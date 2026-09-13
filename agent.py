"""agent.py — Agent 大脑：提示词、模型输出解析、主循环、防死循环。

核心循环（每一步）：
    截图 → 处理图像 → 构造提示词 → 问模型 → 解析 JSON → 校验 → 执行 → 存档

关于输出：
本模块不直接 print，而是通过 log 回调把消息交给调用方。
命令行版（main.py）把回调接到 print，图形界面版（macapp.py）接到窗口日志区。
这样同一套核心逻辑能被两种前端复用，不用改。
"""
import hashlib
import json
import os
import re
import time
from collections import deque
from typing import Any, Callable, Deque, Dict, List, Optional

from adb import AdbClient, AdbError
from config import Config
from image import PreparedImage, is_all_black, prepare, png_size
from llm import LlmClient, LlmError
from tools import ToolExecutor


SYSTEM_PROMPT_TEMPLATE = """你是一个安卓手机自动化助手。你通过查看手机屏幕截图来决定下一步操作。

# 屏幕信息
手机屏幕的完整分辨率是 **{width} x {height}** 像素。
- 左上角坐标是 (0, 0)，右下角是 ({width}, {height})
- 横坐标 x 范围 0 到 {width}，纵坐标 y 范围 0 到 {height}
- 截图可能按比例缩小后展示，但**你输出的坐标必须是上面这个完整分辨率下的真实像素值**
{grid_note}

# 你可以执行的动作
每次输出**一个** JSON 对象，包含 `thought`（你的判断）和 `action`（要执行的动作）。

1. 点击
   {{"thought": "说明为什么", "action": "tap", "x": 540, "y": 1200}}

2. 长按
   {{"thought": "说明为什么", "action": "long_press", "x": 540, "y": 1200, "duration_ms": 1000}}

3. 滑动（用方向更简单：up 表示手指向上划，内容往下滚）
   {{"thought": "说明为什么", "action": "swipe", "direction": "up"}}
   也可以用坐标：
   {{"thought": "说明为什么", "action": "swipe", "x1": 540, "y1": 1800, "x2": 540, "y2": 600, "duration_ms": 300}}

4. 输入文字（会输入到当前已聚焦的输入框，所以要先点击输入框）
   {{"thought": "说明为什么", "action": "input_text", "text": "要输入的内容"}}

5. 打开应用（需要包名，如微信 com.tencent.mm、设置 com.android.settings）
   {{"thought": "说明为什么", "action": "open_app", "package": "com.tencent.mm"}}

6. 发送按键
   {{"thought": "说明为什么", "action": "key", "key": "back"}}
   可选 key：back（返回）、home（回桌面）、enter（回车）、app_switch（多任务）

7. 等待页面加载
   {{"thought": "说明为什么", "action": "wait", "seconds": 2}}

8. 任务完成
   {{"thought": "说明为什么", "action": "finish", "summary": "一句话描述完成了什么"}}

9. 任务无法完成
   {{"thought": "说明为什么", "action": "fail", "reason": "说明卡在哪里"}}

# 重要规则
1. **只输出 JSON，不要输出任何其他文字、解释或 markdown 代码块标记。**
2. 每次只输出一个动作。执行后你会看到新的屏幕截图，再决定下一步。
3. 点击时**尽量点目标的中心位置**，不要点在元素边缘。
4. 如果画面显示的内容和你预期的不同，先想清楚现在在哪个界面，再决定动作。
5. **如果你连续几步看到完全相同的画面，说明前面的操作没生效**，
   必须换一种方式（改点别的位置、先滑动找找、或按返回键），不要重复同样的操作。
6. 找到目标并完成用户要求后，用 finish 结束，不要做多余的操作。
7. 如果反复尝试都无法推进，用 fail 说明原因，不要无限尝试。
"""


def _default_log(msg: str) -> None:
    print(msg)


def _never_stop() -> bool:
    return False


class Agent:
    """执行任务的主循环。

    参数说明：
        log_cb:   接收日志字符串的回调。GUI 传自己的方法，CLI 传 print。
        stop_cb:  每步开始前调用，返回 True 表示请求中止任务。
        step_cb:  每步结束时调用，签名 (step, total, record)，供 GUI 更新进度。
    """

    def __init__(
        self,
        cfg: Config,
        adb: AdbClient,
        llm: LlmClient,
        verbose: bool = True,
        log_cb: Optional[Callable[[str], None]] = None,
        stop_cb: Optional[Callable[[], bool]] = None,
        step_cb: Optional[Callable[[int, int, Dict[str, Any]], None]] = None,
        run_logger: Optional[Any] = None,
    ):
        self.cfg = cfg
        self.adb = adb
        self.llm = llm
        self.verbose = verbose
        self._log = log_cb or _default_log
        self._should_stop = stop_cb or _never_stop
        self._on_step = step_cb
        # 每次运行独立的日志器（含专属目录、文本日志、截图目录）。
        # 没有传入时退化成只打屏，方便单元测试。
        self.run_logger = run_logger

        self.screen_w, self.screen_h = adb.screen_size()
        self.tools = ToolExecutor(
            adb=adb,
            screen_w=self.screen_w,
            screen_h=self.screen_h,
            coordinate_mode=cfg.coordinate_mode,
            wait_after_action=float(cfg.wait_after_action),
            log_cb=self._log,
        )

        # 截图和报告的存放位置。
        # 有 RunLogger 就进它专属的目录；没有时（单元测试、嵌入式调用）
        # 也按时间戳隔离开，避免多次运行互相覆盖截图。
        if run_logger is not None:
            self.shot_dir = run_logger.shot_dir
            self._fallback_stamp = None
        else:
            self._fallback_stamp = time.strftime("%Y%m%d_%H%M%S")
            self.shot_dir = os.path.join(
                cfg.log_dir, "runs", "%s_run" % self._fallback_stamp,
                "screenshots",
            )
        os.makedirs(self.shot_dir, exist_ok=True)

        # 会话状态。
        # 注意：这里刻意不保存历史截图。虽然服务端单图能给到 1024 token、
        # 也支持多图，但每轮都带上历史截图会让每步的输入 token 成倍增长，
        # 而历史信息用纯文本摘要表达完全够用。每轮只发当前这一张。
        self.step_records: List[Dict[str, Any]] = []
        self.recent_actions: Deque[str] = deque(maxlen=5)
        self.prev_hash: Optional[str] = None
        self.stagnant = 0

    # ------------------------------------------------------------------
    # 系统提示词
    # ------------------------------------------------------------------
    def _system_prompt(self) -> str:
        grid_note = ""
        if self.cfg.draw_grid_overlay:
            n = int(self.cfg.grid_divisions)
            grid_note = (
                "- 截图上叠加了 %d x %d 的红色网格，网格交点旁的数字就是该处的真实像素坐标，\n"
                "  请用它来估算目标位置" % (n, n)
            )

        return SYSTEM_PROMPT_TEMPLATE.format(
            width=self.screen_w,
            height=self.screen_h,
            grid_note=grid_note,
        )

    # ------------------------------------------------------------------
    # 主循环
    # ------------------------------------------------------------------
    def run(self, task: str) -> bool:
        """执行任务。返回 True 表示成功完成，False 表示失败、超步数或被中止。"""
        log = self._log
        log("=" * 56)
        log("任务：%s" % task)
        log("屏幕：%d x %d 像素" % (self.screen_w, self.screen_h))
        log("模型：%s" % self.cfg.model)
        log("坐标模式：%s   最大步数：%d" % (self.cfg.coordinate_mode, self.cfg.max_steps))
        log("=" * 56)

        system_prompt = self._system_prompt()
        max_steps = int(self.cfg.max_steps)

        for step in range(1, max_steps + 1):
            if self._should_stop():
                log("")
                log("[中止] 用户停止了任务。")
                self._save_report(task, success=False, note="用户中止")
                return False

            result = self._step(step, max_steps, task, system_prompt)
            if result is not None:
                return result

        log("")
        log("[结束] 已达到最大步数 %d，任务未完成。" % max_steps)
        log("       如果任务本来就需要更多步骤，可以调大 config.json 里的 max_steps。")
        self._save_report(task, success=False, note="达到最大步数上限")
        return False

    def _step(
        self, step: int, max_steps: int, task: str, system_prompt: str
    ) -> Optional[bool]:
        """执行一步。返回 True/False 表示任务结束，None 表示继续。"""
        log = self._log
        log("")
        log("-" * 56)
        log("第 %d / %d 步" % (step, max_steps))
        log("-" * 56)

        # 1. 观察：截图
        try:
            shot = self.adb.screenshot_bytes()
        except AdbError as e:
            log("  [错误] 截图失败：%s" % e)
            return False

        if is_all_black(shot):
            log("  [警告] 截图几乎是全黑的。当前页面可能有 DRM 保护，系统不允许截屏。")

        img_hash = hashlib.sha256(shot).hexdigest()
        try:
            orig_w, orig_h = png_size(shot)
        except Exception:
            orig_w, orig_h = self.screen_w, self.screen_h

        if (orig_w, orig_h) != (self.screen_w, self.screen_h):
            # 折叠屏、旋转、分屏都会走到这里。以实际截图尺寸为准重新校准。
            log(
                "  [提示] 实际截图 %dx%d 与 wm size 报告的 %dx%d 不一致，"
                "已按实际截图尺寸校准坐标。"
                % (orig_w, orig_h, self.screen_w, self.screen_h)
            )
            self.screen_w, self.screen_h = orig_w, orig_h
            self.tools.w, self.tools.h = orig_w, orig_h

        # 2. 死循环检测：画面与上一步完全相同
        if self.prev_hash is not None and img_hash == self.prev_hash:
            self.stagnant += 1
            log("  [提示] 画面与上一步完全相同（连续 %d 次）" % self.stagnant)
        else:
            self.stagnant = 0

        # 3. 处理图像。
        #    服务端自己会把图归一到约 1300x1300，所以缩放只是为了省上传流量，
        #    不是功能必需。image_shrink=false 时直接发原图。
        img = prepare(
            shot,
            pixel_budget=int(self.cfg.image_pixel_budget),
            max_bytes=int(self.cfg.image_max_bytes),
            draw_grid=bool(self.cfg.draw_grid_overlay),
            grid_divisions=int(self.cfg.grid_divisions),
            shrink=bool(self.cfg.get("image_shrink", True)),
        )
        log("  截图：%s" % img.describe())

        if self.cfg.save_screenshots:
            path = self.shot_dir and os.path.join(
                self.shot_dir, "step_%02d.png" % step
            )
            if path:
                try:
                    with open(path, "wb") as f:
                        f.write(img.data)
                except OSError:
                    pass

        # 4. 构造这一轮的提示文本。
        #    关键：防死循环的警告必须在**发请求之前**注入，
        #    否则模型要到下一轮才看得到，起不到纠正作用。
        warnings = self._build_warnings()
        if warnings:
            log("  [干预] %s" % " / ".join(w.split("：")[0] for w in warnings))
        prompt = self._build_step_prompt(step, warnings)

        # 5. 问模型（带重试）
        raw = self._ask_model(system_prompt, prompt, img, step)
        if raw is None:
            return False

        # 6. 解析
        action = parse_action(raw)
        if action is None:
            log("  [错误] 无法从模型输出里解析出合法的动作 JSON。")
            log("         模型原始输出：%s" % _shorten(raw, 300))
            # 记一条失败记录，下一轮的提示里会带上这个错误，促使模型纠正格式
            rec = {
                "step": step,
                "screenshot": "screenshots/step_%02d.png" % step,
                "raw_model_output": raw,
                "action": {"action": "(解析失败)"},
                "action_signature": "(解析失败)",
                "executed_ok": False,
                "result": "模型输出不是合法 JSON，请只输出一个 JSON 对象",
                "stagnant": self.stagnant,
            }
            self.step_records.append(rec)
            self.prev_hash = img_hash
            if self._on_step:
                self._on_step(step, max_steps, rec)
            return None

        # 7. 展示模型的判断
        thought = action.get("thought") or ""
        if thought:
            log("  思考：%s" % thought)
        log("  动作：%s" % _describe_action(action))

        # 8. 执行
        result = self.tools.execute(action)
        if result.ok:
            log("  结果：%s" % result.message)
        else:
            log("  [执行失败] %s" % result.message)

        # 9. 存档
        sig = _action_signature(action)
        rec = {
            "step": step,
            "screenshot": "screenshots/step_%02d.png" % step,
            "raw_model_output": raw,
            "action": action,
            "action_signature": sig,
            "executed_ok": result.ok,
            "result": result.message,
            "stagnant": self.stagnant,
        }
        self.step_records.append(rec)
        if self._on_step:
            self._on_step(step, max_steps, rec)

        # 10. 结束判断
        if result.done:
            log("")
            log("=" * 56)
            if result.finished:
                log("✓ %s" % result.message)
            else:
                log("✗ %s" % result.message)
            log("=" * 56)
            self._save_report(task, success=result.finished, note=result.message)
            return result.finished

        # 11. 更新状态，供下一轮的死循环检测使用
        self.recent_actions.append(sig)
        self.prev_hash = img_hash
        return None

    # ------------------------------------------------------------------
    # 与模型交互
    # ------------------------------------------------------------------
    def _build_warnings(self) -> List[str]:
        """检查是否陷入死循环，返回需要强注入给模型的警告。

        这是纯视觉方案最容易翻车的地方：模型会反复点同一个无效位置。
        光靠 max_steps 兜底太晚 —— 必须在每一轮请求前就把警告塞进提示词。
        """
        warnings: List[str] = []

        # 1. 上一次的动作有没有重复
        if self.recent_actions:
            last = self.recent_actions[-1]
            count = list(self.recent_actions).count(last)
            if count >= int(self.cfg.repeat_threshold):
                warnings.append(
                    "重复动作警告：你已经把同一个操作（%s）执行了 %d 次，"
                    "但它没有产生效果。**必须换一种完全不同的方式**，"
                    "比如点击不同的位置、先滑动寻找目标、或按返回键回到上一页。"
                    % (last, count)
                )

        # 2. 画面是否静止
        if self.stagnant >= int(self.cfg.stagnant_threshold):
            warnings.append(
                "画面静止警告：屏幕画面已经连续 %d 次没有任何变化，"
                "说明你之前的操作全都没有生效。请重新仔细观察截图，"
                "先判断当前到底在哪个界面，再决定动作；不要继续尝试同样的做法。"
                % self.stagnant
            )

        # 3. 上一步执行失败
        if self.step_records and not self.step_records[-1].get("executed_ok"):
            warnings.append(
                "上一步执行失败警告：上一轮的操作没有成功执行（%s）。"
                "请调整参数后重试，或者改用其他方式达到目的。"
                % _shorten(str(self.step_records[-1].get("result", "")), 80)
            )

        return warnings

    def _build_step_prompt(self, step: int, warnings: Optional[List[str]] = None) -> str:
        """当前这一轮要发给模型的文本（图片会另外附加）。"""
        lines = []
        if step == 1:
            lines.append("这是手机当前屏幕。请决定第一步操作。")
        else:
            lines.append("这是执行上一步操作后的手机屏幕。请决定下一步操作。")

        if self.step_records:
            recent = self.step_records[-4:]
            lines.append("")
            lines.append("最近的操作记录：")
            for rec in recent:
                status = "成功" if rec["executed_ok"] else "失败"
                lines.append(
                    "  第%d步 %s → %s（%s）"
                    % (
                        rec["step"],
                        _describe_action(rec["action"]),
                        _shorten(rec["result"], 60),
                        status,
                    )
                )

        if warnings:
            lines.append("")
            for w in warnings:
                lines.append("⚠️ " + w)

        return "\n".join(lines)

    def _ask_model(
        self, system_prompt: str, prompt: str, img: PreparedImage, step: int
    ) -> Optional[str]:
        """调用模型，失败时重试一次。返回 None 表示放弃本任务。"""
        for attempt in (1, 2):
            try:
                return self.llm.chat_with_image(system_prompt, prompt, img)
            except LlmError as e:
                if attempt == 1:
                    self._log("  [重试] 模型调用失败，2 秒后重试一次 ...")
                    time.sleep(2)
                else:
                    self._log("")
                    self._log("[错误] 模型调用连续两次失败，任务中止。")
                    self._log(str(e))
        return None

    # ------------------------------------------------------------------
    # 存档
    # ------------------------------------------------------------------
    def _save_report(self, task: str, success: bool, note: str = "") -> None:
        """把整个任务的执行过程写成 JSON。

        注意：写到**本次运行自己的目录**里，绝不覆盖历史记录。
        之前的实现是固定写 logs/last_run.json，跑第二次就把第一次冲掉了，
        出问题想回看上一次根本没法查。
        """
        payload = {
            "task": task,
            "success": success,
            "note": note,
            "model": self.cfg.model,
            "screen": [self.screen_w, self.screen_h],
            "total_steps": len(self.step_records),
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
            "steps": self.step_records,
        }

        if self.run_logger is not None:
            path = self.run_logger.report_path
        else:
            # 没有日志器时的兜底：同样带时间戳，互不覆盖
            path = os.path.join(
                os.path.dirname(self.shot_dir),
                "report_%s.json" % (self._fallback_stamp or time.strftime("%Y%m%d_%H%M%S")),
            )

        try:
            from run_logger import write_report

            if write_report(path, payload):
                self._log("")
                self._log("本次执行的完整记录已保存：")
                self._log("  文字日志：%s" % (self.run_logger.path
                                             if self.run_logger else path))
                self._log("  结构化报告：%s" % path)
                if self.run_logger is not None:
                    self._log("  步骤截图：%s" % self.shot_dir)
            else:
                self._log("[警告] 无法写入报告文件：%s" % path)
        except Exception as e:
            self._log("[警告] 无法写入报告文件：%s" % e)


# ----------------------------------------------------------------------
# JSON 解析（模型输出不可信，必须防御性处理）
# ----------------------------------------------------------------------
_FENCE_RE = re.compile(r"```(?:json)?\s*(.*?)```", re.DOTALL | re.IGNORECASE)


def parse_action(raw: str) -> Optional[Dict[str, Any]]:
    """从模型输出里尽力提取动作 JSON。失败返回 None。

    模型实际会出的幺蛾子（都见过）：
      - 用 ```json 围栏包起来
      - 前后加一段解释文字
      - 输出多个 JSON 对象
      - 用单引号而不是双引号
    """
    if not raw:
        return None
    text = raw.strip()

    # 1. 优先取 markdown 代码块里的内容
    fence = _FENCE_RE.search(text)
    if fence:
        text = fence.group(1).strip()

    # 2. 直接解析试试
    obj = _try_json(text)
    if obj is not None:
        return obj

    # 3. 退而求其次：找第一个 { 到最后一个 } 之间的内容
    start = text.find("{")
    end = text.rfind("}")
    if start != -1 and end > start:
        obj = _try_json(text[start : end + 1])
        if obj is not None:
            return obj

    # 4. 可能是多个 JSON 对象，逐个尝试
    for match in re.finditer(r"\{[^{}]*\}", text, re.DOTALL):
        obj = _try_json(match.group(0))
        if obj is not None and "action" in obj:
            return obj

    return None


def _try_json(text: str) -> Optional[Dict[str, Any]]:
    try:
        obj = json.loads(text)
    except (json.JSONDecodeError, ValueError):
        return None
    if isinstance(obj, dict):
        return obj
    # 万一模型返回了 [{...}] 这种数组，取第一个
    if isinstance(obj, list) and obj and isinstance(obj[0], dict):
        return obj[0]
    return None


def _action_signature(action: Dict[str, Any]) -> str:
    """给动作生成一个用于重复检测的指纹（只看关键参数）。"""
    name = str(action.get("action", ""))
    parts = [name]
    for key in ("x", "y", "x1", "y1", "x2", "y2", "direction", "package", "key", "text"):
        if key in action:
            parts.append("%s=%s" % (key, action[key]))
    return "|".join(parts)


def _describe_action(action: Dict[str, Any]) -> str:
    """把动作翻译成人类可读的一行中文，用于日志。"""
    name = str(action.get("action", "?"))
    if name in ("tap", "long_press"):
        return "%s(%s, %s)" % (name, action.get("x", "?"), action.get("y", "?"))
    if name == "swipe":
        if action.get("direction"):
            return "swipe(%s)" % action["direction"]
        return "swipe(%s,%s→%s,%s)" % (
            action.get("x1", "?"), action.get("y1", "?"),
            action.get("x2", "?"), action.get("y2", "?"),
        )
    if name == "input_text":
        return "input_text(%s)" % _shorten(str(action.get("text", "")), 30)
    if name == "open_app":
        return "open_app(%s)" % action.get("package", "?")
    if name == "key":
        return "key(%s)" % action.get("key", "?")
    if name == "wait":
        return "wait(%ss)" % action.get("seconds", "?")
    if name == "finish":
        return "finish"
    if name == "fail":
        return "fail"
    return name


def _shorten(text: str, limit: int) -> str:
    text = str(text).replace("\n", " ")
    if len(text) <= limit:
        return text
    return text[: limit - 3] + "..."
