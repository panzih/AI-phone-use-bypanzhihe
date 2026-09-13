#!/usr/bin/env python3
"""selftest.py — 离线自测：不需要真机和 API Key，验证框架自身逻辑是否正确。

用途：改完代码后跑一遍，确认没有把主循环弄坏。

    python3 selftest.py

它用一个假的 ADB 和一个假的 OpenAI 兼容服务端，端到端跑完整个主循环，
覆盖：图像处理、坐标换算、JSON 解析、动作执行、结束语义、防死循环。
"""
import json
import os
import struct
import sys
import threading
import zlib
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import adb as adbmod  # noqa: E402
import config as cfgmod  # noqa: E402
import llm as llmmod  # noqa: E402
from agent import Agent, parse_action  # noqa: E402
from image import compute_scale, png_size, prepare  # noqa: E402
from tools import ToolExecutor  # noqa: E402

W, H = 1080, 2400
PASS, FAIL = [], []


def check(name: str, cond: bool, extra: str = "") -> None:
    (PASS if cond else FAIL).append(name)
    print("  [%s] %s%s" % ("通过" if cond else "失败", name, ("  " + extra) if extra else ""))


def make_png(seed: int = 0, w: int = W, h: int = H) -> bytes:
    """造一张假的手机截图（纯手写 PNG，不依赖 Pillow）。"""
    raw = b"".join(
        b"\x00" + bytes([((x + seed * 13) * 5) % 256 for x in range(w) for _ in range(3)])
        for _y in range(h)
    )

    def chunk(tag: bytes, data: bytes) -> bytes:
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 1))
        + chunk(b"IEND", b"")
    )


def make_small_png(w: int, h: int) -> bytes:
    return make_png(0, w, h)


def _find_detail(messages) -> str:
    """从消息里取出 detail 参数，用于验证它确实被发出去了。"""
    for m in messages:
        if isinstance(m.get("content"), list):
            for part in m["content"]:
                if part.get("type") == "image_url":
                    return part.get("image_url", {}).get("detail", "")
    return ""


def make_server(script, frozen: bool = False):
    """起一个假的 OpenAI 兼容服务端。

    script: 依次返回的动作列表（按带图请求的顺序）。
    """
    state = {"seen_prompts": [], "requests": []}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_POST(self):
            n = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(n))
            has_img = any(
                isinstance(m.get("content"), list)
                and any(p.get("type") == "image_url" for p in m["content"])
                for m in body["messages"]
            )
            state["requests"].append(
                {
                    "model": body.get("model"),
                    "has_image": has_img,
                    "detail": _find_detail(body["messages"]),
                    "temperature": body.get("temperature"),
                    "response_format": body.get("response_format"),
                    "messages": len(body["messages"]),
                }
            )
            for m in body["messages"]:
                if m["role"] == "user" and isinstance(m["content"], list):
                    state["seen_prompts"].append(m["content"][0]["text"])

            if has_img:
                idx = sum(1 for r in state["requests"] if r["has_image"]) - 1
                action = script[min(idx, len(script) - 1)]
                content = json.dumps(action, ensure_ascii=False)
            else:
                content = "OK"

            payload = json.dumps(
                {"choices": [{"message": {"content": content}, "finish_reason": "stop"}]}
            ).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    srv = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, srv.server_address[1], state


class FakeAdb(adbmod.AdbClient):
    """假 ADB。frozen=True 时每步返回完全相同的画面，用于测死循环检测。"""

    def __init__(self, frozen: bool = False):
        self.adb = "fake"
        self.serial = "FAKE"
        self._ime_cache = None
        self.frozen = frozen
        self.n = 0
        self.actions = []

    def screenshot_bytes(self):
        self.n += 1
        return make_png(0 if self.frozen else self.n)

    def screen_size(self):
        return (W, H)

    def tap(self, x, y):
        self.actions.append(("tap", x, y))

    def swipe(self, a, b, c, d, ms):
        self.actions.append(("swipe", a, b, c, d, ms))

    def long_press(self, x, y, ms):
        self.actions.append(("long_press", x, y, ms))

    def input_text(self, t):
        self.actions.append(("input_text", t))
        return "input text (ASCII)"

    def open_app(self, p):
        self.actions.append(("open_app", p))

    def key(self, k):
        self.actions.append(("key", k))


def section(title: str) -> None:
    print("\n" + "=" * 62)
    print(title)
    print("=" * 62)


def test_image() -> None:
    section("1. 图像处理")
    png = make_png()
    w, h = png_size(png)
    check("解析 PNG 尺寸", (w, h) == (W, H), "得到 %dx%d" % (w, h))
    check("缩放比例计算", abs(compute_scale(W, H, 640000) - 0.4969) < 0.001)

    # 服务端会自己归一到约 1300x1300，客户端缩放只是省流量
    img = prepare(png, 1690000, 4194304, draw_grid=False, shrink=True)
    check("缩放到服务端目标尺寸内", img.sent_w * img.sent_h <= 1690000,
          "%dx%d = %d 像素" % (img.sent_w, img.sent_h, img.sent_w * img.sent_h))

    raw = prepare(png, 1690000, 4194304, draw_grid=False, shrink=False)
    check("image_shrink=false 时原样发送", (raw.sent_w, raw.sent_h) == (W, H),
          "%dx%d" % (raw.sent_w, raw.sent_h))

    # 1080x2400 = 2.59M 像素，确实超过 1.69M 预算，所以会缩一点
    check("1080x2400 会被缩到预算内", img.sent_w < W)
    # 但 1280x1920 = 2.46M 也超；用一张小图验证不缩放
    small = prepare(make_small_png(400, 800), 1690000, 4194304, shrink=True)
    check("小图不缩放", (small.sent_w, small.sent_h) == (400, 800),
          "%dx%d" % (small.sent_w, small.sent_h))


def test_parse() -> None:
    section("2. 模型输出解析")
    cases = [
        ('{"action":"tap","x":1,"y":2}', True, "标准 JSON"),
        ('```json\n{"action":"tap","x":1,"y":2}\n```', True, "markdown 围栏"),
        ('我先点击。{"action":"tap","x":1,"y":2} 完成。', True, "前后有解释文字"),
        ('[{"action":"tap","x":1,"y":2}]', True, "被包成数组"),
        ("抱歉我做不到", False, "没有 JSON"),
        ("", False, "空输出"),
    ]
    for raw, should_ok, label in cases:
        got = parse_action(raw)
        check(label, (got is not None) == should_ok, "-> %s" % got)


def test_tools() -> None:
    section("3. 坐标换算与动作执行")
    fa = FakeAdb()
    ex = ToolExecutor(fa, W, H, "pixel", wait_after_action=0)
    ex.execute({"action": "tap", "x": 540, "y": 1200})
    check("像素坐标直通", fa.actions[-1] == ("tap", 540, 1200))
    ex.execute({"action": "tap", "x": 99999, "y": -500})
    check("越界坐标被 clamp", fa.actions[-1] == ("tap", W - 1, 0), str(fa.actions[-1]))
    ex.execute({"action": "swipe", "direction": "up"})
    a = fa.actions[-1]
    check("方向滑动生成起终点", a[0] == "swipe" and a[3] > a[5], str(a))
    r = ex.execute({"action": "shell", "cmd": "rm -rf /"})
    check("非白名单动作被拒绝", not r.ok and len(fa.actions) == 3)
    r = ex.execute({"action": "tap", "x": 1, "y": 1})
    check("白名单动作正常执行", r.ok)
    r = ex.execute({"action": "finish", "summary": "done"})
    check("finish 标记任务结束", r.done and r.finished)
    r = ex.execute({"action": "fail", "reason": "卡住"})
    check("fail 标记任务结束但非成功", r.done and not r.finished)

    fa2 = FakeAdb()
    ex2 = ToolExecutor(fa2, W, H, "normalized", wait_after_action=0)
    ex2.execute({"action": "tap", "x": 500, "y": 500})
    check("归一化坐标换算", fa2.actions[-1] == ("tap", 540, 1200), str(fa2.actions[-1]))


def test_loop() -> None:
    section("4. 主循环端到端（正常完成）")
    script = [
        {"thought": "点一下", "action": "tap", "x": 540, "y": 1200},
        {"thought": "滑动", "action": "swipe", "direction": "up"},
        {"thought": "输入", "action": "input_text", "text": "hello"},
        {"thought": "结束", "action": "finish", "summary": "全部完成"},
    ]
    srv, port, state = make_server(script)
    try:
        cfg = cfgmod.load("config.json")
        cfg._data.update(
            {
                "base_url": "http://127.0.0.1:%d" % port,
                "api_key": "sk-test",
                "max_steps": 8,
                "draw_grid_overlay": False,
                "wait_after_action": 0,
            }
        )
        fa = FakeAdb()
        agent = Agent(cfg, fa, llmmod.LlmClient(cfg))
        ok = agent.run("测试任务")
        check("任务返回成功", ok is True)
        check(
            "三个动作都到达设备",
            fa.actions
            == [("tap", 540, 1200), ("swipe", 540, 1800, 540, 600, 300), ("input_text", "hello")],
            str(fa.actions),
        )
        check("每次请求都带图片", all(r["has_image"] for r in state["requests"]))
        check("每次请求都带 detail=original",
              all(r["detail"] == "original" for r in state["requests"]),
              "实际: %s" % {r["detail"] for r in state["requests"]})
        check("temperature 按官方建议为 1.0",
              all(r["temperature"] == 1.0 for r in state["requests"]),
              "实际: %s" % {r["temperature"] for r in state["requests"]})
        check("每次只发 2 条消息（不堆积历史截图）",
              all(r["messages"] == 2 for r in state["requests"]),
              str([r["messages"] for r in state["requests"]]))
        check("模型名为 deepseek-flash",
              all(r["model"] == "deepseek-flash" for r in state["requests"]),
              "实际: %s" % {r["model"] for r in state["requests"]})
        check("默认模式（无 RunLogger）下仍能正常跑完", ok is True)
        check("默认模式下不写 last_run.json（已废弃的覆盖式日志）",
              not os.path.exists("logs/last_run.json"))
    finally:
        srv.shutdown()


def test_stagnation() -> None:
    section("5. 防死循环（画面永远不变）")
    script = [{"thought": "再点一次", "action": "tap", "x": 100, "y": 100}]
    srv, port, state = make_server(script)
    try:
        cfg = cfgmod.load("config.json")
        cfg._data.update(
            {
                "base_url": "http://127.0.0.1:%d" % port,
                "api_key": "sk-test",
                "max_steps": 6,
                "draw_grid_overlay": False,
                "wait_after_action": 0,
            }
        )
        agent = Agent(cfg, FakeAdb(frozen=True), llmmod.LlmClient(cfg))
        ok = agent.run("卡死测试")
        check("未完成任务时返回 False", ok is False)
        warned = [p for p in state["seen_prompts"] if "画面静止警告" in p]
        check("画面静止警告注入到模型请求里", len(warned) > 0,
              "共 %d 轮收到警告" % len(warned))
        rep = [p for p in state["seen_prompts"] if "重复动作警告" in p]
        check("重复动作警告注入到模型请求里", len(rep) > 0)
    finally:
        srv.shutdown()


def test_callback_and_stop() -> None:
    section("6. 日志回调与中途停止（GUI 依赖这两点）")

    # --- 日志回调：Agent 不能自己 print，必须走回调 ---
    script = [
        {"thought": "点一下", "action": "tap", "x": 10, "y": 10},
        {"thought": "结束", "action": "finish", "summary": "done"},
    ]
    srv, port, state = make_server(script)
    try:
        cfg = cfgmod.load("config.json")
        cfg._data.update(
            {
                "base_url": "http://127.0.0.1:%d" % port,
                "api_key": "sk-test",
                "max_steps": 5,
                "draw_grid_overlay": False,
                "wait_after_action": 0,
            }
        )
        captured = []
        agent = Agent(cfg, FakeAdb(), llmmod.LlmClient(cfg), log_cb=captured.append)
        ok = agent.run("回调测试")
        check("日志回调收到了消息", len(captured) > 5, "共 %d 条" % len(captured))
        check("回调内容包含任务和步骤信息",
              any("任务：" in m for m in captured)
              and any("第 1 / 5 步" in m for m in captured))
        check("自定义回调下任务仍能正常完成", ok is True)

        # --- 停止回调：模拟用户点「停止」 ---
        stop_after = {"n": 0}

        def stop_now():
            # 第 3 次检查时请求停止
            stop_after["n"] += 1
            return stop_after["n"] > 2

        loop_script = [{"thought": "重复点", "action": "tap", "x": 10, "y": 10}]
        srv2, port2, state2 = make_server(loop_script)
        try:
            cfg2 = cfgmod.load("config.json")
            cfg2._data.update(
                {
                    "base_url": "http://127.0.0.1:%d" % port2,
                    "api_key": "sk-test",
                    "max_steps": 20,
                    "draw_grid_overlay": False,
                    "wait_after_action": 0,
                }
            )
            msgs = []
            fa = FakeAdb()
            agent2 = Agent(cfg2, fa, llmmod.LlmClient(cfg2),
                           log_cb=msgs.append, stop_cb=stop_now)
            ok2 = agent2.run("停止测试")
            check("收到停止请求后任务中止", ok2 is False)
            check("中止提示出现在日志里", any("用户停止了任务" in m for m in msgs))
            check("中止后没有继续执行更多动作", len(fa.actions) < 5,
                  "实际执行 %d 步" % len(fa.actions))
        finally:
            srv2.shutdown()
    finally:
        srv.shutdown()


def test_paths() -> None:
    section("7. 路径解析（打包成 .app 后的关键）")
    import paths as pathsmod

    check("开发模式下 is_frozen 为 False", pathsmod.is_frozen() is False)
    check("资源目录存在", os.path.isdir(pathsmod.resource_dir()))
    check("用户数据目录会被创建", os.path.isdir(pathsmod.user_data_dir()))
    check("配置文件能定位到", pathsmod.config_path().endswith("config.json"))
    check("日志目录能定位到", os.path.isdir(pathsmod.log_dir()))

    # 模拟打包后没有终端的情况：safe_print 不能崩
    saved = sys.stdout
    sys.stdout = None  # type: ignore
    try:
        pathsmod.safe_print("无 stdout 时不应崩溃")
        survived = True
    except Exception:
        survived = False
    finally:
        sys.stdout = saved
    check("无 stdout 时 safe_print 不崩溃", survived)


def test_logging() -> None:
    section("8. 日志落盘（出问题时唯一能查的东西）")
    import shutil
    import tempfile

    from run_logger import RunLogger, describe_env, write_report

    tmp = tempfile.mkdtemp(prefix="aiPhoneLogTest_")
    try:
        # --- 每次运行必须是独立目录，不能互相覆盖 ---
        lg1 = RunLogger(tmp, label="打开设置看WiFi", kind="run")
        lg1.write("第一次运行的第一行")
        lg1.write("乱码字符 / \\ : * ? \" < > | 应该被清掉")
        lg1.finish(note="测试完成")
        lg1.close()

        lg2 = RunLogger(tmp, label="打开设置看WiFi", kind="run")
        lg2.write("第二次运行的内容")
        lg2.finish(note="测试完成")
        lg2.close()

        check("两次运行生成不同目录", lg1.dir != lg2.dir)
        check("第一次的日志文件仍然存在", os.path.exists(lg1.path))
        check("两个日志文件内容互不覆盖",
              "第一次运行的第一行" in open(lg1.path, encoding="utf-8").read()
              and "第一次" not in open(lg2.path, encoding="utf-8").read())
        check("目录名包含任务摘要和中文",
              "打开设置看WiFi" in os.path.basename(lg1.dir),
              os.path.basename(lg1.dir))

        # --- 内容完整性 ---
        content = open(lg1.path, encoding="utf-8").read()
        check("日志含开始时间", "开始时间：" in content)
        check("日志含结束时间", "结束时间：" in content)
        check("日志含耗时", "总耗时：" in content)
        check("日志含结果", "结果：测试完成" in content)
        check("每行都有时间戳", content.count("[") >= 8)
        check("任务描述里的特殊字符被清理，没有生成非法路径",
              os.path.isdir(lg1.dir))

        # --- 截图目录 ---
        check("运行日志有自己的截图目录", os.path.isdir(lg1.shot_dir))
        check("截图目录在本次运行目录内",
              lg1.shot_dir.startswith(lg1.dir))

        # --- latest 软链接 ---
        latest = os.path.join(tmp, "latest")
        check("latest 软链接指向最后一次运行",
              os.path.islink(latest) and os.readlink(latest) == lg2.dir)

        # --- 诊断类日志进 diag/ ---
        dg = RunLogger(tmp, label="check", kind="diag")
        dg.write("自检输出")
        dg.finish()
        dg.close()
        check("诊断日志放在 diag/ 下",
              os.path.basename(os.path.dirname(dg.path)) == "diag",
              dg.path)

        # --- 环境信息 ---
        cfg = cfgmod.load("config.json")
        env = describe_env(cfg, adb_serial="TEST123")
        joined = "\n".join(env)
        check("环境信息含 Python 版本", "Python" in joined)
        check("环境信息含模型名", "deepseek-flash" in joined)
        check("环境信息含设备序列号", "TEST123" in joined)
        check("环境信息里的 API Key 已脱敏",
              "sk-" not in joined or "***" in joined)

        # --- 结构化报告 ---
        rp = os.path.join(lg1.dir, "report.json")
        check("报告能写入", write_report(rp, {"task": "x", "steps": []}))
        check("报告是合法 JSON",
              json.load(open(rp, encoding="utf-8"))["task"] == "x")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def test_agent_writes_to_run_dir() -> None:
    section("9. Agent 把截图和报告写进本次运行目录")
    import shutil
    import tempfile

    from run_logger import RunLogger

    tmp = tempfile.mkdtemp(prefix="aiPhoneRunTest_")
    try:
        script = [
            {"thought": "点一下", "action": "tap", "x": 10, "y": 10},
            {"thought": "结束", "action": "finish", "summary": "done"},
        ]
        srv, port, state = make_server(script)
        try:
            cfg = cfgmod.load("config.json")
            cfg._data.update({
                "base_url": "http://127.0.0.1:%d" % port,
                "api_key": "sk-test",
                "max_steps": 5,
                "draw_grid_overlay": False,
                "wait_after_action": 0,
                "save_screenshots": True,
            })
            logger = RunLogger(tmp, label="测试任务", kind="run")
            agent = Agent(cfg, FakeAdb(), llmmod.LlmClient(cfg),
                          log_cb=logger.write, run_logger=logger)
            agent.run("测试任务")
            logger.finish()
            logger.close()

            # 截图必须落在本次运行目录里，不能散落在公共 logs 下
            shots = os.listdir(logger.shot_dir)
            check("截图写进了本次运行目录", len(shots) >= 2, "共 %d 张" % len(shots))
            check("截图文件名是 step_NN.png",
                  all(s.startswith("step_") and s.endswith(".png") for s in shots),
                  str(sorted(shots)))
            check("报告写在本次运行目录里",
                  os.path.exists(logger.report_path))
            rep = json.load(open(logger.report_path, encoding="utf-8"))
            check("报告记录了每一步",
                  rep["total_steps"] >= 2 and len(rep["steps"]) >= 2,
                  "total_steps=%s" % rep.get("total_steps"))
            check("报告记录了模型原始输出",
                  "raw_model_output" in rep["steps"][0])
            check("文本日志里能看到每一步的动作",
                  "tap" in open(logger.path, encoding="utf-8").read())
            check("报告里不含旧的 last_run.json 写法",
                  "last_run" not in logger.report_path)
        finally:
            srv.shutdown()
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    print("=" * 62)
    print("  离线自测 —— 不需要真机，不需要 API Key")
    print("=" * 62)
    test_image()
    test_parse()
    test_tools()
    test_loop()
    test_stagnation()
    test_callback_and_stop()
    test_paths()
    test_logging()
    test_agent_writes_to_run_dir()

    print("\n" + "=" * 62)
    print("结果：%d 项通过，%d 项失败" % (len(PASS), len(FAIL)))
    if FAIL:
        print("\n失败项：")
        for f in FAIL:
            print("  - %s" % f)
    print("=" * 62)
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
