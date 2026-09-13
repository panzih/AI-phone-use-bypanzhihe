"""run_logger.py — 日志落盘。

为什么需要单独一个模块：
图形界面的日志窗口是内存里的，应用一关就没了。
出问题时你需要的是「当时到底发生了什么」的完整记录，所以每一行
显示在窗口里的文字，都必须同时写进磁盘。

目录结构：

    ~/Library/Application Support/AI手机助手/logs/     （打包后）
    项目目录/logs/                                      （开发时）
    ├── runs/                       每次任务一个独立目录，永不覆盖
    │   ├── 20260912_143022_打开设置看WiFi/
    │   │   ├── run.log             完整文本日志（窗口里看到的全部文字）
    │   │   ├── report.json         结构化记录（每步动作 + 模型原始输出）
    │   │   └── screenshots/
    │   │       ├── step_01.png
    │   │       └── step_02.png
    │   └── 20260912_150311_发消息给文件传输助手/
    │       └── ...
    ├── diag/                       自检、截图等诊断输出
    │   ├── 20260912_141500_check.log
    │   └── 20260912_141730_shot.log
    └── latest -> runs/最新那个      （软链接，方便快速找到最近一次）

设计要点：
  - 每写一行立即 flush，程序崩溃或被强杀时日志不会丢
  - 日志文件同时是「窗口内容的镜像」，两边永远一致
  - 目录名带时间戳和任务摘要，一眼就能找到想要的那次
"""
import os
import re
import shutil
import time
from typing import List, Optional


class RunLogger:
    """一次运行的日志收集器。

    用法：
        logger = RunLogger(log_root, "打开设置", kind="run")
        logger.write("第一行")          # 同时落盘 + 攒进内存
        print(logger.text())            # 取全部内容
        logger.path                    # 日志文件路径
    """

    def __init__(self, log_root: str, label: str = "", kind: str = "run"):
        self.log_root = os.path.abspath(log_root)
        self.kind = kind
        self.started = time.time()
        self.lines: List[str] = []

        stamp = time.strftime("%Y%m%d_%H%M%S")
        safe_label = _safe_name(label)

        if kind == "run":
            base = os.path.join(self.log_root, "runs")
            dirname = "%s_%s" % (stamp, safe_label) if safe_label else stamp
            self.dir = _unique_dir(base, dirname)
        else:
            self.dir = os.path.join(self.log_root, "diag")

        os.makedirs(self.dir, exist_ok=True)

        if kind == "run":
            self.path = os.path.join(self.dir, "run.log")
            self.shot_dir = os.path.join(self.dir, "screenshots")
            os.makedirs(self.shot_dir, exist_ok=True)
            self.report_path = os.path.join(self.dir, "report.json")
            _update_latest_link(os.path.join(self.log_root, "runs"), self.dir)
        else:
            self.path = os.path.join(
                self.dir, "%s_%s.log" % (stamp, safe_label or kind)
            )
            self.shot_dir = self.dir
            self.report_path = os.path.join(
                self.dir, "%s_%s.json" % (stamp, safe_label or kind)
            )

        self._fh = open(self.path, "w", encoding="utf-8", buffering=1)
        self._closed = False
        self.write("=" * 64)
        self.write("  AI 手机助手 运行日志")
        self.write("  开始时间：%s" % time.strftime("%Y-%m-%d %H:%M:%S"))
        self.write("  日志文件：%s" % self.path)
        self.write("=" * 64)

    # ------------------------------------------------------------------
    def write(self, text: str) -> None:
        """写入一行。带时间戳落盘，同时保留原文供界面显示。

        每行立即 flush（buffering=1 + 显式 flush），
        这样即使程序被强杀，已经产生的日志也不会丢。
        """
        text = "" if text is None else str(text)
        self.lines.append(text)
        stamp = time.strftime("%H:%M:%S")
        try:
            self._fh.write("[%s] %s\n" % (stamp, text))
            self._fh.flush()
        except (OSError, ValueError):
            # 写盘失败不能让主流程崩掉，界面上的日志仍然可用
            pass

    def text(self) -> str:
        return "\n".join(self.lines)

    @property
    def elapsed(self) -> float:
        return time.time() - self.started

    def finish(self, note: str = "") -> None:
        """收尾：写结束信息并关闭文件句柄。"""
        if self._closed:
            return
        self.write("-" * 64)
        self.write("结束时间：%s" % time.strftime("%Y-%m-%d %H:%M:%S"))
        self.write("总耗时：%.1f 秒" % self.elapsed)
        if note:
            self.write("结果：%s" % note)
        self.write("日志已保存：%s" % self.path)
        self.close()

    def close(self) -> None:
        self._closed = True
        try:
            self._fh.close()
        except Exception:
            pass

    # ------------------------------------------------------------------
    def shot_path(self, step: int) -> str:
        """第 step 步的截图应该存到哪。"""
        return os.path.join(self.shot_dir, "step_%02d.png" % step)


def _safe_name(text: str, limit: int = 24) -> str:
    """把任务描述变成安全的目录名。

    去掉路径分隔符和特殊字符，中文保留（macOS 文件名支持中文）。
    """
    text = (text or "").strip()
    text = re.sub(r"[\s/\\:*?\"<>|\n\r\t]+", "", text)
    text = re.sub(r"[^\w\u4e00-\u9fff]", "", text)
    return text[:limit]


def _unique_dir(base: str, name: str) -> str:
    """防止同一秒内两次运行撞名。"""
    candidate = os.path.join(base, name)
    n = 2
    while os.path.exists(candidate):
        candidate = os.path.join(base, "%s-%d" % (name, n))
        n += 1
    return candidate


def _update_latest_link(runs_dir: str, target: str) -> None:
    """维护一个 latest 软链接指向最近一次运行，方便快速找到。

    软链接失败不影响任何功能（比如在不支持的文件系统上），所以静默忽略。
    """
    link = os.path.join(os.path.dirname(runs_dir), "latest")
    try:
        if os.path.islink(link) or os.path.exists(link):
            if os.path.islink(link):
                os.unlink(link)
            elif os.path.isdir(link):
                shutil.rmtree(link, ignore_errors=True)
            else:
                os.unlink(link)
        os.symlink(target, link)
    except OSError:
        pass


def write_report(path: str, payload: dict) -> bool:
    """把结构化报告写到指定路径（不覆盖上一次运行的文件）。"""
    import json

    try:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
        return True
    except OSError:
        return False


def describe_env(cfg, adb_serial: str = "") -> List[str]:
    """收集环境信息。

    出问题时这些是第一批要问的东西：Python 版本、有没有 Pillow、
    用的哪个 adb、什么模型、什么参数。写在日志开头，
    以后拿到日志文件就能直接判断，不用再问用户。
    """
    import platform
    import sys

    try:
        from image import HAS_PIL
    except Exception:
        HAS_PIL = False

    lines = [
        "-" * 64,
        "环境信息",
        "-" * 64,
        "  程序版本：0.1.0",
        "  操作系统：%s %s" % (platform.system(), platform.release()),
        "  Python  ：%s" % sys.version.split()[0],
        "  Pillow  ：%s" % ("已安装" if HAS_PIL else "未安装"),
        "  运行模式：%s" % ("打包应用" if _is_frozen() else "源码运行"),
    ]
    try:
        lines.append("  配置文件：%s" % os.path.abspath(cfg.path or ""))
    except Exception:
        pass
    lines += [
        "  模型    ：%s" % cfg.get("model"),
        "  接口地址：%s" % cfg.chat_url,
        "  温度    ：%s" % cfg.get("temperature"),
        "  图片精度：%s" % (cfg.get("detail") or "(默认)"),
        "  坐标模式：%s" % cfg.get("coordinate_mode"),
        "  最大步数：%s" % cfg.get("max_steps"),
        "  等待时间：%s 秒" % cfg.get("wait_after_action"),
        "  API Key ：%s" % _mask(cfg.api_key),
    ]
    if adb_serial:
        lines.append("  设备序列：%s" % adb_serial)
    lines.append("-" * 64)
    return lines


def _is_frozen() -> bool:
    try:
        from paths import is_frozen

        return is_frozen()
    except Exception:
        import sys

        return bool(getattr(sys, "frozen", False))


def _mask(key: str) -> str:
    """Key 只留头尾，避免日志文件泄露完整密钥。"""
    if not key:
        return "(未设置)"
    if len(key) <= 10:
        return key[:2] + "***"
    return key[:6] + "***" + key[-4:]
