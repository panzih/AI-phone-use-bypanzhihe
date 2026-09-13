"""tools.py — 动作执行器。

职责：把模型输出的动作字典翻译成具体的 ADB 调用。
本模块是模型与设备之间唯一的执行入口，所有动作都经过参数归一化与边界保护。

注意：当前版本按需求未加入安全拦截（危险动作二次确认等）。
将来要加时，集中在本模块的 execute() 里加钩子即可，不影响其他模块。
"""
import time
from typing import Any, Callable, Dict, Optional, Tuple

from adb import AdbClient, AdbError


# 允许执行的动作白名单。
# 不在这里的动作一律拒绝执行 —— 防止模型编造动作导致意外行为。
VALID_ACTIONS = {
    "tap",
    "swipe",
    "long_press",
    "input_text",
    "open_app",
    "key",
    "wait",
    "screenshot",  # 让模型能主动"再看一眼"
    "finish",
    "fail",
}

# 语义方向 → (起点比例, 终点比例)，比例相对屏幕宽高
SWIPE_DIRECTIONS = {
    "up":    ((0.5, 0.75), (0.5, 0.25)),   # 手指上滑 = 内容向下滚
    "down":  ((0.5, 0.25), (0.5, 0.75)),
    "left":  ((0.75, 0.5), (0.25, 0.5)),
    "right": ((0.25, 0.5), (0.75, 0.5)),
}


class ActionResult:
    def __init__(self, ok: bool, message: str, done: bool = False, finished: bool = False):
        self.ok = ok
        self.message = message
        self.done = done           # 任务是否结束（finish / fail）
        self.finished = finished   # 是否成功完成（区分 finish 和 fail）

    def __repr__(self) -> str:
        return "ActionResult(ok=%s, msg=%s)" % (self.ok, self.message)


class ToolExecutor:
    def __init__(
        self,
        adb: AdbClient,
        screen_w: int,
        screen_h: int,
        coordinate_mode: str = "pixel",
        wait_after_action: float = 1.5,
        log_cb: Optional[Callable[[str], None]] = None,
    ):
        self.adb = adb
        self.w = screen_w
        self.h = screen_h
        self.coordinate_mode = coordinate_mode
        self.wait_after_action = wait_after_action
        self._log = log_cb or (lambda msg: None)

    # ------------------------------------------------------------------
    # 坐标归一化
    # ------------------------------------------------------------------
    def to_pixels(self, x: Any, y: Any) -> Tuple[int, int]:
        """把模型给的坐标换算成真实物理像素，并做边界保护。

        - coordinate_mode = "pixel"      : 模型直接给像素值
        - coordinate_mode = "normalized" : 模型给 0-1000 的千分比

        无论哪种模式，最终都要 clamp 到屏幕范围内，
        否则 input tap 会因为越界直接报错。
        """
        try:
            fx = float(x)
            fy = float(y)
        except (TypeError, ValueError):
            raise AdbError("坐标不是数字：x=%r, y=%r" % (x, y))

        if self.coordinate_mode == "normalized":
            px = fx / 1000.0 * self.w
            py = fy / 1000.0 * self.h
        else:
            px, py = fx, fy

        px = int(round(px))
        py = int(round(py))
        # 边界保护：留 1 像素余量，避免点到屏幕外
        px = max(0, min(self.w - 1, px))
        py = max(0, min(self.h - 1, py))
        return px, py

    def _duration(self, raw: Any, default: int) -> int:
        """时长参数统一处理：限制在 100ms - 10s，防止模型给出天文数字。"""
        try:
            v = int(float(raw))
        except (TypeError, ValueError):
            return default
        return max(100, min(10000, v))

    # ------------------------------------------------------------------
    # 动作执行
    # ------------------------------------------------------------------
    def execute(self, action: Dict[str, Any]) -> ActionResult:
        """执行一个动作。异常在这里被捕获并转成失败结果，不向上抛。"""
        name = str(action.get("action", "")).strip().lower()

        if name not in VALID_ACTIONS:
            return ActionResult(
                False,
                "未知动作 %r。可用动作：%s" % (name, ", ".join(sorted(VALID_ACTIONS))),
            )

        # 预留：将来在这里加安全拦截（危险动作二次确认）
        handler = getattr(self, "_do_%s" % name, None)
        if handler is None:
            return ActionResult(False, "动作 %s 尚未实现" % name)

        try:
            result = handler(action)
        except AdbError as e:
            return ActionResult(False, "执行失败：%s" % e)
        except Exception as e:  # 兜底，避免单步异常打断整个任务
            return ActionResult(False, "执行异常：%s: %s" % (type(e).__name__, e))

        # 动作后等待界面加载（finish/fail 不需要）
        if result.ok and not result.done and self.wait_after_action > 0:
            time.sleep(self.wait_after_action)
        return result

    # -- 各动作实现 -----------------------------------------------------
    def _do_tap(self, a: Dict[str, Any]) -> ActionResult:
        if "x" not in a or "y" not in a:
            return ActionResult(False, "tap 缺少 x / y 参数")
        x, y = self.to_pixels(a["x"], a["y"])
        self.adb.tap(x, y)
        return ActionResult(True, "已点击 (%d, %d)" % (x, y))

    def _do_long_press(self, a: Dict[str, Any]) -> ActionResult:
        if "x" not in a or "y" not in a:
            return ActionResult(False, "long_press 缺少 x / y 参数")
        x, y = self.to_pixels(a["x"], a["y"])
        ms = self._duration(a.get("duration_ms"), 1000)
        self.adb.long_press(x, y, ms)
        return ActionResult(True, "已长按 (%d, %d) %d 毫秒" % (x, y, ms))

    def _do_swipe(self, a: Dict[str, Any]) -> ActionResult:
        ms = self._duration(a.get("duration_ms"), 300)

        # 优先用方向式（模型更容易用对）
        direction = str(a.get("direction", "")).strip().lower()
        if direction:
            if direction not in SWIPE_DIRECTIONS:
                return ActionResult(
                    False,
                    "未知方向 %r。可用：%s" % (direction, ", ".join(SWIPE_DIRECTIONS)),
                )
            (rx1, ry1), (rx2, ry2) = SWIPE_DIRECTIONS[direction]
            x1, y1 = int(rx1 * self.w), int(ry1 * self.h)
            x2, y2 = int(rx2 * self.w), int(ry2 * self.h)
            self.adb.swipe(x1, y1, x2, y2, ms)
            return ActionResult(
                True, "已向 %s 滑动（%d,%d → %d,%d，%d 毫秒）" % (direction, x1, y1, x2, y2, ms)
            )

        # 坐标式
        need = ("x1", "y1", "x2", "y2")
        if not all(k in a for k in need):
            return ActionResult(
                False, "swipe 需要 direction 参数，或完整的 x1/y1/x2/y2 四个坐标"
            )
        x1, y1 = self.to_pixels(a["x1"], a["y1"])
        x2, y2 = self.to_pixels(a["x2"], a["y2"])
        self.adb.swipe(x1, y1, x2, y2, ms)
        return ActionResult(True, "已滑动 (%d,%d → %d,%d，%d 毫秒)" % (x1, y1, x2, y2, ms))

    def _do_input_text(self, a: Dict[str, Any]) -> ActionResult:
        text = a.get("text")
        if text is None:
            return ActionResult(False, "input_text 缺少 text 参数")
        text = str(text)
        if not text:
            return ActionResult(False, "input_text 的 text 为空")
        method = self.adb.input_text(text)
        return ActionResult(True, "已用「%s」输入文本：%s" % (method, text))

    def _do_open_app(self, a: Dict[str, Any]) -> ActionResult:
        pkg = a.get("package")
        if not pkg:
            return ActionResult(False, "open_app 缺少 package 参数（需要应用的包名）")
        self.adb.open_app(str(pkg))
        return ActionResult(True, "已启动应用 %s" % pkg)

    def _do_key(self, a: Dict[str, Any]) -> ActionResult:
        key = a.get("key")
        if not key:
            return ActionResult(False, "key 缺少 key 参数（back/home/enter/app_switch）")
        self.adb.key(str(key))
        return ActionResult(True, "已发送按键 %s" % key)

    def _do_wait(self, a: Dict[str, Any]) -> ActionResult:
        try:
            secs = float(a.get("seconds", 1))
        except (TypeError, ValueError):
            secs = 1.0
        secs = max(0.2, min(10.0, secs))
        time.sleep(secs)
        # 等待动作自己已经 sleep 过了，这里直接返回成功
        return ActionResult(True, "已等待 %.1f 秒" % secs)

    def _do_screenshot(self, a: Dict[str, Any]) -> ActionResult:
        # 主循环下一轮本来就会重新截图，这里只需确认一下
        return ActionResult(True, "已刷新屏幕")

    def _do_finish(self, a: Dict[str, Any]) -> ActionResult:
        summary = str(a.get("summary") or a.get("message") or "任务完成")
        return ActionResult(True, "任务完成：%s" % summary, done=True, finished=True)

    def _do_fail(self, a: Dict[str, Any]) -> ActionResult:
        reason = str(a.get("reason") or a.get("message") or "无法完成")
        return ActionResult(True, "任务失败：%s" % reason, done=True, finished=False)
