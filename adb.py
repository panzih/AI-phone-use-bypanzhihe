"""adb.py —— ADB 封装（纯 subprocess，零第三方依赖）。

设计约束（为将来移植到手机端做准备）：
本模块是唯一直接接触 ADB 的地方，且只向上层暴露语义化接口
（screenshot / tap / swipe / input_text / ui_dump ...）。
上层代码里不应出现任何 ADB 原始命令，这样将来把本文件整体替换成
Shizuku + 无障碍服务的双实现时，其他模块一行都不用改。
"""
import base64
import os
import platform
import re
import shutil
import subprocess
import sys
import time
from typing import List, Optional, Tuple


IS_WINDOWS = platform.system() == "Windows"


class AdbError(RuntimeError):
    """ADB 相关错误，消息为面向用户的中文提示。"""


# 安卓按键码表：上层用语义名，不接触数字
KEYCODES = {
    "back": 4,
    "home": 3,
    "enter": 66,
    "app_switch": 187,
    "menu": 82,
    "power": 26,
    "volume_up": 24,
    "volume_down": 25,
    "delete": 67,
    "tab": 61,
    "escape": 111,
}


def _no_window_kwargs() -> dict:
    """Windows 下隐藏黑框；每次调用 ADB 都弹控制台是完全没法用的。"""
    if IS_WINDOWS:
        return {"creationflags": getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)}
    return {}


def find_adb(explicit: str = "") -> str:
    """定位 adb 可执行文件，按可靠性从高到低依次尝试。

    这是新手第一大坑：装好了 Platform Tools 但 adb 不在 PATH 里。
    """
    # 1. config.json 显式指定
    if explicit:
        if os.path.isfile(explicit) or shutil.which(explicit):
            return explicit
        raise AdbError(
            "config.json 里指定的 adb_path 不存在：%s\n"
            "请填 adb 的完整路径，或留空让程序自动查找。" % explicit
        )

    # 2. 环境变量
    env_adb = os.environ.get("ADB_PATH", "").strip()
    if env_adb and (os.path.isfile(env_adb) or shutil.which(env_adb)):
        return env_adb

    # 3. 系统 PATH
    found = shutil.which("adb")
    if found:
        return found

    # 4. 各平台常见安装位置
    home = os.path.expanduser("~")
    candidates: List[str] = []
    if IS_WINDOWS:
        local = os.environ.get("LOCALAPPDATA", "")
        candidates += [
            os.path.join(local, "Android", "Sdk", "platform-tools", "adb.exe"),
            r"C:\Android\platform-tools\adb.exe",
            r"C:\platform-tools\adb.exe",
            r"C:\adb\adb.exe",
        ]
    else:
        candidates += [
            os.path.join(home, "Library", "Android", "sdk", "platform-tools", "adb"),
            "/opt/homebrew/bin/adb",
            "/usr/local/bin/adb",
            "/usr/bin/adb",
        ]
    for c in candidates:
        if c and os.path.isfile(c):
            return c

    raise AdbError(
        "找不到 adb 可执行文件。\n"
        "解决办法（二选一）：\n"
        "  1. 安装 Android Platform Tools：\n"
        "     macOS  : brew install --cask android-platform-tools\n"
        "              （或从 https://developer.android.com/tools/releases/platform-tools 下载解压）\n"
        "     Windows: 下载 zip 解压后，把 platform-tools 目录加入系统 PATH\n"
        "  2. 已装好但不在 PATH 里 → 在 config.json 的 adb_path 填入完整路径，例如：\n"
        "     macOS  : \"/Users/你的用户名/Library/Android/sdk/platform-tools/adb\"\n"
        "     Windows: \"C:\\platform-tools\\adb.exe\"\n"
        "装好后在终端执行 adb version 验证。"
    )


class AdbClient:
    """单个安卓设备的 ADB 客户端。"""

    def __init__(self, adb_path: str = "", serial: Optional[str] = None):
        self.adb = find_adb(adb_path)
        self.serial = serial
        self._ime_cache: Optional[str] = None

    # ------------------------------------------------------------------
    # 底层执行
    # ------------------------------------------------------------------
    def _base_cmd(self) -> List[str]:
        cmd = [self.adb]
        if self.serial:
            cmd += ["-s", self.serial]
        return cmd

    def _run(
        self,
        args: List[str],
        timeout: float = 20.0,
        binary: bool = False,
    ):
        """执行一条 adb 命令。

        binary=True 时返回 bytes（用于截图），否则返回解码后的 str。
        所有调用都带 timeout —— ADB 卡死是常态，没有超时的程序会永久挂起。
        """
        cmd = self._base_cmd() + args
        try:
            proc = subprocess.run(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout,
                **_no_window_kwargs()
            )
        except subprocess.TimeoutExpired:
            raise AdbError(
                "ADB 命令超时（%.0f 秒）：%s\n"
                "设备可能无响应。试试拔插数据线，或执行 adb kill-server 后重连。"
                % (timeout, " ".join(args))
            )
        except FileNotFoundError:
            raise AdbError("无法执行 adb，请检查 config.json 里的 adb_path 是否正确。")

        if proc.returncode != 0:
            err = proc.stderr.decode("utf-8", "replace").strip()
            # 部分命令（如 uiautomator dump）会往 stderr 写正常信息，这里只报真实失败
            if err and "error" in err.lower():
                raise AdbError("ADB 命令失败：%s\n%s" % (" ".join(args), err))

        if binary:
            return proc.stdout
        return proc.stdout.decode("utf-8", "replace")

    def shell(self, command: str, timeout: float = 20.0) -> str:
        """执行 adb shell 命令，返回 stdout 文本。"""
        return self._run(["shell", command], timeout=timeout)

    # ------------------------------------------------------------------
    # 设备管理
    # ------------------------------------------------------------------
    @staticmethod
    def list_devices(adb_path: str = "") -> List[dict]:
        """列出所有已连接设备。返回 [{serial, state, model}, ...]"""
        adb = find_adb(adb_path)
        try:
            proc = subprocess.run(
                [adb, "devices", "-l"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=20,
                **_no_window_kwargs()
            )
        except subprocess.TimeoutExpired:
            raise AdbError("adb devices 超时。请拔插数据线后重试。")

        out = proc.stdout.decode("utf-8", "replace")
        devices = []
        for line in out.splitlines():
            line = line.strip()
            if not line or line.startswith("List of devices"):
                continue
            parts = line.split()
            if len(parts) < 2:
                continue
            serial, state = parts[0], parts[1]
            model = ""
            for p in parts[2:]:
                if p.startswith("model:"):
                    model = p[len("model:"):]
            devices.append({"serial": serial, "state": state, "model": model})
        return devices

    # 常见模拟器的 adb TCP 端口。
    # 模拟器不是 USB 设备，`adb devices` 默认看不到它们，
    # 必须先 `adb connect 127.0.0.1:<端口>` 才会出现在列表里。
    EMULATOR_PORTS = [
        (5555, "通用 / Android Studio 模拟器"),
        (5554, "Android Studio 模拟器（备用端口）"),
        (5556, "多个模拟器时的第二个"),
        (5557, "多个模拟器时的第三个"),
        (62001, "夜神 / MuMu"),
        (62025, "夜神（多开）"),
        (21503, "MuMu 模拟器"),
        (7555, "Genymotion"),
    ]

    def connect_emulator(self, port: int = 5555, quiet: bool = False) -> Optional[str]:
        """连接一个 TCP 端口的模拟器。成功返回序列号，失败返回 None。"""
        addr = "127.0.0.1:%d" % port
        try:
            self._run(["connect", addr], timeout=10)
        except AdbError:
            return None
        # connect 命令即使失败也常常返回 0，所以要回查设备列表确认
        devices = self.list_devices(self.adb)
        for d in devices:
            if d["serial"] == addr and d["state"] == "device":
                if not quiet:
                    print("  已连接模拟器：%s" % addr)
                return addr
        return None

    def auto_connect_emulator(self, extra_ports: Optional[List[int]] = None) -> Optional[str]:
        """依次尝试常见模拟器端口。

        这是纯视觉方案的必需品：模拟器不接 USB，
        用户如果不知道要先 adb connect，会一直卡在「没有检测到设备」。
        """
        ports = list(extra_ports or [])
        for p, _desc in self.EMULATOR_PORTS:
            if p not in ports:
                ports.append(p)

        for p in ports:
            serial = self.connect_emulator(p, quiet=True)
            if serial:
                desc = dict(self.EMULATOR_PORTS).get(p, "自定义端口")
                print("  已连接模拟器 %s（%s）" % (serial, desc))
                return serial
        return None

    @staticmethod
    def list_avds(adb_path: str = "") -> List[str]:
        """列出本机已创建的 Android Studio 模拟器（AVD）。

        没有装模拟器时返回空列表，不报错。
        """
        exe = os.path.join(
            os.path.expanduser("~"), "Library", "Android", "sdk", "emulator", "emulator"
        )
        if not os.path.isfile(exe):
            return []
        try:
            proc = subprocess.run(
                [exe, "-list-avds"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15,
                **_no_window_kwargs()
            )
            return [x.strip() for x in proc.stdout.decode("utf-8", "replace").splitlines()
                    if x.strip()]
        except Exception:
            return []

    def connect(self, interactive: bool = True) -> str:
        """选定要操作的设备。多设备时提示用户选择。"""
        devices = self.list_devices(self.adb)

        # 没看到设备时，先试试是不是模拟器（模拟器必须先 adb connect）
        if not devices:
            self.auto_connect_emulator()
            devices = self.list_devices(self.adb)

        if not devices:
            raise AdbError(
                "没有检测到任何安卓设备。请依次检查：\n"
                "  1. 手机用数据线连上电脑（有些线只能充电，换一根）\n"
                "  2. 手机「设置 → 关于手机 → 版本号」连点 7 次，开启开发者模式\n"
                "  3. 「设置 → 开发者选项」里打开「USB 调试」\n"
                "  4. 手机上会弹「允许 USB 调试吗？」→ 勾选「一直允许」并确定\n"
                "  5. 如果手机下拉通知栏有「USB 用途」，选「文件传输 / MTP」而不是「仅充电」\n"
                "  6. 执行 adb kill-server 再 adb devices 试试\n"
                "  7. 无线调试：先用数据线连一次，执行 adb tcpip 5555，\n"
                "     拔线后执行 adb connect 手机IP:5555\n"
                "  8. 用模拟器的话：先确认模拟器已经启动，然后执行\n"
                "     adb connect 127.0.0.1:5555\n"
                "     （Android Studio 模拟器、夜神、MuMu、Genymotion 都走 TCP，\n"
                "      不会自动出现在 adb devices 里）"
            )

        # 过滤掉不可用设备
        ready = [d for d in devices if d["state"] == "device"]
        bad = [d for d in devices if d["state"] != "device"]

        if not ready:
            msgs = []
            for d in bad:
                if d["state"] == "unauthorized":
                    msgs.append(
                        "  %s：未授权。请看手机屏幕，弹出「允许 USB 调试吗？」时点允许；\n"
                        "    如果没弹窗，到「开发者选项」里点「撤销 USB 调试授权」再重新插线。" % d["serial"]
                    )
                elif d["state"] == "offline":
                    msgs.append(
                        "  %s：设备离线。执行 adb kill-server 后重新插拔数据线。" % d["serial"]
                    )
                else:
                    msgs.append("  %s：状态为 %s，不可用。" % (d["serial"], d["state"]))
            raise AdbError("检测到设备但均不可用：\n" + "\n".join(msgs))

        if self.serial:
            match = [d for d in ready if d["serial"] == self.serial]
            if not match:
                raise AdbError(
                    "config.json 指定的 device_serial=%s 不在已连接设备中。\n"
                    "当前可用：%s"
                    % (self.serial, ", ".join(d["serial"] for d in ready))
                )
        elif len(ready) == 1:
            self.serial = ready[0]["serial"]
        else:
            if not interactive or not sys.stdin.isatty():
                raise AdbError(
                    "检测到多台设备，请在 config.json 的 device_serial 里指定一台：\n"
                    + "\n".join(
                        "  %s  (%s)" % (d["serial"], d["model"] or "未知型号") for d in ready
                    )
                )
            print("检测到多台设备，请选择：")
            for i, d in enumerate(ready):
                print("  [%d] %s  %s" % (i + 1, d["serial"], d["model"] or "未知型号"))
            while True:
                raw = input("输入序号后回车: ").strip()
                if raw.isdigit() and 1 <= int(raw) <= len(ready):
                    self.serial = ready[int(raw) - 1]["serial"]
                    break
                print("输入无效，请重新输入。")

        return self.serial

    def device_info(self) -> str:
        """拿一个人类可读的设备标识，用于日志。"""
        model = self.shell("getprop ro.product.model").strip()
        version = self.shell("getprop ro.build.version.release").strip()
        return "%s (Android %s)" % (model or "未知型号", version or "?")

    # ------------------------------------------------------------------
    # 屏幕
    # ------------------------------------------------------------------
    def screen_size(self) -> Tuple[int, int]:
        """获取屏幕物理分辨率。

        注意：折叠屏、横屏时这里报的值可能与实际截图尺寸不一致，
        所以真实坐标换算请始终以 screenshot_size() 的结果为准。
        """
        out = self.shell("wm size")
        # 优先用 Override size（开发者选项里改过分辨率时才是真实生效值）
        override = re.search(r"Override size:\s*(\d+)x(\d+)", out)
        if override:
            return int(override.group(1)), int(override.group(2))
        physical = re.search(r"Physical size:\s*(\d+)x(\d+)", out)
        if physical:
            return int(physical.group(1)), int(physical.group(2))
        raise AdbError("无法解析屏幕分辨率，wm size 返回：%r" % out)

    def screenshot_bytes(self) -> bytes:
        """截屏并返回 PNG 二进制。

        必须用 exec-out：直接拿到原始二进制流。
        用 `adb shell screencap -p` 会经过 PTY，\\n 被转成 \\r\\n 从而损坏 PNG。
        """
        data = self._run(["exec-out", "screencap", "-p"], timeout=40, binary=True)
        if not data:
            raise AdbError(
                "截屏返回空数据。可能是：\n"
                "  1. 当前页面有 DRM 保护（部分视频/银行 App），系统禁止截屏\n"
                "  2. 设备刚唤醒，稍等 1 秒重试\n"
                "  3. 部分 ROM 需要先执行 adb shell screencap 测试权限"
            )
        return data

    # ------------------------------------------------------------------
    # 输入操作
    # ------------------------------------------------------------------
    def tap(self, x: int, y: int) -> None:
        self.shell("input tap %d %d" % (x, y))

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> None:
        self.shell("input swipe %d %d %d %d %d" % (x1, y1, x2, y2, duration_ms))

    def long_press(self, x: int, y: int, duration_ms: int = 1000) -> None:
        """长按 = 起点终点相同的 swipe。"""
        self.swipe(x, y, x, y, duration_ms)

    def key(self, name: str) -> None:
        """发送按键。name 用语义名（back/home/enter/app_switch）。"""
        key = name.strip().lower()
        code = KEYCODES.get(key)
        if code is None:
            if key.isdigit():
                code = int(key)
            else:
                raise AdbError(
                    "未知按键 %r。可用：%s" % (name, ", ".join(sorted(KEYCODES)))
                )
        self.shell("input keyevent %d" % code)

    # ------------------------------------------------------------------
    # 文本输入（中文必须走特殊方案）
    # ------------------------------------------------------------------
    def current_ime(self) -> str:
        if self._ime_cache is None:
            try:
                self._ime_cache = self.shell(
                    "settings get secure default_input_method"
                ).strip()
            except AdbError:
                self._ime_cache = ""
        return self._ime_cache

    def has_adb_keyboard(self) -> bool:
        """检测 ADBKeyboard 输入法是否已安装并启用。"""
        try:
            imes = self.shell("ime list -s -a")
            return "com.android.adbkeyboard" in imes
        except AdbError:
            return False

    def input_text(self, text: str) -> str:
        """输入文本，自动处理中文。返回实际使用的方法名，供日志展示。

        `adb shell input text` 只支持 ASCII，中文会静默失败，
        所以这里按 A → B → C 三种方案降级。
        """
        if not text:
            return "noop"

        # 纯 ASCII 且不含 shell 特殊字符 → 直接用 input text，最快
        if _is_ascii_safe(text):
            self.shell("input text %s" % _escape_for_input(text))
            return "input text (ASCII)"

        # 方案 A：ADBKeyboard（中文首选）
        if self.has_adb_keyboard():
            b64 = base64.b64encode(text.encode("utf-8")).decode("ascii")
            self.shell("am broadcast -a ADB_INPUT_B64 --es msg %s" % b64)
            return "ADBKeyboard"

        # 方案 B：剪贴板 + 粘贴
        try:
            b64 = base64.b64encode(text.encode("utf-8")).decode("ascii")
            self.shell("am broadcast -a clipper.set -e text %s" % b64)
            time.sleep(0.3)
            self.shell("input keyevent 279")  # KEYCODE_PASTE
            return "剪贴板粘贴"
        except AdbError:
            pass

        # 方案 C：兜底，明确报错而不是静默失败
        raise AdbError(
            "无法输入中文（文本中含非 ASCII 字符）。\n"
            "adb shell input text 只支持英文数字，输入中文需要装一个辅助输入法：\n"
            "  1. 搜索下载 ADBKeyboard.apk（开源，Sen-HTTP 作者）\n"
            "  2. 安装到手机：adb install ADBKeyboard.apk\n"
            "  3. 启用：adb shell ime enable com.android.adbkeyboard/.AdbIME\n"
            "  4. 切为当前输入法：adb shell ime set com.android.adbkeyboard/.AdbIME\n"
            "  5. 重新运行本程序\n"
            "或者：先用英文完成流程，中文部分手动输入。"
        )

    # ------------------------------------------------------------------
    # 应用管理
    # ------------------------------------------------------------------
    def open_app(self, package: str) -> None:
        """按包名启动应用。

        用 monkey 而不是 am start，因为不需要知道具体 Activity 名。
        """
        pkg = package.strip()
        out = self.shell(
            "monkey -p %s -c android.intent.category.LAUNCHER 1" % pkg, timeout=25
        )
        if "No activities found" in out or "monkey aborted" in out.lower():
            raise AdbError(
                "无法启动应用 %s：找不到可启动的界面。\n"
                "可能包名写错了。查看已安装应用的包名：\n"
                "  adb shell pm list packages | grep 关键词\n"
                "例如微信是 com.tencent.mm，设置是 com.android.settings。" % pkg
            )

    def force_stop(self, package: str) -> None:
        self.shell("am force-stop %s" % package.strip())

    def current_app(self) -> str:
        """返回当前前台应用的包名，失败时返回空串。"""
        try:
            out = self.shell("dumpsys window")
        except AdbError:
            return ""
        # 不同安卓版本字段名不一样，逐个尝试
        for pattern in (
            r"mCurrentFocus=Window\{[^}]*?\s([\w.]+)/",
            r"mFocusedApp=AppWindowToken\{[^}]*?\s([\w.]+)/",
            r"topResumedActivity.*?\s([\w.]+)/",
        ):
            m = re.search(pattern, out)
            if m:
                pkg = m.group(1)
                if pkg and pkg not in ("android", "com.android.systemui"):
                    return pkg
        return ""


def _is_ascii_safe(text: str) -> bool:
    """判断能否安全地直接走 input text。"""
    if not text.isascii():
        return False
    # input text 用 %s 表示空格，其他 shell 特殊字符需转义
    if any(c in text for c in "\"'\\$`&|;<>()[]{}*?!#~\n\r"):
        return False
    return True


def _escape_for_input(text: str) -> str:
    """input text 里空格要写成 %s。"""
    return text.replace(" ", "%s")
