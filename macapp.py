"""macapp.py — macOS 图形界面版。

设计原则（按需求）：
  - 只用系统原生控件，不做任何美化
  - 配色只用黑白灰红四色
  - 布局极简：输入任务 → 开始 → 看日志

线程模型：
  tkinter 不是线程安全的，所有界面更新必须在主线程。
  所以任务跑在后台线程里，日志通过 queue 传回主线程，
  由主线程用 after() 定时取出并追加到文本框。

如果文件名是 macapp.py 却要在 Windows 上跑，界面一样能出来
（tkinter 是跨平台的），只是打包脚本不同。
"""
import os
import queue
import sys
import threading
import tkinter as tk
from tkinter import font as tkfont
from typing import Optional

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import paths  # noqa: E402

# ----------------------------------------------------------------------
# 配色：只有黑白灰红
# ----------------------------------------------------------------------
C_BG = "#ffffff"        # 白：窗口背景
C_FG = "#1a1a1a"        # 近黑：主文字
C_MUTED = "#777777"     # 灰：次要文字、边框
C_LIGHT = "#f2f2f2"     # 浅灰：输入框/日志区背景
C_RED = "#c62828"       # 红：停止按钮、错误

# 中文字号：用固定宽度的系统字体，保证中文和数字对齐好看
FONT_UI = ("Helvetica Neue", 13)
FONT_LOG = ("Menlo", 11)
FONT_SMALL = ("Helvetica Neue", 11)


class RedirectToLog:
    """把 print 的输出重定向到窗口日志区。

    adb.py / main.py 里有些地方仍然直接 print（比如环境自检），
    这个类让它们的内容也能显示在窗口里，而不是丢到看不见的 stdout。
    """

    def __init__(self, sink):
        self._sink = sink
        self._buf = ""

    def write(self, text: str) -> None:
        self._buf += text
        while "\n" in self._buf:
            line, self._buf = self._buf.split("\n", 1)
            if line.strip():
                self._sink(line)

    def flush(self) -> None:
        if self._buf.strip():
            self._sink(self._buf)
        self._buf = ""


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.cfg = None
        self.worker = None
        self.stop_flag = threading.Event()
        self.msg_queue: "queue.Queue[str]" = queue.Queue()
        # 当前正在写的日志器；None 表示没有任务在跑（这时日志只显示不落盘）
        self.current_logger = None
        # 最近一次运行的日志文件路径，供「打开本次日志」用
        self.last_log_dir = ""

        self._build_ui()
        self._pump_queue()
        self._load_config_async()

    # ------------------------------------------------------------------
    # 界面
    # ------------------------------------------------------------------
    def _build_ui(self) -> None:
        r = self.root
        r.title("AI 手机助手")
        r.configure(bg=C_BG)
        r.geometry("820x620")
        r.minsize(620, 420)

        # ---- 顶部：标题 + 状态 ----
        top = tk.Frame(r, bg=C_BG)
        top.pack(fill="x", padx=16, pady=(14, 0))

        tk.Label(
            top, text="AI 手机助手", bg=C_BG, fg=C_FG,
            font=("Helvetica Neue", 17),
        ).pack(side="left")

        self.status_var = tk.StringVar(value="正在初始化 ...")
        tk.Label(
            top, textvariable=self.status_var, bg=C_BG, fg=C_MUTED, font=FONT_SMALL
        ).pack(side="right")

        tk.Frame(r, bg=C_MUTED, height=1).pack(fill="x", padx=16, pady=(10, 0))

        # ---- 任务输入 ----
        mid = tk.Frame(r, bg=C_BG)
        mid.pack(fill="x", padx=16, pady=(12, 0))

        tk.Label(mid, text="任务", bg=C_BG, fg=C_FG, font=FONT_UI).pack(side="left")

        self.task_var = tk.StringVar()
        self.entry = tk.Entry(
            mid, textvariable=self.task_var, font=FONT_UI,
            bg=C_LIGHT, fg=C_FG, relief="flat", insertbackground=C_FG,
        )
        self.entry.pack(side="left", fill="x", expand=True, padx=10, ipady=6)
        self.entry.bind("<Return>", lambda _e: self.on_start())

        self.btn_start = tk.Button(
            mid, text="开始", command=self.on_start, font=FONT_UI,
            bg=C_FG, fg=C_BG, activebackground=C_MUTED, activeforeground=C_BG,
            relief="flat", padx=18, pady=4, cursor="hand2",
        )
        self.btn_start.pack(side="left")

        self.btn_stop = tk.Button(
            mid, text="停止", command=self.on_stop, font=FONT_UI,
            bg=C_RED, fg=C_BG, activebackground=C_MUTED, activeforeground=C_BG,
            relief="flat", padx=18, pady=4, cursor="hand2", state="disabled",
        )
        self.btn_stop.pack(side="left", padx=(8, 0))

        # ---- 工具按钮 ----
        tools = tk.Frame(r, bg=C_BG)
        tools.pack(fill="x", padx=16, pady=(8, 0))

        for text, cmd in (
            ("环境自检", self.on_check),
            ("设备列表", self.on_devices),
            ("存一张截图", self.on_shot),
            ("打开配置", self.on_open_config),
            ("打开本次日志", self.on_open_current_log),
            ("打开日志目录", self.on_open_logs),
            ("清空", self.on_clear),
        ):
            tk.Button(
                tools, text=text, command=cmd, font=FONT_SMALL,
                bg=C_BG, fg=C_FG, activebackground=C_LIGHT, activeforeground=C_FG,
                relief="solid", borderwidth=1, padx=10, pady=2, cursor="hand2",
            ).pack(side="left", padx=(0, 6))

        # ---- 日志区 ----
        log_wrap = tk.Frame(r, bg=C_BG)
        log_wrap.pack(fill="both", expand=True, padx=16, pady=(10, 6))

        self.log = tk.Text(
            log_wrap, font=FONT_LOG, bg=C_LIGHT, fg=C_FG,
            relief="flat", wrap="word", padx=10, pady=8,
            insertbackground=C_FG, state="disabled",
        )
        self.log.pack(side="left", fill="both", expand=True)

        scroll = tk.Scrollbar(log_wrap, command=self.log.yview, width=12)
        scroll.pack(side="right", fill="y")
        self.log.configure(yscrollcommand=scroll.set)

        # 日志里用红色表示错误/失败 —— 这是唯一允许的强调色
        self.log.tag_configure("error", foreground=C_RED)
        self.log.tag_configure("ok", foreground=C_FG)
        self.log.tag_configure("muted", foreground=C_MUTED)

        # ---- 底部提示 ----
        self.hint_var = tk.StringVar(
            value="提示：手机需开启 USB 调试并用数据线连接；首次使用请先点「环境自检」。"
        )
        tk.Label(
            r, textvariable=self.hint_var,
            bg=C_BG, fg=C_MUTED, font=FONT_SMALL, anchor="w",
            justify="left", wraplength=780,
        ).pack(fill="x", padx=16, pady=(0, 12))

    # ------------------------------------------------------------------
    # 日志队列
    # ------------------------------------------------------------------
    def log_line(self, text: str) -> None:
        """线程安全：同时写入日志文件、放进界面队列。

        这是全局唯一的日志出口：Agent 的回调、重定向过来的 print、
        工具函数的输出，全都汇到这里，所以界面和日志文件的内容永远一致。
        """
        logger = getattr(self, "current_logger", None)
        if logger is not None:
            logger.write(text)
        self.msg_queue.put(text)

    def _pump_queue(self) -> None:
        """主线程定时取出队列里的消息并处理。

        这是本文件里**唯一**允许操作界面的地方。
        tkinter 不是线程安全的：从后台线程调用 root.after() 或碰控件都会抛异常
        （实测会直接崩），所以后台线程只能往队列里放消息。
        """
        try:
            while True:
                text = self.msg_queue.get_nowait()
                if text.startswith("\x00STATUS\x00"):
                    payload = text[len("\x00STATUS\x00"):]
                    if "|" in payload:
                        status, busy = payload.split("|", 1)
                        self.status_var.set(status)
                        self._set_busy(busy == "1")
                    else:
                        self.status_var.set(payload)
                elif text.startswith("\x00LOG_DIR\x00"):
                    self.last_log_dir = text[len("\x00LOG_DIR\x00"):]
                    self.hint_var.set("本次日志：%s" % self.last_log_dir)
                else:
                    self._append(text)
        except queue.Empty:
            pass
        self.root.after(80, self._pump_queue)

    def _append(self, text: str) -> None:
        self.log.configure(state="normal")
        tag = "ok"
        low = text
        if ("[错误]" in low or "[失败]" in low or "失败" in low
                or "错误" in low or "✗" in low or "警告" in low):
            tag = "error"
        elif text.startswith("  ") or text.startswith("本次") or "提示" in text:
            tag = "muted"
        self.log.insert("end", text + "\n", tag)
        self.log.see("end")
        self.log.configure(state="disabled")

    def _set_status(self, text: str, busy: Optional[bool] = None) -> None:
        """线程安全地更新状态栏，可顺带更新按钮可用性。

        只能往队列里放消息，绝不能直接碰控件。
        """
        if busy is None:
            self.msg_queue.put("\x00STATUS\x00" + text)
        else:
            self.msg_queue.put("\x00STATUS\x00%s|%d" % (text, 1 if busy else 0))

    # ------------------------------------------------------------------
    # 状态管理
    # ------------------------------------------------------------------
    def _set_busy(self, busy: bool) -> None:
        self.btn_start.configure(state="disabled" if busy else "normal")
        self.btn_stop.configure(state="normal" if busy else "disabled")

    def _worker_wrapper(self, fn, logger=None):
        """统一的线程外壳：处理日志收尾、异常、按钮状态。

        注意：这里是后台线程，只能通过 _set_status 往队列里放消息，
        不能直接调用 _set_busy 或 root.after。
        """
        ok = True
        err = ""
        try:
            fn()
        except Exception as e:
            ok = False
            err = "%s: %s" % (type(e).__name__, e)
            self.log_line("")
            self.log_line("[错误] %s" % err)
        finally:
            self.stop_flag.clear()
            if logger is not None:
                logger.finish(note=("成功" if ok else "异常中止") + (": " + err if err else ""))
                logger.close()
                # 界面底部显示本次日志位置，状态栏显示完整路径
                self.msg_queue.put("\x00LOG_DIR\x00" + logger.path)
                self._set_status("日志已保存", busy=False)
            else:
                self._set_status("就绪", busy=False)

    # ------------------------------------------------------------------
    # 配置加载
    # ------------------------------------------------------------------
    def _load_config_async(self) -> None:
        """在后台线程加载配置，避免启动时界面卡住。"""

        def work():
            from config import load as load_config

            path = paths.config_path()
            self.log_line("配置文件：%s" % path)
            if paths.is_frozen():
                self.log_line("运行模式：已打包应用")
            try:
                self.cfg = load_config(path)
                self.log_line("模型：%s" % self.cfg.model)
                if not self.cfg.api_key:
                    self.log_line("[警告] 还没有配置 API Key。")
                    self.log_line("         点「打开配置」填写 api_key，或用环境变量 "
                                  "DEEPSEEK_API_KEY。")
                self.log_line("")
                self.log_line("准备就绪。在下方输入任务后按回车或点「开始」。")
            except Exception as e:
                self.log_line("[错误] 读取配置失败：%s" % e)
            self._set_status("就绪", busy=False)

        threading.Thread(target=work, daemon=True).start()

    # ------------------------------------------------------------------
    # 按钮动作
    # ------------------------------------------------------------------
    def on_start(self) -> None:
        task = self.task_var.get().strip()
        if not task:
            self.log_line("[提示] 请先输入任务描述。")
            return
        if self.cfg is None:
            self.log_line("[提示] 配置还没加载完，稍等一下。")
            return

        self.entry.delete(0, "end")
        self.stop_flag.clear()
        self._set_status("执行中 ...", busy=True)

        # 本次任务独立的日志器：文本日志 + 报告 + 截图都进它自己的目录
        from run_logger import RunLogger, describe_env

        logger = RunLogger(paths.log_dir(), label=task, kind="run")
        self.current_logger = logger

        self.log_line("")
        self.log_line("本次运行日志目录：")
        self.log_line("  %s" % logger.dir)
        self.log_line("")

        def work():
            from adb import AdbClient, AdbError
            from agent import Agent
            from llm import LlmClient

            if not self.cfg.api_key:
                self.log_line("[错误] 没有配置 API Key，无法调用模型。")
                self.log_line("       点「打开配置」填写 api_key，或设置环境变量 "
                              "DEEPSEEK_API_KEY 后重启应用。")
                return

            try:
                adb = AdbClient(self.cfg.adb_path, self.cfg.device_serial)
                serial = adb.connect(interactive=False)
            except AdbError as e:
                self.log_line("[错误] 连接设备失败：")
                for line in str(e).splitlines():
                    self.log_line("  " + line)
                # 连接失败也要记下环境信息，否则排查时没有上下文
                for line in describe_env(self.cfg):
                    self.log_line(line)
                return

            # 环境信息写在日志开头，以后拿到日志文件就知道当时的配置
            for line in describe_env(self.cfg, adb_serial=serial):
                self.log_line(line)
            self.log_line("设备型号：%s" % adb.device_info())

            llm = LlmClient(self.cfg)
            agent = Agent(
                self.cfg, adb, llm,
                log_cb=self.log_line,
                stop_cb=self.stop_flag.is_set,
                run_logger=logger,
            )
            agent.run(task)

        self.worker = threading.Thread(
            target=lambda: self._worker_wrapper(work, logger=logger), daemon=True
        )
        self.worker.start()

    def on_stop(self) -> None:
        self.stop_flag.set()
        self._set_status("正在停止 ...")
        self.log_line("[提示] 已请求停止，会在当前这一步结束后中止。")

    def on_check(self) -> None:
        self._run_diagnostic("check", "自检中 ...")

    def on_devices(self) -> None:
        self._run_diagnostic("devices", "查询设备中 ...")

    def on_shot(self) -> None:
        self._run_diagnostic("shot", "截图中 ...")

    def _run_diagnostic(self, kind: str, status: str) -> None:
        """跑一个诊断类操作（自检 / 列设备 / 截图）。

        这三个不涉及模型，输出主要给人看，但同样要落盘 ——
        否则用户说「自检报错了」，你手上什么信息都没有。
        """
        if self.cfg is None:
            return
        self._set_status(status, busy=True)

        from run_logger import RunLogger

        logger = RunLogger(paths.log_dir(), label=kind, kind="diag")
        self.current_logger = logger

        def work():
            import main as cli

            old = sys.stdout
            sys.stdout = RedirectToLog(self.log_line)  # type: ignore
            try:
                if kind == "check":
                    cli.check_environment(self.cfg, skip_llm=False)
                elif kind == "devices":
                    cli.cmd_list_devices(self.cfg)
                else:
                    # 截图存进这次诊断自己的目录，跟日志放一起好找
                    cli.cmd_screenshot(self.cfg, out_dir=logger.dir)
            finally:
                sys.stdout = old
                self.current_logger = None

        threading.Thread(
            target=lambda: self._worker_wrapper(work, logger=logger), daemon=True
        ).start()

    def on_clear(self) -> None:
        self.log.configure(state="normal")
        self.log.delete("1.0", "end")
        self.log.configure(state="disabled")

    def on_open_config(self) -> None:
        path = paths.config_path()
        if not os.path.exists(path):
            self.log_line("[错误] 找不到配置文件：%s" % path)
            return
        self._open(path)
        self.log_line("已打开配置文件：%s" % path)
        self.log_line("修改 api_key 后需要重启应用才生效。")

    def on_open_current_log(self) -> None:
        """打开最近这次运行的日志文件（用系统默认程序）。

        这是出问题时最常用的按钮：直接看到完整日志，不用去翻目录。
        """
        target = self.last_log_dir
        logger = getattr(self, "current_logger", None)
        if logger is not None and not getattr(logger, "_closed", False):
            target = logger.path
        if not target or not os.path.exists(target):
            self.log_line("[提示] 还没有日志文件。先跑一次任务或点「环境自检」。")
            return
        self._open(target)
        self.log_line("已打开日志文件：%s" % target)

    def on_open_logs(self) -> None:
        """打开日志总目录，能看到所有历史运行记录。"""
        d = paths.log_dir()
        self._open(d)
        self.log_line("已打开日志目录：%s" % d)
        runs = os.path.join(d, "runs")
        if os.path.isdir(runs):
            try:
                n = len([x for x in os.listdir(runs) if not x.startswith(".")])
                self.log_line("里面 runs/ 下有 %d 次历史运行记录，每次一个独立目录。" % n)
            except OSError:
                pass

    @staticmethod
    def _open(path: str) -> None:
        try:
            if sys.platform == "darwin":
                import subprocess
                subprocess.Popen(["open", path])
            elif sys.platform.startswith("win"):
                os.startfile(path)  # type: ignore
            else:
                import subprocess
                subprocess.Popen(["xdg-open", path])
        except Exception:
            pass


def _install_crash_handlers() -> str:
    """把异常和 stdout/stderr 都导到文件里，返回该文件路径。

    为什么必须这么做：.app 是窗口模式（console=False），双击启动时没有终端。
    如果程序在界面出来之前就崩了，你会看到「闪一下就没了」，
    而且拿不到任何错误信息，根本没法排查。

    装了这个之后，任何崩溃都会留下痕迹：
        ~/Library/Application Support/AI手机助手/logs/startup.log
    """
    import traceback

    # 崩溃处理器自己绝对不能崩 —— 否则程序启动即退出，还什么信息都不留。
    # 所以这里对「找一个能写日志的地方」这件事做了完整兜底。
    log_path = "<无法确定>"
    fh = None
    try:
        log_path = os.path.join(paths.log_dir(), "startup.log")
        fh = open(log_path, "a", encoding="utf-8", buffering=1)
    except Exception:
        try:
            import tempfile
            log_path = os.path.join(tempfile.gettempdir(), "aiphone_startup.log")
            fh = open(log_path, "a", encoding="utf-8", buffering=1)
        except Exception:
            fh = None

    if fh is None:
        # 连临时文件都写不了，只能放弃落盘，但至少不要崩
        return log_path

    fh.write("\n" + "=" * 60 + "\n")
    fh.write("启动时间：%s\n" % __import__("time").strftime("%Y-%m-%d %H:%M:%S"))
    fh.write("程序版本：0.1.0\n")
    try:
        fh.write("运行模式：%s\n" % ("打包应用" if paths.is_frozen() else "源码运行"))
    except Exception:
        pass
    fh.write("=" * 60 + "\n")

    class _Tee:
        def __init__(self, stream):
            self.stream = stream

        def write(self, text):
            try:
                fh.write(text)
            except Exception:
                pass
            if self.stream is not None:
                try:
                    self.stream.write(text)
                except Exception:
                    pass

        def flush(self):
            try:
                fh.flush()
            except Exception:
                pass
            if self.stream is not None:
                try:
                    self.stream.flush()
                except Exception:
                    pass

        def isatty(self):
            return False

    sys.stdout = _Tee(sys.stdout)  # type: ignore
    sys.stderr = _Tee(sys.stderr)  # type: ignore

    def _excepthook(exc_type, exc_value, exc_tb):
        fh.write("\n[未捕获异常]\n")
        traceback.print_exception(exc_type, exc_value, exc_tb, file=fh)
        fh.flush()
        traceback.print_exception(exc_type, exc_value, exc_tb)

    sys.excepthook = _excepthook

    # 后台线程里的异常默认只打屏，这里也记录下来
    def _thread_hook(args):
        fh.write("\n[后台线程异常] %s\n" % args.thread.name)
        traceback.print_exception(args.exc_type, args.exc_value,
                                  args.exc_traceback, file=fh)
        fh.flush()

    try:
        threading.excepthook = _thread_hook  # type: ignore
    except Exception:
        pass

    return log_path


def main() -> int:
    log_path = _install_crash_handlers()
    try:
        root = tk.Tk()
    except Exception:
        import traceback

        traceback.print_exc()
        print("\n[致命错误] 无法创建图形界面。")
        print("完整错误已写入：%s" % log_path)
        # 尝试用系统弹窗告知用户，失败就算了
        try:
            import subprocess
            subprocess.Popen([
                "osascript", "-e",
                'display alert "AI手机助手启动失败" message '
                '"无法创建图形界面。详情见日志：\\n%s"' % log_path,
            ])
        except Exception:
            pass
        return 1

    # 尽量用系统默认外观，不额外美化
    try:
        root.tk.call("tk", "scaling", 1.0)
    except Exception:
        pass

    try:
        App(root)
        root.mainloop()
    except Exception:
        import traceback

        traceback.print_exc()
        print("\n[致命错误] 程序异常退出，详情见：%s" % log_path)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
