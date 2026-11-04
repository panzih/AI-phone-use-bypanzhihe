#!/usr/bin/env python3
"""「纸盒」App 15s 宣传片渲染脚本。

分镜（1920x1080 @30fps，内部 4K 超采样后缩到 1080p）：
  0.0-2.4   墨色暗场：立方体 logo 描边生长 + 「纸盒」+ 标语
  2.4-10.8  纸白亮场：手机滑入，三张功能卡依次进入（替你操作 / 技能学习 / 定时任务），
            手机屏内是对应的真实 UI 动效
  10.8-12.6 墨色 hero：你动嘴，它动手
  12.6-15.0 结尾卡：牛皮纸方块 logo + AI 操作手机. + v0.12 CTA，淡出

品牌色取自 app/src/main/java/com/aiphone/assistant/ui/design/Color.kt（Paper / Ink 两套）
与 ic_box_logo.xml / ic_launcher_background（牛皮纸 #E8D5B7 + 棕线 #6B4F2A）。
"""
import datetime
import math
import os
import random
import sys
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FRAMES_DIR = os.path.join(HERE, "frames")
os.makedirs(FRAMES_DIR, exist_ok=True)

FPS = 30
DUR = 15.0
NFRAMES = int(FPS * DUR)          # 450
W, H = 1920, 1080
S = 2                             # 超采样倍数
CW, CH = W * S, H * S

# ---------------------------------------------------------------- 调色板（Color.kt）
INK = dict(canvas="#12110D", surface="#1C1B16", sunken="#0C0B08", outline="#2D2A21",
           outlineS="#474134", ink="#F1EEE6", ink2="#A8A294", ink3="#7B7667",
           accent="#63C7A7", accentSoft="#193A30", accentDeep="#A9DFCE")
PAPER = dict(canvas="#F3F1EB", surface="#FEFDFA", sunken="#EAE7DE", outline="#E2DCD0",
             outlineS="#CBC4B4", ink="#191813", ink2="#5A564C", ink3="#8B8577",
             accent="#186A56", accentSoft="#D8EAE1", accentDeep="#0D3B2F")
KRAFT_BG, KRAFT_LINE = "#E8D5B7", "#6B4F2A"
BEZEL = "#17160F"
SUCCESS, SUCCESS_SOFT = "#2A7648", "#DFEDE3"
DANGER, DANGER_SOFT = "#B23A2E", "#F6E1DD"


def hx(c, a=255):
    c = c.lstrip("#")
    return (int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16), int(a * 255) if a <= 1 else int(a))


def ease_out_cubic(x):
    x = clamp01(x)
    return 1 - (1 - x) ** 3


def ease_in_out(x):
    x = clamp01(x)
    return x * x * (3 - 2 * x)


def ease_out_back(x, s=1.5):
    x = clamp01(x)
    x -= 1
    return 1 + x * x * ((s + 1) * x + s)


def clamp01(x):
    return 0.0 if x < 0 else (1.0 if x > 1 else x)


def seg(t, a, b):
    return clamp01((t - a) / (b - a)) if b > a else (1.0 if t >= a else 0.0)


def lerp(a, b, k):
    return a + (b - a) * k


# ---------------------------------------------------------------- 字体
_FONT_DEF = {
    "sans": ("/System/Library/Fonts/Hiragino Sans GB.ttc", 0),    # W3
    "sansb": ("/System/Library/Fonts/Hiragino Sans GB.ttc", 2),   # W6
    "song": ("/System/Library/Fonts/Supplemental/Songti.ttc", 0),  # SC Black
    "songb": ("/System/Library/Fonts/Supplemental/Songti.ttc", 1),  # SC Bold
    "lat": ("/System/Library/Fonts/Helvetica.ttc", 0),
    "latb": ("/System/Library/Fonts/Helvetica.ttc", 1),
}
_FONTS = {}


def F(key, size):
    size = max(4, int(size * S))
    key = (key, size)
    if key not in _FONTS:
        path, idx = _FONT_DEF[key[0]]
        _FONTS[key] = ImageFont.truetype(path, size, index=idx)
    return _FONTS[key]


def txt(d, xy, s, font, fill, anchor="lm"):
    d.text(xy, s, font=font, fill=fill, anchor=anchor)


def paste_over(base, im, x, y):
    """支持负坐标的贴图（alpha 混合）。x,y 为画布像素坐标。"""
    base.paste(im, (int(x), int(y)), im)


def new_layer():
    return Image.new("RGBA", (CW, CH), (0, 0, 0, 0))


def fade_layer(im, k):
    k = clamp01(k)
    if k >= 0.999:
        return im
    if k <= 0.001:
        im.paste((0, 0, 0, 0), [0, 0, im.width, im.height])
        return im
    im.putalpha(im.getchannel("A").point(lambda v: int(v * k)))
    return im


# ---------------------------------------------------------------- 立方体 logo（ic_box_logo.xml 的几何）
CUBE_PTS = dict(A=(20, 88), B=(20, 46), C=(46, 20), D=(88, 20), E=(88, 62),
                F=(62, 88), G=(62, 46))
CUBE_EDGES = [("A", "B"), ("B", "C"), ("C", "D"), ("D", "E"), ("E", "F"), ("F", "A"),
              ("B", "G"), ("G", "F"), ("G", "D")]


def draw_cube(d, x0, y0, size, color, w, progress=1.0):
    """按周长进度描边生长，圆头笔触，和 app 内 logo 一致。坐标均为 design 单位。"""
    x0, y0, size, w = x0 * S, y0 * S, size * S, w * S
    k = size / 68.0

    def pt(n):
        p = CUBE_PTS[n]
        return (x0 + (p[0] - 20) * k, y0 + (p[1] - 20) * k)

    segs = [(pt(a), pt(b)) for a, b in CUBE_EDGES]
    lens = [math.dist(a, b) for a, b in segs]
    target = sum(lens) * clamp01(progress)
    acc = 0.0
    w = max(1, int(w))
    for (a, b), L in zip(segs, lens):
        if target <= acc + 1e-6:
            break
        frac = min(1.0, (target - acc) / L)
        end = (a[0] + (b[0] - a[0]) * frac, a[1] + (b[1] - a[1]) * frac)
        d.line([a, end], fill=color, width=w)
        r = w / 2.0
        for px, py in (a, end):
            d.ellipse([px - r, py - r, px + r, py + r], fill=color)
        acc += L
        if frac < 1.0:
            break


# ---------------------------------------------------------------- 光晕 / 背景 / 噪点
def glow_sprite(radius, rgb, peak):
    size = radius * 2
    y, x = np.mgrid[0:size, 0:size]
    dd = np.sqrt((x - radius) ** 2 + (y - radius) ** 2) / radius
    a = (np.clip(1 - dd, 0, 1) ** 2.4 * peak).astype(np.uint8)
    arr = np.zeros((size, size, 4), np.uint8)
    arr[..., 0], arr[..., 1], arr[..., 2], arr[..., 3] = rgb[0], rgb[1], rgb[2], a
    return Image.fromarray(arr)


def build_dark_bg():
    """墨色渐变 + 暗角，一次性烘焙。"""
    top = np.array(hx(INK["sunken"])[:3], float)
    mid = np.array(hx(INK["canvas"])[:3], float)
    bot = np.array(hx(INK["surface"])[:3], float)
    rows = np.zeros((CH, 3), float)
    yy = np.linspace(0, 1, CH)
    for i, v in enumerate(yy):
        rows[i] = top + (mid - top) * (v / 0.45) if v < 0.45 else mid + (bot - mid) * ((v - 0.45) / 0.55)
    img = np.repeat(rows[:, None, :], CW, axis=1).astype(np.uint8)
    arr = np.array(Image.fromarray(img, "RGB"))
    # 暗角
    yv, xv = np.mgrid[0:CH, 0:CW]
    dv = np.sqrt(((xv - CW / 2) / (CW / 2)) ** 2 + ((yv - CH / 2) / (CH / 2)) ** 2)
    va = (np.clip(dv - 0.55, 0, 1) ** 1.8 * 120).astype(np.uint8) / 255.0
    arr[..., 0] = (arr[..., 0] * (1 - va / 255.0)).astype(np.uint8)
    arr[..., 1] = (arr[..., 1] * (1 - va / 255.0)).astype(np.uint8)
    arr[..., 2] = (arr[..., 2] * (1 - va / 255.0)).astype(np.uint8)
    return Image.fromarray(arr, "RGB").convert("RGBA")


def build_paper_bg():
    """纸白底 + 极淡暖光 + 边缘沉一点。"""
    base = np.zeros((CH, CW, 3), np.uint8)
    base[..., 0], base[..., 1], base[..., 2] = hx(PAPER["canvas"])[:3]
    yv, xv = np.mgrid[0:CH, 0:CW]
    dv = np.sqrt(((xv - CW / 2) / (CW / 2)) ** 2 + ((yv - CH / 2) / (CH / 2)) ** 2)
    edge = np.clip(dv - 0.5, 0, 1) ** 1.7 * 0.55
    warm = np.clip(1 - dv, 0, 1) ** 2 * 0.30
    c_edge = np.array(hx(PAPER["sunken"])[:3], float)
    c_warm = np.array(hx(PAPER["surface"])[:3], float)
    out = base.astype(float)
    for ch in range(3):
        out[..., ch] = out[..., ch] * (1 - edge) + c_edge[ch] * edge
        out[..., ch] = out[..., ch] * (1 - warm) + c_warm[ch] * warm
    return Image.fromarray(out.astype(np.uint8), "RGB").convert("RGBA")


def build_grain():
    """两张全幅噪点层，逐帧随机平移，胶片感。"""
    imgs = []
    for seed in (11, 12):
        rng = np.random.default_rng(seed)
        a = rng.integers(0, 16, (CH, CW), dtype=np.uint8)
        arr = np.zeros((CH, CW, 4), np.uint8)
        arr[..., 0] = arr[..., 1] = arr[..., 2] = 255
        arr[..., 3] = a
        imgs.append(Image.fromarray(arr, "RGBA"))
    return imgs


GLOW_MINT = glow_sprite(760, hx(INK["accent"])[:3], 74)
GLOW_KRAFT = glow_sprite(520, hx(KRAFT_BG)[:3], 44)
GLOW_MINT_BIG = glow_sprite(980, hx(INK["accent"])[:3], 88)
BG_DARK = build_dark_bg()
BG_PAPER = build_paper_bg()
GRAINS = build_grain()


class Particles:
    def __init__(self, n, seed, colors, amin=26, amax=86, size=(3, 9)):
        rng = random.Random(seed)
        self.p = [dict(x=rng.uniform(0, W), y=rng.uniform(0, H),
                       vy=rng.uniform(14, 52), ph=rng.uniform(0, 6.28),
                       r=rng.uniform(*size), c=rng.choice(colors),
                       a=rng.uniform(amin, amax), sway=rng.uniform(6, 26)) for _ in range(n)]

    def draw(self, d, t, k=1.0, converge=0.0):
        for p in self.p:
            y = (p["y"] - p["vy"] * t) % (H + 60) - 30
            x = (p["x"] + math.sin(t * 0.6 + p["ph"]) * p["sway"]) % W
            if converge > 0:
                x = lerp(x, W / 2 + (x - W / 2) * 0.35, converge)
                y = lerp(y, H / 2 + (y - H / 2) * 0.35, converge)
            tw = 0.55 + 0.45 * math.sin(t * 1.7 + p["ph"] * 2)
            r = p["r"] * S
            col = hx(p["c"], p["a"] * tw * k)
            d.ellipse([x * S - r, y * S - r, x * S + r, y * S + r], fill=col)


PART_DARK = Particles(64, 5, [INK["accent"], KRAFT_BG, INK["ink"]])
PART_PAPER = Particles(40, 6, [KRAFT_LINE, PAPER["accent"]], amin=14, amax=40, size=(2, 6))


# ---------------------------------------------------------------- 手机 mockup
PHONE_W, PHONE_H = 402, 838
SCR = (13, 13, PHONE_W - 13, PHONE_H - 13)   # 屏幕内框（design 坐标）
SCR_W, SCR_H = SCR[2] - SCR[0], SCR[3] - SCR[1]


def build_phone():
    im = Image.new("RGBA", (PHONE_W * S, PHONE_H * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, PHONE_W * S, PHONE_H * S], radius=58 * S, fill=hx(BEZEL))
    d.rounded_rectangle([2 * S, 2 * S, (PHONE_W - 2) * S, (PHONE_H - 2) * S],
                        radius=56 * S, outline=hx("#3B362A"), width=3)
    d.rounded_rectangle([SCR[0] * S, SCR[1] * S, SCR[2] * S, SCR[3] * S],
                        radius=44 * S, fill=hx(PAPER["surface"]))
    # 听筒
    d.rounded_rectangle([158 * S, 26 * S, 244 * S, 44 * S], radius=9 * S, fill=hx(BEZEL))
    # 侧键
    d.rounded_rectangle([PHONE_W * S - 3, 210 * S, PHONE_W * S + 4, 300 * S], radius=3, fill=hx("#2A2519"))
    d.rounded_rectangle([-4, 190 * S, 3, 262 * S], radius=3, fill=hx("#2A2519"))
    return im, SCR


PHONE, SCR_BOX = build_phone()


def phone_screen_layer():
    im = Image.new("RGBA", (SCR_W * S, SCR_H * S), (0, 0, 0, 0))
    return im, ImageDraw.Draw(im)


def finish_screen_layer(im, veil=0.0):
    if veil > 0:
        # 注意：ImageDraw 直写像素会抹掉下层内容，白纱必须走 alpha_composite
        im.alpha_composite(Image.new("RGBA", im.size, (253, 253, 250, int(veil))))
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, im.width, im.height], radius=40 * S, fill=255)
    im.putalpha(Image.composite(im.getchannel("A"), Image.new("L", im.size, 0), mask))
    return im


def paste_phone(base, x, y, slt, ui_fn, ui_t):
    """把带 UI 的手机贴到画布，(x,y) 为左上角 design 坐标。"""
    spr = PHONE.copy()
    lay, d = phone_screen_layer()
    ui_fn(d, ui_t)
    # 换屏瞬间的一层白纱，像屏幕刷新
    veil = max(0.0, 1.0 - ui_t / 0.28) * 175 if ui_t < 0.28 else 0.0
    spr.alpha_composite(finish_screen_layer(lay, veil), (SCR[0] * S, SCR[1] * S))
    # 投影
    sh = new_layer()
    ds = ImageDraw.Draw(sh)
    ds.rounded_rectangle([x * S + 6, y * S + 26, (x + PHONE_W) * S + 6, (y + PHONE_H) * S + 26],
                         radius=58 * S, fill=(20, 18, 12, 66))
    paste_over(base, sh.filter(__import__("PIL.ImageFilter", fromlist=["GaussianBlur"]).GaussianBlur(26)), 0, 0)
    paste_over(base, spr, x * S, y * S)


# ---------------------------------------------------------------- 手机屏内 UI（design 坐标，屏内原点）
def _appbar(d, title, slt):
    p = PAPER
    a = seg(slt, 0.0, 0.25)
    draw_cube(d, 20, 24, 30, hx(KRAFT_LINE), 4.4, 1.0)
    txt(d, (62 * S, 39 * S), title, F("sansb", 27), hx(p["ink"], a), "lm")
    d.line([20 * S, 72 * S, (SCR_W - 20) * S, 72 * S], fill=hx(p["outline"], a), width=2)
    # 状态栏
    txt(d, (24 * S, 20 * S), "9:41", F("latb", 19), hx(p["ink"], a), "lm")
    d.rounded_rectangle([(SCR_W - 52) * S, 14 * S, (SCR_W - 28) * S, 26 * S], radius=3 * S,
                        outline=hx(p["ink2"], a), width=2)
    d.rectangle([(SCR_W - 50) * S, 16 * S, (SCR_W - 40) * S, 24 * S], fill=hx(p["ink2"], a))


def _input_bar(d, slt):
    p = PAPER
    y = SCR_H - 78
    d.rounded_rectangle([20 * S, y * S, (SCR_W - 74) * S, (y + 58) * S], radius=29 * S,
                        fill=hx(p["canvas"]), outline=hx(p["outline"]), width=2)
    txt(d, (44 * S, (y + 29) * S), "告诉 AI 下一步做什么", F("sans", 22), hx(p["ink3"]), "lm")
    cx, cy = (SCR_W - 46) * S, (y + 29) * S
    d.ellipse([cx - 23 * S, cy - 23 * S, cx + 23 * S, cy + 23 * S], fill=hx(p["accent"]))
    d.line([cx - 8 * S, cy, cx + 8 * S, cy], fill=hx(p["surface"]), width=5 * S)
    d.line([cx + 2 * S, cy - 6 * S, cx + 9 * S, cy + 0.5 * S], fill=hx(p["surface"]), width=5 * S)
    d.line([cx + 2 * S, cy + 6 * S, cx + 9 * S, cy - 0.5 * S], fill=hx(p["surface"]), width=5 * S)


def ui_task(d, slt):
    """功能 1：替你操作 —— 对话 + 执行涟漪。"""
    p = PAPER
    _appbar(d, "操作手机", slt)
    a = seg(slt, 0.08, 0.35)
    # 用户气泡（两行）
    bx, bw = SCR_W - 20, 272
    by = 104 + (1 - ease_out_cubic(a)) * 24
    d.rounded_rectangle([(bx - bw) * S, by * S, bx * S, (by + 96) * S], radius=20 * S, fill=hx(p["accent"], a))
    txt(d, ((bx - bw + 22) * S, (by + 30) * S), "把 WiFi 打开，", F("sans", 25), hx(p["surface"], a), "lm")
    txt(d, ((bx - bw + 22) * S, (by + 64) * S), "再看下电量", F("sans", 25), hx(p["surface"], a), "lm")
    # AI 执行卡
    a2 = seg(slt, 0.3, 0.65)
    cy0 = 232 + (1 - ease_out_cubic(a2)) * 24
    d.rounded_rectangle([20 * S, cy0 * S, (SCR_W - 20) * S, (cy0 + 118) * S], radius=20 * S,
                        fill=hx(p["surface"], a2), outline=hx(p["outline"], a2), width=2)
    txt(d, (42 * S, (cy0 + 26) * S), "正在执行 · 第 2 步", F("sansb", 24), hx(p["accent"], a2), "lm")
    prog = (0.18 + 0.62 * ease_in_out(seg(slt, 0.7, 2.6))) * a2
    d.rounded_rectangle([42 * S, (cy0 + 56) * S, (SCR_W - 42) * S, (cy0 + 64) * S],
                        radius=4 * S, fill=hx(p["sunken"], a2))
    if prog > 0.02:
        d.rounded_rectangle([42 * S, (cy0 + 56) * S, (42 + (SCR_W - 84) * prog) * S, (cy0 + 64) * S],
                            radius=4 * S, fill=hx(p["accent"], a2))
    txt(d, (42 * S, (cy0 + 94) * S), "点击「WLAN」开关", F("sans", 21), hx(p["ink3"], a2), "lm")
    # 设置行 mock：WLAN 开关
    a3 = seg(slt, 0.55, 0.9)
    ry = 392 + (1 - ease_out_cubic(a3)) * 24
    d.rounded_rectangle([20 * S, ry * S, (SCR_W - 20) * S, (ry + 76) * S], radius=18 * S,
                        fill=hx(p["sunken"], a3))
    txt(d, (44 * S, (ry + 38) * S), "WLAN", F("sansb", 26), hx(p["ink"], a3), "lm")
    tx0, ty0 = SCR_W - 88, ry + 38
    flip = ease_in_out(seg(slt, 1.15, 1.45))
    on = flip
    track = hx(p["outlineS"]) if on < 0.5 else hx(p["accent"])
    d.rounded_rectangle([tx0 * S, (ty0 - 19) * S, (tx0 + 64) * S, (ty0 + 19) * S], radius=19 * S,
                        fill=track)
    kx = (tx0 + 19 + 26 * on) * S
    d.ellipse([kx - 16 * S, (ty0 - 16) * S, kx + 16 * S, (ty0 + 16) * S], fill=hx(p["surface"]))
    # 涟漪（开关心跳）
    for i in range(3):
        rp = seg(slt, 1.15 + i * 0.28, 1.15 + i * 0.28 + 0.75)
        if 0 < rp < 1:
            r = (12 + 86 * ease_out_cubic(rp)) * S
            al = (1 - rp) * 150
            d.ellipse([tx0 * S + 32 * S - r, ty0 * S - r, tx0 * S + 32 * S + r, ty0 * S + r],
                      outline=hx(p["accent"], al), width=4)
    # 第二行静态
    a4 = seg(slt, 0.75, 1.05)
    ry2 = ry + 92
    d.rounded_rectangle([20 * S, ry2 * S, (SCR_W - 20) * S, (ry2 + 76) * S], radius=18 * S,
                        fill=hx(p["sunken"], a4))
    txt(d, (44 * S, (ry2 + 38) * S), "蓝牙", F("sansb", 26), hx(p["ink2"], a4), "lm")
    d.rounded_rectangle([tx0 * S, (ry2 + 19 - 19) * S, (tx0 + 64) * S, (ry2 + 38) * S],
                        radius=19 * S, fill=hx(p["outlineS"], a4))
    kx2 = (tx0 + 19) * S
    d.ellipse([kx2 - 16 * S, (ry2 + 22 - 16) * S, kx2 + 16 * S, (ry2 + 22 + 16) * S],
              fill=hx(p["surface"], a4))
    _input_bar(d, slt)


def ui_record(d, slt):
    """功能 2：技能学习 —— 录制步骤 -> 学成技能。"""
    p = PAPER
    _appbar(d, "操作记录", slt)
    # REC 条
    a = seg(slt, 0.08, 0.35)
    py = 104 + (1 - ease_out_cubic(a)) * 24
    d.rounded_rectangle([20 * S, py * S, (SCR_W - 20) * S, (py + 64) * S], radius=20 * S,
                        fill=hx(DANGER_SOFT, a))
    pulse = 0.5 + 0.5 * math.sin(slt * 7)
    d.ellipse([44 * S, (py + 32 - 11) * S, (44 + 22) * S, (py + 32 + 11) * S],
              fill=hx(DANGER, a * (0.45 + 0.55 * pulse)))
    txt(d, (84 * S, (py + 32) * S), "录制中 · 已记录 3 步", F("sansb", 25), hx(p["ink"], a), "lm")
    steps = ["打开微信", "点右上角 ＋ 号", "选一张照片，点发送"]
    for i, s in enumerate(steps):
        a2 = seg(slt, 0.25 + i * 0.32, 0.6 + i * 0.32)
        y = 196 + i * 76 + (1 - ease_out_cubic(a2)) * 20
        d.rounded_rectangle([20 * S, y * S, (SCR_W - 20) * S, (y + 62) * S], radius=16 * S,
                            fill=hx(p["canvas"], a2))
        d.rounded_rectangle([36 * S, (y + 13) * S, (36 + 36) * S, (y + 49) * S], radius=11 * S,
                            fill=hx(p["accentSoft"], a2))
        txt(d, (54 * S, (y + 31) * S), str(i + 1), F("latb", 23), hx(p["accent"], a2), "mm")
        txt(d, (92 * S, (y + 31) * S), s, F("sans", 25), hx(p["ink"], a2), "lm")
    # 学会卡
    a3 = ease_out_back(seg(slt, 1.75, 2.2))
    cy0 = 442
    d.rounded_rectangle([20 * S, (cy0 + (1 - min(a3, 1)) * 30) * S, (SCR_W - 20) * S,
                         (cy0 + 158 + (1 - min(a3, 1)) * 30) * S], radius=20 * S,
                        fill=hx(SUCCESS_SOFT, min(a3, 1)), outline=hx(SUCCESS, min(a3, 1)), width=3)
    ccx, ccy = 62, cy0 + 44
    d.ellipse([(ccx - 22) * S, (ccy - 22) * S, (ccx + 22) * S, (ccy + 22) * S],
              fill=hx(SUCCESS, min(a3, 1)))
    d.line([ccx * S - 10 * S, ccy * S, ccx * S - 2 * S, ccy * S + 9 * S],
           fill=hx(p["surface"], min(a3, 1)), width=5 * S)
    d.line([ccx * S - 2 * S, ccy * S + 9 * S, ccx * S + 12 * S, ccy * S - 9 * S],
           fill=hx(p["surface"], min(a3, 1)), width=5 * S)
    txt(d, (104 * S, (ccy) * S), "已学会「发朋友圈」", F("sansb", 27), hx(p["ink"], min(a3, 1)), "lm")
    txt(d, (42 * S, (cy0 + 112) * S), "以后一句话，就能重放", F("sans", 23), hx(p["ink2"], min(a3, 1)), "lm")
    _input_bar(d, slt)


def ui_schedule(d, slt):
    """功能 3：定时任务 —— 时钟跳到 08:00 触发。"""
    p = PAPER
    _appbar(d, "定时任务", slt)
    a = seg(slt, 0.08, 0.35)
    today = datetime.date.today()
    wk = "一二三四五六日"[today.weekday()]
    date_str = f"{today.month}月{today.day}日 周{wk}"
    flip = ease_in_out(seg(slt, 1.25, 1.5))
    hh, mm = (8, 0) if flip > 0.5 else (7, 59)
    flash = 1 - abs(flip - 0.5) * 2 if flip in (0.5,) else (seg(slt, 1.25, 1.6) * (1 - seg(slt, 1.6, 2.2)))
    col = p["accent"] if flip >= 0.5 else p["ink"]
    txt(d, (20 * S, 130 * S), f"{hh:02d}:{mm:02d}", F("latb", 64), hx(col, a), "lm")
    txt(d, (20 * S, 186 * S), date_str, F("sans", 22), hx(p["ink3"], a), "lm")
    # 任务卡
    a2 = seg(slt, 0.3, 0.65)
    cy0 = 240 + (1 - ease_out_cubic(a2)) * 24
    pulse_w = 2 + (2 * flash if flip >= 0.5 else 0)
    d.rounded_rectangle([20 * S, cy0 * S, (SCR_W - 20) * S, (cy0 + 168) * S], radius=20 * S,
                        fill=hx(p["surface"], a2),
                        outline=hx(p["accent"], a2) if flip >= 0.5 else hx(p["outline"], a2),
                        width=int(pulse_w))
    # 时间 chip
    d.rounded_rectangle([42 * S, (cy0 + 22) * S, (42 + 150) * S, (cy0 + 60) * S], radius=12 * S,
                        fill=hx(p["accentSoft"], a2))
    txt(d, (117 * S, (cy0 + 41) * S), "每天 08:00", F("sansb", 22), hx(p["accent"], a2), "mm")
    # 开关
    tx0 = SCR_W - 88
    d.rounded_rectangle([tx0 * S, (cy0 + 22) * S, (tx0 + 64) * S, (cy0 + 60) * S], radius=19 * S,
                        fill=hx(p["accent"], a2))
    d.ellipse([(tx0 + 29) * S, (cy0 + 25) * S, (tx0 + 61) * S, (cy0 + 57) * S], fill=hx(p["surface"], a2))
    txt(d, (42 * S, (cy0 + 96) * S), "打开日历，看看今天安排", F("sans", 26), hx(p["ink"], a2), "lm")
    txt(d, (42 * S, (cy0 + 136) * S), "一次性任务跑完自动关闭", F("sans", 20), hx(p["ink3"], a2), "lm")
    # 第二条任务（未启用）
    a5 = seg(slt, 0.85, 1.2)
    cy1 = 436 + (1 - ease_out_cubic(a5)) * 24
    d.rounded_rectangle([20 * S, cy1 * S, (SCR_W - 20) * S, (cy1 + 128) * S], radius=20 * S,
                        fill=hx(p["surface"], a5), outline=hx(p["outline"], a5), width=2)
    d.rounded_rectangle([42 * S, (cy1 + 18) * S, (42 + 150) * S, (cy1 + 54) * S], radius=12 * S,
                        fill=hx(p["sunken"], a5))
    txt(d, (117 * S, (cy1 + 36) * S), "每天 22:30", F("sansb", 21), hx(p["ink2"], a5), "mm")
    d.rounded_rectangle([tx0 * S, (cy1 + 18) * S, (tx0 + 64) * S, (cy1 + 54) * S], radius=17 * S,
                        fill=hx(p["outlineS"], a5))
    d.ellipse([(tx0 + 3) * S, (cy1 + 21) * S, (tx0 + 35) * S, (cy1 + 51) * S], fill=hx(p["surface"], a5))
    txt(d, (42 * S, (cy1 + 90) * S), "晚上十点半，开勿扰", F("sans", 24), hx(p["ink2"], a5), "lm")
    # 触发标签
    a3 = ease_out_back(seg(slt, 1.55, 2.0))
    ty = cy0 + 196 + 160
    d.rounded_rectangle([20 * S, ty * S, (20 + 208) * S, (ty + 56) * S], radius=16 * S,
                        fill=hx(SUCCESS_SOFT, min(a3, 1)))
    d.line([40 * S, (ty + 28) * S, 48 * S, (ty + 37) * S], fill=hx(SUCCESS, min(a3, 1)), width=5 * S)
    d.line([48 * S, (ty + 37) * S, 62 * S, (ty + 19) * S], fill=hx(SUCCESS, min(a3, 1)), width=5 * S)
    txt(d, (80 * S, (ty + 28) * S), "已开始执行", F("sansb", 24), hx(SUCCESS, min(a3, 1)), "lm")
    txt(d, (20 * S, (SCR_H - 130) * S), "到点提醒你，你看着它干", F("sans", 20), hx(p["ink3"], a2), "lm")
    _input_bar(d, slt)


# ---------------------------------------------------------------- 功能卡（右侧）
FEATURES = [
    dict(idx="01", title="替你操作", sub="你说一句，AI 替你点",
         chips=["每步可见", "随时急停"], ui=ui_task),
    dict(idx="02", title="技能学习", sub="手动录一遍，AI 学成技能",
         chips=["一句话重放", "自动起名"], ui=ui_record),
    dict(idx="03", title="定时任务", sub="到点自动跑，不用盯",
         chips=["每天重复", "准点触发"], ui=ui_schedule),
]


def icon_tap(d, cx, cy, s, col):
    cx, cy = cx * s, cy * s
    d.ellipse([cx - 11 * s, cy - 11 * s, cx + 11 * s, cy + 11 * s], fill=col)
    for r0, a0, a1 in ((30, -150, -20), (42, -170, -40)):
        bb = [cx - r0 * s, cy - r0 * s, cx + r0 * s, cy + r0 * s]
        d.arc(bb, a0, a1, fill=col, width=int(5 * s))


def icon_record(d, cx, cy, s, col):
    cx, cy = cx * s, cy * s
    d.ellipse([cx - 34 * s, cy - 34 * s, cx + 34 * s, cy + 34 * s], outline=col, width=int(5 * s))
    d.ellipse([cx - 12 * s, cy - 12 * s, cx + 12 * s, cy + 12 * s], fill=col)
    for dx, dy in ((-46, -46), (46, 46)):
        d.line([cx + dx * s, cy + (dy + 8) * s, cx + dx * s, cy + (dy - 8) * s], fill=col, width=int(4 * s))
        d.line([cx + (dx - 8) * s, cy + dy * s, cx + (dx + 8) * s, cy + dy * s], fill=col, width=int(4 * s))


def icon_clock(d, cx, cy, s, col):
    cx, cy = cx * s, cy * s
    d.ellipse([cx - 34 * s, cy - 34 * s, cx + 34 * s, cy + 34 * s], outline=col, width=int(5 * s))
    d.line([cx, cy, cx, cy - 19 * s], fill=col, width=int(5 * s))
    d.line([cx, cy, cx + 13 * s, cy + 7 * s], fill=col, width=int(5 * s))
    r = 3 * s
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)


def rounded_pill(d, x, y, text, font, fg, bg, padx=18, h=44):
    w = font.getlength(text) / S + padx * 2
    d.rounded_rectangle([x * S, y * S, (x + w) * S, (y + h) * S], radius=h / 2 * S, fill=bg)
    txt(d, ((x + w / 2) * S, (y + h / 2) * S), text, font, fg, "mm")
    return w


def draw_feature_card(d, f, alpha, dy, icon_wob):
    p = PAPER
    x0, y0, cw, chh = 1000, 385, 780, 310
    y0 += dy
    A = alpha
    # 阴影
    d.rounded_rectangle([(x0 + 4) * S, (y0 + 14) * S, (x0 + cw + 4) * S, (y0 + chh + 14) * S],
                        radius=30 * S, fill=(90, 86, 76, int(40 * A)))
    d.rounded_rectangle([x0 * S, y0 * S, (x0 + cw) * S, (y0 + chh) * S], radius=30 * S,
                        fill=hx(p["surface"], A), outline=hx(p["outline"], A), width=2)
    icx, icy = 1096, y0 + 108
    d.ellipse([(icx - 55) * S, (icy - 55) * S, (icx + 55) * S, (icy + 55) * S],
              fill=hx(p["accentSoft"], A))
    icol = hx(p["accent"], A)
    if f["idx"] == "01":
        icon_tap(d, icx, icy, S, icol)
    elif f["idx"] == "02":
        icon_record(d, icx, icy, S, icol)
    else:
        icon_clock(d, icx, icy, S, icol)
    txt(d, ((x0 + cw - 42) * S, (y0 + 56) * S), f["idx"], F("songb", 46), hx(p["outlineS"], A), "rm")
    txt(d, (1186 * S, (y0 + 84) * S), f["title"], F("song", 60), hx(p["ink"], A), "lm")
    txt(d, (1186 * S, (y0 + 156) * S), f["sub"], F("sans", 35), hx(p["ink2"], A), "lm")
    xx = 1096 - 55
    for chip in f["chips"]:
        w = rounded_pill(d, xx, y0 + 208, chip, F("sansb", 24),
                         hx(p["accent"], A), hx(p["accentSoft"], A))
        xx += w + 18


# ---------------------------------------------------------------- 场景
def overlay_flash(base, t, t0, color="#FEFDFA", dur=0.34, peak=1.0):
    dt = t - t0
    if 0 <= dt < dur:
        a = peak * (1 - dt / dur) ** 1.6
        ov = Image.new("RGBA", (CW, CH), hx(color, a))
        base.alpha_composite(ov)


def black_overlay(base, a):
    if a > 0.001:
        base.alpha_composite(Image.new("RGBA", (CW, CH), (5, 5, 3, a)))


def scene_intro(base, t):
    paste_over(base, GLOW_MINT.resize((1300 * S, 1300 * S)), (960 - 650) * S, (360 - 650) * S)
    ov = new_layer()
    d = ImageDraw.Draw(ov)
    PART_DARK.draw(d, t, k=1.0)
    # logo 描边生长
    gp = ease_out_cubic(seg(t, 0.2, 1.3))
    gcol = hx(INK["accent"], 90)
    draw_cube(d, 960 - 105, 240, 210, gcol, 26, gp)      # 底层绿晕边
    draw_cube(d, 960 - 105, 240, 210, hx(KRAFT_BG), 9, gp)
    # 名称与标语
    a1 = seg(t, 1.05, 1.65)
    dy1 = (1 - ease_out_cubic(a1)) * 26
    f = F("song", 124)
    tr = 22
    wtot = f.getlength("纸盒") / S + tr
    sx = 960 - wtot / 2
    for i, chx in enumerate("纸盒"):
        ai = seg(t, 1.05 + i * 0.09, 1.6 + i * 0.09)
        txt(d, ((sx + i * (f.getlength(chx) / S + tr)) * S, (560 + dy1) * S), chx, f,
            hx(INK["ink"], ai), "lm")
    a2 = seg(t, 1.4, 2.0)
    dy2 = (1 - ease_out_cubic(a2)) * 18
    # 「让 AI 替你操作手机」，AI 高亮
    f2, f2b = F("sans", 42), F("sansb", 44)
    parts = [("让 ", f2, INK["ink2"]), ("AI", f2b, INK["accent"]), (" 替你操作手机", f2, INK["ink2"])]
    wtot = sum(ft.getlength(s) / S for s, ft, _ in parts)
    sx = 960 - wtot / 2
    for s, ft, c in parts:
        txt(d, (sx * S, (678 + dy2) * S), s, ft, hx(c, a2), "lm")
        sx += ft.getlength(s) / S
    a3 = seg(t, 1.6, 2.15)
    txt(d, (960 * S, 1000 * S), "By 潘纸盒", F("sans", 25), hx(INK["ink3"], a3), "mm")
    base.alpha_composite(ov)


def scene_features(base, t):
    lt = t - 2.4
    idx = min(2, int(lt // 2.8))
    slt = lt - idx * 2.8
    f = FEATURES[idx]
    ov = new_layer()
    d = ImageDraw.Draw(ov)
    PART_PAPER.draw(d, t, k=1.0)
    # 顶栏品牌
    ab = seg(lt, 0.15, 0.55)
    draw_cube(d, 84, 62, 44, hx(KRAFT_LINE), 5, 1.0)
    txt(d, (146 * S, 84 * S), "纸盒", F("sansb", 36), hx(PAPER["ink"], ab), "lm")
    txt(d, (1836 * S, 84 * S), "v0.12.0", F("lat", 26), hx(PAPER["ink3"], ab), "rm")
    # 手机
    px = lerp(-520, 170, ease_out_cubic(seg(lt, 0.0, 0.6)))
    py = 121 + 4 * math.sin(t * 1.1)
    paste_phone(base, px, py, slt, f["ui"], slt)
    # 功能卡
    enter = seg(slt, 0.1, 0.5)
    exit_ = seg(slt, 2.52, 2.8)
    alpha = ease_out_cubic(enter) * (1 - ease_in_out(exit_))
    dy = (1 - ease_out_cubic(enter)) * 46 - ease_in_out(exit_) * 30
    if alpha > 0.01:
        draw_feature_card(d, f, alpha, dy, slt)
    # 进度点
    for i in range(3):
        cx = 1400 - 60 + i * 60
        if i == idx:
            act = ease_out_cubic(seg(slt, 0.1, 0.45))
            wdt = 18 + 14 * act
            d.rounded_rectangle([(cx - wdt) * S, 765 * S, (cx + wdt) * S, 775 * S],
                                radius=5 * S, fill=hx(PAPER["accent"], act))
        else:
            d.ellipse([(cx - 5) * S, 765 * S, (cx + 5) * S, 775 * S], fill=hx(PAPER["outlineS"], 0.9))
    base.alpha_composite(ov)


def scene_hero(base, t):
    lt = t - 10.8
    paste_over(base, GLOW_MINT_BIG.resize((1500 * S, 1500 * S)), (960 - 750) * S, (500 - 750) * S)
    ov = new_layer()
    d = ImageDraw.Draw(ov)
    PART_DARK.draw(d, t + 40, k=0.9, converge=min(1.0, lt / 1.2))
    # 背景大立方体水印
    draw_cube(d, 960 - 300 + math.sin(lt * 0.7) * 10, 540 - 300, 600, hx(INK["outline"], 150), 5, 1.0)
    # 两行大字
    for line, col, t0 in (("你动嘴", INK["ink"], 0.18), ("它动手", INK["accent"], 0.48)):
        a = ease_out_cubic(seg(lt, t0, t0 + 0.45))
        if a <= 0:
            continue
        f = F("song", 172)
        lw, lh = int(f.getlength(line)) + 60 * S, 260 * S
        tl = Image.new("RGBA", (lw, lh), (0, 0, 0, 0))
        ImageDraw.Draw(tl).text((lw // 2, lh // 2 - 20 * S), line, font=f, fill=hx(col), anchor="mm")
        sc = lerp(1.18, 1.0, a)
        tl = tl.resize((max(1, int(lw * sc)), max(1, int(lh * sc))))
        paste_over(ov, fade_layer(tl, a),
                   960 * S - tl.width / 2, (400 if line == "你动嘴" else 600) * S - tl.height / 2)
    # 下划线 + 副标
    ul = ease_out_cubic(seg(lt, 0.95, 1.4))
    if ul > 0:
        wdt = 430 * ul
        d.rounded_rectangle([(960 - wdt / 2) * S, 742 * S, (960 + wdt / 2) * S, 752 * S],
                            radius=5 * S, fill=hx(INK["accent"], 220))
    a2 = seg(lt, 1.1, 1.55)
    txt(d, (960 * S, 820 * S), "AI 直接操作你的安卓手机", F("sans", 40), hx(INK["ink2"], a2), "mm")
    base.alpha_composite(ov)


def scene_ending(base, t):
    lt = t - 12.6
    paste_over(base, GLOW_KRAFT.resize((1100 * S, 1100 * S)), (960 - 550) * S, (330 - 550) * S)
    paste_over(base, GLOW_MINT.resize((1200 * S, 1200 * S)), (960 - 600) * S, (760 - 600) * S)
    ov = new_layer()
    d = ImageDraw.Draw(ov)
    PART_DARK.draw(d, t + 80, k=0.8)
    # 牛皮纸方块 tile（同启动图标）
    a0 = ease_out_back(seg(lt, 0.08, 0.5))
    if a0 > 0:
        ts = 176
        tl = Image.new("RGBA", (ts * S, ts * S), (0, 0, 0, 0))
        dtl = ImageDraw.Draw(tl)
        dtl.rounded_rectangle([0, 0, ts * S, ts * S], radius=42 * S, fill=hx(KRAFT_BG))
        draw_cube(dtl, (ts - 116) / 2, (ts - 116) / 2, 116, hx(KRAFT_LINE), 7.5, 1.0)
        sc = lerp(0.7, 1.0, min(1.0, a0))
        tl = tl.resize((int(ts * S * sc), int(ts * S * sc)))
        paste_over(ov, fade_layer(tl, min(1.0, a0)), 960 * S - tl.width / 2, 292 * S - tl.height / 2)
    a1 = seg(lt, 0.28, 0.75)
    f = F("song", 112)
    txt(d, (960 * S, (470 + (1 - ease_out_cubic(a1)) * 20) * S), "纸盒", f, hx(INK["ink"], a1), "mm")
    a2 = seg(lt, 0.42, 0.85)
    txt(d, (960 * S, (580 + (1 - ease_out_cubic(a2)) * 16) * S), "AI 操作手机.", F("sansb", 42),
        hx(INK["accent"], a2), "mm")
    a3 = seg(lt, 0.52, 0.95)
    txt(d, (960 * S, (638) * S), "v0.12.0 · 开源免费", F("sans", 28), hx(INK["ink3"], a3), "mm")
    # CTA pill + 扫光
    a4 = ease_out_back(seg(lt, 0.62, 1.05))
    pw, ph, py0 = 460, 92, 690
    if a4 > 0:
        px0 = 960 - pw / 2
        sh = ease_in_out(seg(lt, 1.25, 1.85))
        tl = Image.new("RGBA", (pw * S, ph * S), (0, 0, 0, 0))
        dtl = ImageDraw.Draw(tl)
        dtl.rounded_rectangle([0, 0, pw * S, ph * S], radius=ph / 2 * S, fill=hx(INK["accent"]))
        txt(dtl, (pw / 2 * S, ph / 2 * S), "立即下载体验", F("sansb", 36), hx(PAPER["surface"]), "mm")
        if sh > 0:
            band = Image.new("RGBA", tl.size, (0, 0, 0, 0))
            bx = -300 * S + (pw + 600) * S * sh
            ImageDraw.Draw(band).polygon(
                [(bx, 0), (bx + 130 * S, 0), (bx + 30 * S, ph * S), (bx - 100 * S, ph * S)],
                fill=(255, 255, 255, 74))
            tl.alpha_composite(Image.composite(band, Image.new("RGBA", tl.size, (0, 0, 0, 0)),
                                               tl.getchannel("A")))
        sc = lerp(0.85, 1.0, min(1.0, a4))
        tl2 = tl.resize((int(pw * S * sc), int(ph * S * sc)))
        paste_over(ov, fade_layer(tl2, min(1.0, a4)), 960 * S - tl2.width / 2,
                   (py0 + ph / 2) * S - tl2.height / 2)
    a5 = seg(lt, 0.8, 1.2)
    txt(d, (960 * S, 838 * S), "GitHub · panzih/AI-phone-use-bypanzhihe", F("lat", 25),
        hx(INK["ink2"], a5 * 0.95), "mm")
    base.alpha_composite(ov)


# ---------------------------------------------------------------- 逐帧
def render_frame(i):
    t = i / FPS
    if t < 2.4:
        base = BG_DARK.copy()
        scene_intro(base, t)
    elif t < 10.8:
        base = BG_PAPER.copy()
        scene_features(base, t)
    elif t < 12.6:
        base = BG_DARK.copy()
        scene_hero(base, t)
    else:
        base = BG_DARK.copy()
        scene_ending(base, t)
    # 转场闪白
    overlay_flash(base, t, 2.4)
    overlay_flash(base, t, 10.8, color="#EAF6F0")
    # 胶片噪点
    g = GRAINS[i % 2]
    dx, dy = random.randrange(CW), random.randrange(CH)
    shifted = np.roll(np.array(g), (dy, dx), axis=(0, 1))
    base.alpha_composite(Image.fromarray(shifted, "RGBA"))
    # 首尾黑场
    black_overlay(base, int(255 * (1 - seg(t, 0.0, 0.35))))
    black_overlay(base, int(255 * seg(t, 14.55, 15.0)))
    frame = base.convert("RGB").resize((W, H), Image.LANCZOS)
    frame.save(os.path.join(FRAMES_DIR, f"f{i:04d}.png"), compress_level=1)


# ---------------------------------------------------------------- 音频
def build_audio(path):
    sr = 44100
    n = int(sr * DUR)
    mix = np.zeros(n, np.float64)
    rng = np.random.default_rng(3)

    def add(sig, t0):
        i0 = int(t0 * sr)
        i1 = min(n, i0 + len(sig))
        if i0 < n:
            mix[i0:i1] += sig[: i1 - i0]

    def env_ar(nlen, att, rel):
        e = np.ones(nlen)
        na, nr = int(att * sr), int(rel * sr)
        na, nr = min(na, nlen), min(nr, nlen)
        if na > 0:
            e[:na] = np.linspace(0, 1, na)
        if nr > 0:
            e[-nr:] *= np.linspace(1, 0, nr)
        return e

    # —— 铺底和声 ——
    chords = [
        (0.0, 2.4, [110.0, 164.81, 220.0, 261.63]),
        (2.4, 5.2, [87.31, 130.81, 174.61, 220.0]),
        (5.2, 8.0, [130.81, 196.0, 261.63, 329.63]),
        (8.0, 10.8, [98.0, 146.83, 196.0, 246.94]),
        (10.8, 12.6, [110.0, 164.81, 220.0, 329.63]),
        (12.6, 15.0, [87.31, 130.81, 164.81, 220.0]),
    ]
    for t0, t1, freqs in chords:
        dur = t1 - t0
        nlen = int(dur * sr)
        e = env_ar(nlen, 0.7, 0.9)
        for k, fq in enumerate(freqs):
            amp = 0.055 if k else 0.04
            sig = np.sin(2 * np.pi * fq * np.arange(nlen) / sr)
            sig += 0.35 * np.sin(2 * np.pi * fq * 1.003 * np.arange(nlen) / sr)
            add(sig * e * amp, t0)
    # —— 底鼓 ——
    for k in range(12):
        tk = 2.4 + 0.75 * k
        nlen = int(0.32 * sr)
        tt = np.arange(nlen) / sr
        fq = 130 * np.exp(-tt * 9) + 42
        sig = np.sin(2 * np.pi * np.cumsum(fq) / sr) * np.exp(-tt * 11) * 0.5
        add(sig, tk)
    # —— 轻拍（off-beat hat）——
    for k in range(11):
        tk = 2.775 + 0.75 * k
        nlen = int(0.035 * sr)
        sig = rng.standard_normal(nlen) * np.linspace(1, 0, nlen) ** 2 * 0.05
        add(sig - np.convolve(sig, np.ones(24) / 24, mode="same") * 0.9, tk)
    # —— 转场 whoosh ——
    for t0 in (2.4, 5.2, 8.0, 10.8):
        nlen = int(0.85 * sr)
        tt = np.arange(nlen) / sr
        e = (tt / 0.85) ** 2.4
        sig = rng.standard_normal(nlen) * e * 0.16
        sig = np.convolve(sig, np.ones(10) / 10, mode="same")
        add(sig, t0 - 0.78)
    # —— 落点 boom ——
    for t0, amp in ((2.4, 0.42), (10.8, 0.5)):
        nlen = int(0.7 * sr)
        tt = np.arange(nlen) / sr
        add(np.sin(2 * np.pi * 54 * tt) * np.exp(-tt * 6) * amp, t0)
    # —— riser 进 hero ——
    nlen = int(0.9 * sr)
    tt = np.arange(nlen) / sr
    fq = 180 + (720 - 180) * (tt / 0.9) ** 2
    add(np.sin(2 * np.pi * np.cumsum(fq) / sr) * (tt / 0.9) ** 2 * 0.12, 9.9)
    # —— 卡片出现 tick ——
    for t0 in (2.65, 5.45, 8.25):
        nlen = int(0.06 * sr)
        tt = np.arange(nlen) / sr
        add(np.sin(2 * np.pi * 1250 * tt) * np.exp(-tt * 60) * 0.1, t0)
    # —— hero 大字 pop ——
    for t0, f0 in ((10.98, 660), (11.28, 880)):
        nlen = int(0.5 * sr)
        tt = np.arange(nlen) / sr
        add(np.sin(2 * np.pi * f0 * tt) * np.exp(-tt * 8) * 0.12, t0)
    # —— 结尾 chime ——
    for i, f0 in enumerate((523.25, 659.25, 783.99)):
        nlen = int(1.8 * sr)
        tt = np.arange(nlen) / sr
        add(np.sin(2 * np.pi * f0 * tt) * np.exp(-tt * 2.4) * 0.11, 12.72 + i * 0.07)
    # —— 母线 ——
    mix[: int(0.2 * sr)] *= np.linspace(0, 1, int(0.2 * sr))
    fo = int(1.1 * sr)
    mix[-fo:] *= np.linspace(1, 0, fo) ** 1.4
    mix = np.tanh(mix * 1.35) * 0.9
    peak = np.abs(mix).max()
    if peak > 0:
        mix = mix / peak * 0.92
    st = np.stack([mix, mix], axis=1)
    pcm = (st * 32767).astype(np.int16)
    with wave.open(path, "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(sr)
        wf.writeframes(pcm.tobytes())


def main():
    only = int(sys.argv[1]) if len(sys.argv) > 1 else NFRAMES
    for i in range(only):
        render_frame(i)
        if i % 60 == 0:
            print(f"frame {i}/{NFRAMES}", flush=True)
    print("frames done", flush=True)
    build_audio(os.path.join(HERE, "audio.wav"))
    print("audio done", flush=True)


if __name__ == "__main__":
    main()
