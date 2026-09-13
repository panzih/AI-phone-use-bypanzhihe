"""image.py — 屏幕图像的可选缩放、压缩与网格标尺。

重要说明（2026-09-10 后的实际情况）：
服务端自己会处理图片尺寸 —— 官方 Vision 指南写明，图片在推理前会被自动缩放，
使总像素数约为 1300x1300，单图 token 上限 1024。
所以**客户端做缩放不是功能必需**，直接把原始截图发过去也能正常工作。

那这里为什么还保留缩放？纯粹是为了省上传流量和加速：
一张 1080x2400 的 PNG 截图约 2-3 MB，base64 后 3-4 MB，每步都要传一次。
先在本地缩到服务端的目标尺寸附近，上传量能降到 1/3 左右，画质没有损失
（因为服务端反正也会缩到那个尺寸）。

把 config.json 里的 image_shrink 设为 false 就会跳过缩放、直接发送原图。

依赖策略（零依赖优先）：
  - 缩放：优先 Pillow；没有则用 macOS 自带 sips；都没有则原样发送
  - 网格标尺：需要 Pillow（没有就跳过，不影响主流程）
"""
import os
import platform
import struct
import subprocess
import tempfile
from typing import Optional, Tuple


IS_MACOS = platform.system() == "Darwin"

try:
    from PIL import Image, ImageDraw  # type: ignore
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

try:
    from paths import safe_print as _warn
except ImportError:
    # 万一 paths.py 不在搜索路径里（比如被单独引用），退回到普通 print
    def _warn(msg: str) -> None:
        try:
            print(msg)
        except Exception:
            pass


class ImageError(RuntimeError):
    pass


def png_size(data: bytes) -> Tuple[int, int]:
    """从 PNG 字节流里直接读出宽高（读 IHDR 块，不需要解码整张图）。"""
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ImageError("这不是有效的 PNG 数据（可能截图损坏了）")
    if data[12:16] != b"IHDR":
        raise ImageError("PNG 结构异常：缺少 IHDR 块")
    w, h = struct.unpack(">II", data[16:24])
    return int(w), int(h)


def is_all_black(data: bytes, sample_stride: int = 4096) -> bool:
    """粗略检测全黑图。

    部分 ROM 在 DRM 保护页面（视频、银行 App）返回全黑截图，
    这时明确报错比让模型对着黑屏瞎猜要好。
    只做粗采样，避免解码整张图。
    """
    if len(data) < 1024:
        return False
    body = data[1000:]
    sample = body[::sample_stride]
    if not sample:
        return False
    # 全黑 PNG 压缩后极短，这本身就是强信号
    return all(b == 0 for b in sample) and len(data) < 20000


def compute_scale(w: int, h: int, budget: int) -> float:
    """按像素预算算出缩放比例（>=1.0 表示不需要缩小）。"""
    pixels = w * h
    if pixels <= budget:
        return 1.0
    return (float(budget) / float(pixels)) ** 0.5


def scale_dimensions(w: int, h: int, budget: int) -> Tuple[int, int, float]:
    """算出缩放后的尺寸。

    注意：不能简单地用 compute_scale 后各自 round —— 宽高分别四舍五入后相乘，
    结果可能略微超过预算（例如 1080x2400 在 640000 预算下会得到
    537x1193 = 640641 > 640000）。
    这里在必要时把长边再减 1，保证最终像素数真正落在预算内。
    """
    if w * h <= budget:
        return w, h, 1.0

    factor = compute_scale(w, h, budget)
    nw = max(1, int(round(w * factor)))
    nh = max(1, int(round(h * factor)))

    # 逐像素回退，直到真的不超预算
    while nw * nh > budget and (nw > 1 or nh > 1):
        if nw >= nh and nw > 1:
            nw -= 1
        elif nh > 1:
            nh -= 1
        else:
            break

    return nw, nh, float(nw) / float(w)


def _scale_with_sips(png_bytes: bytes, max_edge: int) -> Optional[bytes]:
    """用 macOS 自带 sips 缩放（零依赖方案）。"""
    if not IS_MACOS:
        return None
    tmp_in = tmp_out = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            f.write(png_bytes)
            tmp_in = f.name
        tmp_out = tmp_in.replace(".png", "_s.png")
        proc = subprocess.run(
            ["sips", "-Z", str(int(max_edge)), "--out", tmp_out, tmp_in],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=30,
        )
        if proc.returncode != 0 or not os.path.exists(tmp_out):
            return None
        with open(tmp_out, "rb") as f:
            return f.read()
    except Exception:
        return None
    finally:
        for p in (tmp_in, tmp_out):
            if p and os.path.exists(p):
                try:
                    os.unlink(p)
                except OSError:
                    pass


def _draw_grid(png_bytes: bytes, divisions: int) -> Optional[bytes]:
    """在图上叠加网格标尺，帮助模型建立坐标感。

    这是 GUI agent 常用技巧：模型对绝对像素没有直觉，
    但给出带刻度参考的网格后，坐标估计会明显改善。
    """
    if not HAS_PIL:
        return None
    import io

    try:
        img = Image.open(io.BytesIO(png_bytes)).convert("RGB")
        w, h = img.size
        draw = ImageDraw.Draw(img)
        step_x = w / float(divisions)
        step_y = h / float(divisions)

        for i in range(1, divisions):
            x = int(i * step_x)
            y = int(i * step_y)
            draw.line([(x, 0), (x, h)], fill=(255, 80, 80), width=1)
            draw.line([(0, y), (w, y)], fill=(255, 80, 80), width=1)

        # 标尺文字：横向标 x 像素值，纵向标 y 像素值
        for i in range(1, divisions):
            x = int(i * step_x)
            y = int(i * step_y)
            if i % 2 == 0 or divisions <= 10:
                draw.text((x + 2, 2), str(x), fill=(255, 255, 0))
                draw.text((2, y + 2), str(y), fill=(255, 255, 0))

        buf = io.BytesIO()
        img.save(buf, format="PNG", optimize=True)
        return buf.getvalue()
    except Exception:
        return None


class PreparedImage:
    """准备好发送给模型的图像，并携带坐标换算所需的元数据。"""

    def __init__(
        self,
        data: bytes,
        media_type: str,
        orig_w: int,
        orig_h: int,
        sent_w: int,
        sent_h: int,
        scale: float,
    ):
        self.data = data
        self.media_type = media_type
        self.orig_w = orig_w
        self.orig_h = orig_h
        self.sent_w = sent_w
        self.sent_h = sent_h
        self.scale = scale

    @property
    def size_kb(self) -> float:
        return len(self.data) / 1024.0

    def describe(self) -> str:
        return "%dx%d → %dx%d (%.2fx, %.0f KB, %s)" % (
            self.orig_w, self.orig_h,
            self.sent_w, self.sent_h,
            self.scale, self.size_kb, self.media_type,
        )


def prepare(
    png_bytes: bytes,
    pixel_budget: int = 1690000,
    max_bytes: int = 4194304,
    draw_grid: bool = False,
    grid_divisions: int = 10,
    shrink: bool = True,
) -> PreparedImage:
    """把原始截图处理成可发送的图像。

    流水线：读尺寸 → （可选）按预算缩放 → 超字节上限则缩得更小 →（可选）叠加网格

    shrink=False 时不做任何缩放，直接发送原图 —— 服务端会自己处理尺寸。
    """
    orig_w, orig_h = png_size(png_bytes)

    if not shrink:
        data = png_bytes
        media_type = "image/png"
        sent_w, sent_h, scale = orig_w, orig_h, 1.0
    else:
        sent_w, sent_h, scale = scale_dimensions(orig_w, orig_h, pixel_budget)
        data = png_bytes
        media_type = "image/png"
        if scale < 1.0:
            max_edge = max(sent_w, sent_h)

            if HAS_PIL:
                import io

                img = Image.open(io.BytesIO(png_bytes)).convert("RGB")
                img = img.resize((sent_w, sent_h), Image.LANCZOS)
                buf = io.BytesIO()
                # 优先 JPEG：同样画质下体积远小于 PNG
                quality = 85
                while quality >= 40:
                    buf.seek(0)
                    buf.truncate()
                    img.save(buf, format="JPEG", quality=quality, optimize=True)
                    if buf.tell() <= max_bytes:
                        break
                    quality -= 15
                data = buf.getvalue()
                media_type = "image/jpeg"
            else:
                scaled = _scale_with_sips(png_bytes, max_edge)
                if scaled is not None:
                    data = scaled
                    media_type = "image/png"
                    sent_w, sent_h = png_size(scaled)
                    scale = float(sent_w) / float(orig_w)
                else:
                    # 无法缩放（Windows 且没装 Pillow）：原样发送。
                    # 这不是错误 —— 服务端会自己缩放，只是上传量大一些。
                    _warn(
                        "  [提示] 未安装 Pillow 且系统无图像缩放工具，直接发送原图。\n"
                        "         功能不受影响（服务端会自己缩放），只是上传量大一些。\n"
                        "         想要本地压缩可执行：pip3 install Pillow\n"
                        "         想彻底跳过缩放：把 config.json 的 image_shrink 设为 false"
                    )
                    sent_w, sent_h, scale = orig_w, orig_h, 1.0

    # 叠加网格标尺
    if draw_grid:
        gridded = _draw_grid(data, grid_divisions)
        if gridded is not None:
            data = gridded
            media_type = "image/png"
            sent_w, sent_h = png_size(gridded)

    return PreparedImage(data, media_type, orig_w, orig_h, sent_w, sent_h, scale)
