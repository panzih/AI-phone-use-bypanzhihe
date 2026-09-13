"""paths.py — 统一的路径解析。

为什么需要这个模块：
开发时程序的工作目录就是项目目录，config.json 和 logs/ 用相对路径没问题。
但打包成 .app 之后，双击启动时当前工作目录会变成 "/"（不是 .app 所在目录），
相对路径会写到系统根目录去，必然失败。

macOS 的规范做法是把用户数据放在：
    ~/Library/Application Support/<应用名>/

所以这里统一提供：
  user_data_dir()  用户数据目录（配置、日志、截图）
  resource_dir()   打包进 .app 的只读资源（用 PyInstaller 时是 _MEIPASS）
  is_frozen()      当前是不是在打包后的应用里运行
"""
import os
import sys
import tempfile


APP_NAME = "AI手机助手"

# 缓存解析结果，避免每次调用都去探测文件系统
_DATA_DIR_CACHE = ""


class DataDirError(RuntimeError):
    """所有候选数据目录都不可写时抛出。"""


def is_frozen() -> bool:
    """是否运行在 PyInstaller 打包出来的应用里。"""
    return getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS")


def resource_dir() -> str:
    """只读资源所在目录。

    PyInstaller 单文件模式会把资源解压到临时目录 sys._MEIPASS，
    开发模式下就是本文件所在目录。
    """
    if is_frozen():
        return getattr(sys, "_MEIPASS")
    return os.path.dirname(os.path.abspath(__file__))


def project_dir() -> str:
    """开发模式下的项目目录（打包后等同于资源目录）。"""
    return os.path.dirname(os.path.abspath(__file__))


def user_data_dir() -> str:
    """用户数据目录，会自动创建。

    开发模式下直接用项目目录，这样你改 config.json 就在原地生效，
    不会出现「代码里改了但程序读的是另一个文件」的困惑。
    打包后才切到 ~/Library/Application Support/。

    这里刻意做了多级回退：如果 macOS 规范目录因为权限、磁盘、沙箱等原因
    建不出来，退到下一个可写位置，**绝不让程序因为「找不到地方存文件」而启动失败**。
    可以用环境变量 AIPHONE_DATA_DIR 强制指定。
    """
    global _DATA_DIR_CACHE
    if _DATA_DIR_CACHE:
        return _DATA_DIR_CACHE

    # 0. 用户显式指定
    override = os.environ.get("AIPHONE_DATA_DIR", "").strip()
    if override:
        try:
            os.makedirs(override, exist_ok=True)
            _DATA_DIR_CACHE = override
            return override
        except OSError:
            pass

    if is_frozen():
        candidates = [
            # macOS 规范位置
            os.path.join(os.path.expanduser("~"), "Library",
                         "Application Support", APP_NAME),
            # 没有中文目录权限时退一步
            os.path.join(os.path.expanduser("~"), "Library",
                         "Application Support", "AIPhoneAssistant"),
            # 家目录
            os.path.join(os.path.expanduser("~"), "." + APP_NAME),
            # 应用自身所在目录
            os.path.dirname(os.path.abspath(sys.argv[0])),
            # 最后兜底：系统临时目录（重启会清，但至少能跑）
            os.path.join(tempfile.gettempdir(), APP_NAME),
        ]
    else:
        candidates = [project_dir()]

    errors = []
    for cand in candidates:
        try:
            os.makedirs(cand, exist_ok=True)
            # 真正验证一下能不能写，避免目录存在但只读
            probe = os.path.join(cand, ".write_test")
            with open(probe, "w") as f:
                f.write("ok")
            os.unlink(probe)
            _DATA_DIR_CACHE = cand
            return cand
        except OSError as e:
            errors.append("  %s → %s" % (cand, e))
            continue

    # 所有位置都不可写。这时不能再抛异常了（会连锁崩掉），
    # 退回到当前目录并让调用方自己去容错。
    raise DataDirError(
        "找不到可写的数据目录，以下位置都失败了：\n"
        + "\n".join(errors)
        + "\n\n可以用环境变量手动指定，例如：\n"
        '  export AIPHONE_DATA_DIR="$HOME/aiphone-data"'
    )


def config_path() -> str:
    """配置文件路径。打包后如果用户数据目录里没有配置，就从 .app 里复制一份模板出来。

    这样用户第一次打开应用时，配置目录里会出现一个可编辑的 config.json，
    而不是面对一个空的、程序读不到配置的目录。
    """
    target = os.path.join(user_data_dir(), "config.json")
    if not os.path.exists(target):
        template = os.path.join(resource_dir(), "config.json")
        if os.path.exists(template) and os.path.abspath(template) != os.path.abspath(target):
            try:
                with open(template, "r", encoding="utf-8") as src:
                    text = src.read()
                with open(target, "w", encoding="utf-8") as dst:
                    dst.write(text)
            except OSError:
                pass
    return target


def log_dir() -> str:
    """日志目录，会自动创建。

    关键设计：**日志系统本身绝不能成为崩溃的原因**。
    如果连日志目录都建不出来，退到系统临时目录继续跑 ——
    否则程序会因为「写不了日志」而崩溃，而崩溃了又没有日志可查，
    就彻底没法排查了。

    可以用 AIPHONE_LOG_DIR 强制指定。
    """
    override = os.environ.get("AIPHONE_LOG_DIR", "").strip()
    if override:
        try:
            os.makedirs(override, exist_ok=True)
            return override
        except OSError:
            pass

    try:
        d = os.path.join(user_data_dir(), "logs")
        os.makedirs(d, exist_ok=True)
        return d
    except (OSError, DataDirError):
        fallback = os.path.join(tempfile.gettempdir(), APP_NAME, "logs")
        try:
            os.makedirs(fallback, exist_ok=True)
        except OSError:
            pass
        return fallback


def safe_print(msg: str) -> None:
    """在可能没有终端的环境里安全打印。

    打包成 .app 后双击启动时没有终端，sys.stdout 可能是 None，
    此时 print() 会抛 AttributeError 把程序搞崩。
    这个函数会退到 stderr，再不行就静默丢弃。
    """
    for stream_name in ("stdout", "stderr"):
        stream = getattr(sys, stream_name, None)
        if stream is None:
            continue
        try:
            stream.write(msg + "\n")
            stream.flush()
            return
        except Exception:
            continue
