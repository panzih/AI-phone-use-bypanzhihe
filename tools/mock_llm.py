#!/usr/bin/env python3
"""
假的 OpenAI 兼容服务，用来端到端验证纸盒的 AI 循环。

它按顺序返回一串预设动作，模拟"模型在一步步操作手机"：

    第1轮  tap 编号[2]      —— 故意带 markdown 围栏，验证防御性解析
    第2轮  scroll down      —— 验证不需要坐标的滚动
    第3轮  tap 坐标(540,1200) —— 验证坐标式点击
    第4轮  编了个假动作      —— 验证白名单拦截（应该被拒绝并重试）
    第5轮  finished=true    —— 验证正常收尾

跑起来：

    python3 tools/mock_llm.py

模拟器里把「设置 → 模型 → 接口地址」填 http://10.0.2.2:8765
（10.0.2.2 是安卓模拟器访问宿主机的固定地址）
"""

import json
import os
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8765

# 每次响应前等多久（秒）。默认 0，调大方便观察悬浮窗这类
# "一闪而过"的界面：
#     MOCK_DELAY=2 python3 tools/mock_llm.py
DELAY = float(os.environ.get("MOCK_DELAY", "0"))

# 每一轮要回的动作。最后一个发完就停在 finished 上。
SCRIPTED = [
    # 1) 带 markdown 围栏 —— 模型最常干的事。
    #    next_hint 是给用户看的"下一步预告"，会显示在悬浮窗左上角。
    "```json\n"
    '{"thought": "界面上有个设置按钮，先点它", "action": "tap", "index": 2,'
    ' "next_hint": "在设置里找到网络和互联网", "finished": false}\n'
    "```",

    # 2) 滚动，不给坐标
    '{"thought": "列表还有更多内容，往下滚", "action": "scroll", "direction": "down",'
    ' "next_hint": "点进 WiFi 那一项", "finished": false}',

    # 3) 坐标式点击
    '{"thought": "用坐标点一下屏幕中间", "action": "tap", "x": 540, "y": 1200,'
    ' "next_hint": "确认 WiFi 开关的状态", "finished": false}',

    # 4) 编造的动作名 —— 必须被白名单拦下
    '{"thought": "我要执行一个不存在的动作", "action": "shell", "command": "rm -rf /",'
    ' "next_hint": "这一步不该执行", "finished": false}',

    # 5) 坐标越界 —— 验证夹紧
    '{"thought": "点一个超出屏幕的坐标", "action": "tap", "x": 99999, "y": -50,'
    ' "next_hint": "这一步也不该执行", "finished": false}',

    # 6) 收尾
    '{"thought": "做完了", "action": "", "finished": true, "summary": "全部步骤执行完毕，链路验证通过"}',
]

state = {"n": 0, "last_messages": None, "last_texts": None}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        try:
            body = json.loads(raw)
        except Exception:
            body = {}

        n = state["n"]
        state["n"] += 1

        if DELAY > 0:
            time.sleep(DELAY)

        messages = body.get("messages", [])
        model = body.get("model", "?")
        has_image = any(
            isinstance(m.get("content"), list)
            for m in messages
            if isinstance(m, dict)
        )
        img_count = 0
        for m in messages:
            c = m.get("content")
            if isinstance(c, list):
                img_count += sum(1 for p in c if isinstance(p, dict) and p.get("type") == "image_url")

        # ---- 前缀校验 ----
        #
        # 上下文缓存是按**最长公共前缀**复用的。所以这里把这次请求的
        # 消息序列和上次比，看前缀有没有被保住。
        # 如果历史被裁剪、或者"发一套记一套"，这里的匹配长度会骤降到 1
        # （只剩 system），并直接暴露出来。
        texts = []
        for m in messages:
            c = m.get("content")
            if isinstance(c, str):
                texts.append(m.get("role") + ":" + c[:40])
            else:
                parts = [p.get("text", "[图]") for p in c if isinstance(p, dict)]
                texts.append(m.get("role") + ":" + "".join(parts)[:40])

        prefix = 0
        if state["last_texts"] is not None:
            for a, b in zip(state["last_texts"], texts):
                if a == b:
                    prefix += 1
                else:
                    break
        # 上一次的整条序列如果原样成为这次的前缀，就是 100% 命中
        prev_len = len(state["last_texts"]) if state["last_texts"] else 0
        state["last_texts"] = texts

        print(f"\n=== 第 {n + 1} 次请求 ===")
        print(f"  消息数    : {len(messages)}  上次 {prev_len} 条")
        print(f"  前缀复用  : {prefix} / {prev_len} 条" +
              ("   ✅ 前缀完整保留" if prev_len and prefix == prev_len else
               "   ⚠️ 前缀被打断" if prev_len else ""))
        print(f"  模型      : {model}")
        print(f"  消息数    : {len(messages)}")
        print(f"  带图的消息: {img_count}  (当前轮有图 = {has_image})")
        # 抽查最后一条 user 消息里有没有控件树
        for m in reversed(messages):
            if m.get("role") == "user":
                c = m.get("content")
                text = c if isinstance(c, str) else " ".join(
                    p.get("text", "") for p in c if isinstance(p, dict)
                )
                if "界面元素" in text:
                    head = text.split("界面元素")[1][:120].replace("\n", " | ")
                    print(f"  控件树片段: {head}")
                break

        # 按剧本回，超过剧本长度就一直是 finished
        idx = min(n, len(SCRIPTED) - 1)
        content = SCRIPTED[idx]
        print(f"  返回      : {content[:100]}")

        payload = {
            "id": "mock-1",
            "object": "chat.completion",
            "model": model,
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": content},
                    "finish_reason": "stop",
                }
            ],
            # 粗略模拟：每条消息算 400 token。
            # 前缀保住的那部分算缓存命中，剩下的算未命中 ——
            # 数字本身是编的，重点是让"命中率"这个上报能被验证。
            "usage": {
                "prompt_tokens": 400 * len(messages),
                "completion_tokens": 40,
                "total_tokens": 400 * len(messages) + 40,
                "prompt_cache_hit_tokens": 400 * prefix,
                "prompt_cache_miss_tokens": 400 * (len(messages) - prefix),
            },
        }
        data = json.dumps(payload).encode("utf-8")

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass  # 关掉默认的访问日志，只留我们自己打的


if __name__ == "__main__":
    print(f"假模型服务已启动：http://0.0.0.0:{PORT}/v1/chat/completions")
    print("模拟器里接口地址填 http://10.0.2.2:%d" % PORT)
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
