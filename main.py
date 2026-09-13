"""main.py — 命令行入口。

用法：
    python3 main.py "打开设置，进入 WLAN，告诉我当前连接的是哪个 WiFi"
    python3 main.py --check              # 只做环境自检，不执行任务
    python3 main.py --list-devices       # 列出已连接设备
    python3 main.py --shot               # 截一张图保存下来看看效果
    python3 main.py -i                   # 交互模式，连续下任务
"""
import argparse
import os
import sys
import time

# 让脚本在任意工作目录下都能 import 同目录模块
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import paths  # noqa: E402
from adb import AdbClient, AdbError, find_adb  # noqa: E402
from agent import Agent  # noqa: E402
from config import load as load_config  # noqa: E402
from image import HAS_PIL, prepare  # noqa: E402
from llm import LlmClient, LlmError  # noqa: E402


def print_banner() -> None:
    print("=" * 62)
    print("  AI 手机助手 — 让大模型直接操作你的安卓手机")
    print("=" * 62)


def check_environment(cfg, skip_llm: bool = False) -> bool:
    """环境自检。这是最有价值的一步：把常见配置错误提前暴露出来。"""
    print("=" * 62)
    print("  AI 手机助手 — 环境自检")
    print("=" * 62)
    print("\n【1/4】检查 Python 环境")
    print("  Python 版本：%s" % sys.version.split()[0])
    if sys.version_info < (3, 8):
        print("  [错误] 需要 Python 3.8 或更高版本。")
        return False
    print("  Pillow（图像处理）：%s" % ("已安装" if HAS_PIL else "未安装（可选）"))
    if not HAS_PIL:
        print("         没有 Pillow 也能运行：macOS 会用系统自带的 sips 缩放截图。")
        print("         想要更好的压缩效果和网格标尺，可以执行：pip3 install Pillow")
    if os.path.exists(cfg.path):
        print("  配置文件：%s" % os.path.abspath(cfg.path))
    else:
        print("  [警告] 未找到 %s，将使用内置默认配置" % cfg.path)
    print("  日志目录：%s" % cfg.log_dir)

    print("\n【2/4】检查 ADB")
    try:
        adb_path = find_adb(cfg.adb_path)
        print("  adb 位置：%s" % adb_path)
    except AdbError as e:
        print("  [错误] %s" % e)
        return False

    try:
        devices = AdbClient.list_devices(cfg.adb_path)
    except AdbError as e:
        print("  [错误] %s" % e)
        return False

    if not devices:
        print("  [错误] 没有检测到任何安卓设备。")
        print("  请确认：数据线已连接、手机已开启「USB 调试」、")
        print("         手机上弹出的「允许 USB 调试吗？」已点允许。")
        print("  详细排查步骤请运行：python3 main.py --list-devices")
        return False

    for d in devices:
        mark = "✓" if d["state"] == "device" else "✗"
        print("  %s %s  %s  [%s]" % (mark, d["serial"], d["model"] or "未知型号", d["state"]))

    if not any(d["state"] == "device" for d in devices):
        print("  [错误] 设备都不可用，请看上面的状态说明。")
        return False

    print("\n【3/4】检查设备连接")
    try:
        adb = AdbClient(cfg.adb_path, cfg.device_serial)
        serial = adb.connect(interactive=False)
        info = adb.device_info()
        w, h = adb.screen_size()
        print("  已连接：%s" % serial)
        print("  设备型号：%s" % info)
        print("  屏幕分辨率：%d x %d" % (w, h))
    except AdbError as e:
        print("  [错误] %s" % e)
        return False

    print("\n  测试截屏 ...", end="")
    try:
        shot = adb.screenshot_bytes()
        img = prepare(shot, cfg.image_pixel_budget, cfg.image_max_bytes,
                      cfg.draw_grid_overlay, cfg.grid_divisions,
                      shrink=bool(cfg.get("image_shrink", True)))
        print(" 成功")
        print("         原始截图 %d KB → 发送给模型 %s" % (len(shot) // 1024, img.describe()))
        print("         注意：服务端还会自己把图归一到约 1300x1300，这是正常的")
    except AdbError as e:
        print(" 失败")
        print("  [错误] %s" % e)
        return False

    print("\n  测试中文输入能力 ...", end="")
    if adb.has_adb_keyboard():
        print(" 支持（已安装 ADBKeyboard）")
    else:
        print(" 受限")
        print("         未检测到 ADBKeyboard，中文输入会失败。")
        print("         英文数字输入不受影响。需要中文输入请参考 README 的「中文输入」一节。")

    if skip_llm:
        print("\n【4/4】跳过模型检查（--skip-llm）")
        print("\n" + "=" * 62)
        print("环境自检通过（未验证模型接口）")
        print("=" * 62)
        return True

    print("\n【4/4】检查模型接口")
    print("  接口地址：%s" % cfg.chat_url)
    print("  模型名称：%s" % cfg.model)
    print("  图片精度：%s" % (cfg.get("detail") or "(默认)"))
    print("  API Key ：%s" % cfg.masked_key())
    try:
        llm = LlmClient(cfg)
        result = llm.self_check()
    except LlmError as e:
        print("  [错误] %s" % e)
        return False

    if not (result["text_ok"] and result["image_ok"]):
        print("\n  [错误] 模型自检未通过。")
        if result["text_ok"] and not result["image_ok"]:
            print("  文本接口正常但图片接口失败 —— 说明当前模型不接受图片输入。")
            print("  请把 config.json 的 model 改成支持图片的模型，例如：")
            print("      deepseek-flash   （V4.1-Flash，原生多模态）")
            print("  注意 deepseek-v4-flash 和 deepseek-v4-pro 都是纯文本模型。")
        return False

    print("\n" + "=" * 62)
    print("✓ 全部检查通过，可以开始使用：")
    print('  python3 main.py "你的任务描述"')
    print("=" * 62)
    return True


def cmd_list_devices(cfg) -> int:
    """列出设备及排查提示。"""
    print("正在查找 adb ...")
    try:
        print("adb 位置：%s" % find_adb(cfg.adb_path))
    except AdbError as e:
        print(str(e))
        return 1

    print("\n正在查询已连接设备 ...")
    try:
        devices = AdbClient.list_devices(cfg.adb_path)
    except AdbError as e:
        print(str(e))
        return 1

    if not devices:
        print("\n没有检测到任何设备。请按顺序检查：")
        print("  1. 数据线是否插好（换一根线试试，有些线只能充电不能传数据）")
        print("  2. 手机是否已开启「开发者选项 → USB 调试」")
        print("  3. 手机屏幕上是否弹出「允许 USB 调试吗？」→ 勾选「一直允许」并确定")
        print("  4. 手机下拉通知栏，把「USB 用途」从「仅充电」改成「文件传输」")
        print("  5. 执行 adb kill-server 然后重新运行本命令")
        print("\n如果手机没有弹出授权窗口，到「开发者选项」里点「撤销 USB 调试授权」，")
        print("然后拔掉数据线重新插上，弹窗就会再次出现。")
        return 1

    print("\n检测到 %d 台设备：" % len(devices))
    for d in devices:
        state_cn = {
            "device": "可用",
            "unauthorized": "未授权（需要在手机上点允许）",
            "offline": "离线（需要重新插拔数据线）",
        }.get(d["state"], d["state"])
        print("  %s   %s   %s" % (d["serial"], d["model"] or "未知型号", state_cn))
    print("\n在 config.json 里把 device_serial 设成上面的某个序列号即可指定设备。")
    return 0


def cmd_screenshot(cfg, out_dir=None) -> int:
    """截一张图并保存，用于确认设备画面正常。

    out_dir 指定保存目录（诊断日志会把自己的目录传进来，
    这样截图和它的日志待在一起，好找）。
    """
    try:
        adb = AdbClient(cfg.adb_path, cfg.device_serial)
        adb.connect()
    except AdbError as e:
        print(str(e))
        return 1

    print("正在截图 ...")
    try:
        shot = adb.screenshot_bytes()
    except AdbError as e:
        print(str(e))
        return 1

    target = out_dir or cfg.log_dir
    os.makedirs(target, exist_ok=True)
    raw_path = os.path.join(target, "shot_raw.png")
    with open(raw_path, "wb") as f:
        f.write(shot)

    img = prepare(shot, cfg.image_pixel_budget, cfg.image_max_bytes,
                  cfg.draw_grid_overlay, cfg.grid_divisions,
                  shrink=bool(cfg.get("image_shrink", True)))
    sent_path = os.path.join(target, "shot_to_model.png")
    with open(sent_path, "wb") as f:
        f.write(img.data)

    print("\n原始截图：%s  (%d KB)" % (raw_path, len(shot) // 1024))
    print("发给模型的图：%s  (%s)" % (sent_path, img.describe()))
    print("\n打开这两张图对比一下，确认：")
    print("  1. 原始截图能看到手机画面（不是全黑）")
    print("  2. 发给模型的那张虽然变小了，但文字仍然能看清")
    return 0


def run_task(cfg, task: str) -> int:
    """执行一个任务。"""
    from run_logger import RunLogger, describe_env

    try:
        adb = AdbClient(cfg.adb_path, cfg.device_serial)
        serial = adb.connect()
    except AdbError as e:
        print("\n[错误] 连接设备失败：\n%s" % e)
        return 1

    if not cfg.api_key:
        print("\n[错误] 没有配置 API Key。请编辑 config.json 填写 api_key，")
        print("       或设置环境变量 DEEPSEEK_API_KEY。")
        return 1

    # 每次运行一个独立日志目录，历史记录不会被覆盖
    logger = RunLogger(cfg.log_dir, label=task, kind="run")

    def log(msg: str) -> None:
        logger.write(msg)   # 落盘
        print(msg)          # 同时打屏

    log("")
    for line in describe_env(cfg, adb_serial=serial):
        log(line)
    try:
        log("设备型号：%s" % adb.device_info())
    except AdbError:
        pass

    llm = LlmClient(cfg)
    try:
        agent = Agent(
            cfg, adb, llm,
            verbose=cfg.verbose,
            log_cb=log,
            run_logger=logger,
        )
    except AdbError as e:
        log("[错误] 初始化失败：%s" % e)
        logger.finish(note="初始化失败")
        return 1

    exit_code = 2
    try:
        ok = agent.run(task)
        exit_code = 0 if ok else 2
        logger.finish(note="任务成功" if ok else "任务未完成")
    except KeyboardInterrupt:
        log("")
        log("[中断] 用户手动停止（Ctrl+C）。")
        agent._save_report(task, success=False, note="用户中断")
        logger.finish(note="用户中断")
        exit_code = 130
    except LlmError as e:
        log("[错误] %s" % e)
        logger.finish(note="模型调用失败")
        exit_code = 1
    finally:
        logger.close()

    return exit_code


class _TeeLog:
    """把 print 的输出同时写到日志文件和终端。

    --check / --list-devices / --shot 三个命令原本只有 print，
    用户说「自检报错了」时我们手上没有任何可查的东西。
    套上这个之后，终端看到什么，日志文件里就有什么。
    """

    def __init__(self, logger, stream):
        self.logger = logger
        self.stream = stream
        self._buf = ""

    def write(self, text: str) -> None:
        if self.stream is not None:
            try:
                self.stream.write(text)
            except Exception:
                pass
        self._buf += text
        while "\n" in self._buf:
            line, self._buf = self._buf.split("\n", 1)
            self.logger.write(line)

    def flush(self) -> None:
        if self._buf:
            self.logger.write(self._buf)
            self._buf = ""
        if self.stream is not None:
            try:
                self.stream.flush()
            except Exception:
                pass


def _run_with_log(cfg, label: str, fn) -> int:
    """跑一个诊断类命令并落盘。返回退出码。

    fn 接收一个参数：本次诊断的输出目录（截图之类放这里，跟日志待一起）。
    """
    from run_logger import RunLogger, describe_env

    logger = RunLogger(cfg.log_dir, label=label, kind="diag")
    old_stdout = sys.stdout
    sys.stdout = _TeeLog(logger, old_stdout)  # type: ignore
    code = 1
    try:
        for line in describe_env(cfg):
            print(line)
        print("")
        code = fn(logger.dir)
    except Exception as e:
        print("[错误] %s: %s" % (type(e).__name__, e))
        code = 1
    finally:
        sys.stdout = old_stdout
        logger.finish(note="退出码 %d" % code)
        logger.close()
        print("")
        print("日志已保存：%s" % logger.path)
    return code


def main() -> int:
    parser = argparse.ArgumentParser(
        description="AI 手机助手 —— 用大模型操作安卓手机",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例：\n"
            '  python3 main.py "打开设置，看看当前连的是哪个 WiFi"\n'
            '  python3 main.py "打开微信，给文件传输助手发一条消息：你好"\n'
            "  python3 main.py --check\n"
            "  python3 macapp.py            # 打开图形界面\n"
        ),
    )
    parser.add_argument("task", nargs="*", help="要交给 AI 完成的任务，用自然语言描述")
    parser.add_argument("--check", action="store_true", help="环境自检（推荐第一次运行时用）")
    parser.add_argument("--skip-llm", action="store_true", help="自检时跳过模型接口检查")
    parser.add_argument("--list-devices", action="store_true", help="列出已连接设备")
    parser.add_argument("--shot", action="store_true", help="截一张图保存下来")
    parser.add_argument("--config", default=None, help="配置文件路径（默认自动定位）")
    parser.add_argument("-i", "--interactive", action="store_true", help="交互模式，连续下任务")
    args = parser.parse_args()

    # 默认配置文件位置由 paths 统一决定：
    # 开发时在项目目录，打包后在 ~/Library/Application Support/AI手机助手/
    config_file = args.config or paths.config_path()

    try:
        cfg = load_config(config_file)
    except Exception as e:
        print("[错误] 读取配置文件失败：%s" % e)
        return 1

    # 日志目录也统一走 paths，保证打包后不会写到系统根目录
    if not args.config:
        cfg._data["log_dir"] = paths.log_dir()

    if args.list_devices:
        return _run_with_log(cfg, "devices", lambda _d: cmd_list_devices(cfg))

    if args.check:
        return _run_with_log(
            cfg,
            "check",
            lambda _d: 0 if check_environment(cfg, skip_llm=args.skip_llm) else 1,
        )

    if args.shot:
        return _run_with_log(
            cfg, "shot", lambda d: cmd_screenshot(cfg, out_dir=d)
        )

    # 交互模式
    if args.interactive or not args.task:
        if not args.task:
            print_banner()
            print("\n交互模式。直接输入任务描述，输入 q 退出。\n")
        while True:
            try:
                task = input("任务> ").strip()
            except (EOFError, KeyboardInterrupt):
                print()
                break
            if not task:
                continue
            if task.lower() in ("q", "quit", "exit", "退出"):
                break
            run_task(cfg, task)
            print()
        return 0

    task = " ".join(args.task)
    return run_task(cfg, task)


if __name__ == "__main__":
    sys.exit(main())
