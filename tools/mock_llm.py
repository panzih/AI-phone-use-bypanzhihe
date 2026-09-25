#!/usr/bin/env python3
"""
假的 OpenAI 兼容服务，用来端到端验证纸盒的 AI 循环。

它按顺序返回一串预设回复，专门覆盖新协议的边界情况。
**注意：索引是"第几次 HTTP 请求"，不是"第几步"** —— 要图和调技能都会
多产生一次请求（这一轮不算一步），所以下面的注释把两者都标出来了。
`capture` 则是**动作**，不额外产生请求（图随下一步走）。

    请求1  一批 3 个动作（点 2 → 显式 sleep 1.5s → 点 5）
           —— 验证"一轮一批动作"，以及显式 sleep 顶掉默认间隔
    请求2  {"action":"capture"} 夹在一批里
           —— 验证 capture 归一化：图随**下一步**发，步骤不因此多一次往返
    请求3  need_image=true（不给动作）
           —— 验证按需截图：这一轮不算一步，会立刻带图重问
    请求4  看完图后给动作（旧的单动作格式 + markdown 围栏）
           —— 验证防御性解析和向后兼容
    请求5  use_skill="list_skills"
           —— 验证"按需取技能说明文档"
    请求6  use_skill="list_apps"
           —— 验证技能调用：系统应把已安装应用 + 包名回灌给模型
    请求7  用拿到的包名 open_app
           —— 验证技能结果确实留在上下文里、能被用上
    请求8  连点两次 + 中间 sleep 1ms
           —— 模拟双击
    请求9  actions 里混一个编造的动作名（shell）和一个合法动作
           —— 验证白名单：坏的那条丢掉、好的照常执行
    请求10 坐标越界
           —— 验证夹紧
    请求11 sleep 25000 但没声明 long_wait
           —— 验证长等待夹到 5000ms 并把警告回灌给模型（0.9.0 的新规则）
    请求12 finished=true
           —— 验证正常收尾

关键看服务端这边的几行输出：

    带图的消息: N        ← 请求 1~2 是 0（默认不发图）；之后**会一直增长**，
                           这是对的（图跟着消息留在历史里，缓存才命中）
    前缀复用  : X / Y 条   ✅ 前缀完整保留 ← 这个必须是"完整"，否则说明
                           请求构造把历史改动了，缓存会大面积落空
    技能返回  : ...       ← 应用列表那一大段会打在应用日志里

跑起来：

    python3 tools/mock_llm.py

模拟器里把「设置 → 模型 → 接口地址」填 http://10.0.2.2:8765
（10.0.2.2 是安卓模拟器访问宿主机的固定地址）
"""

import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

# 默认 stdout 是块缓冲的，日志会卡在缓冲区里看不见 ——
# 排查问题时最想看的恰好是最后那几行，所以改成行缓冲。
try:
    sys.stdout.reconfigure(line_buffering=True)
except Exception:
    pass

PORT = 8765

# 每次响应前等多久（秒）。默认 0，调大方便观察悬浮窗这类
# "一闪而过"的界面：
#     MOCK_DELAY=2 python3 tools/mock_llm.py
DELAY = float(os.environ.get("MOCK_DELAY", "0"))

# 每一轮要回的回复。最后一个发完就停在 finished 上。
#
# 用环境变量 MOCK_SCRIPT 选择剧本：
#   MOCK_SCRIPT=full     完整剧本（默认）：覆盖各种边界情况
#   MOCK_SCRIPT=bounded  F1 有界性验证：永远要图 + 给一个能解析的动作
SCRIPT = os.environ.get("MOCK_SCRIPT", "full")

if SCRIPT == "bounded":
    # F1 有界性验证剧本：永远要图 + 给一个能解析的动作。
    # 预期：最多 5 次 ask 循环就会 finish(false)，不会无限烧钱。
    SCRIPTED = [
        '{"thought":"我需要看截图","need_image": true, "actions":[{"action":"tap","x":1,"y":1}]}',
    ] * 10  # 重复 10 次，足够触发超限计数器
else:
    # 完整剧本：覆盖各种边界情况
    SCRIPTED = [
        # 请求1  一批 3 个动作（点 2 → 显式 sleep 1.5s → 点 5）
        #        —— 验证"一轮一批动作"，以及显式 sleep 顶掉默认间隔
        '{"thought":"先点一下，等一下，再点一下","actions":[{"action":"tap","x":200,"y":200},{"action":"sleep","duration_ms":1500},{"action":"tap","x":500,"y":500}]}',
        # 请求2  capture 夹在一批动作里
        #        —— 验证 capture：截图随下一步发，不额外多一次往返
        '{"thought":"点完之后想看一眼前一屏","actions":[{"action":"tap","x":100,"y":100},{"action":"capture"}]}',
        # 请求3  need_image=true（不给动作）
        #        —— 验证按需截图
        '{"thought":"我需要看截图","need_image": true}',
        # 请求4  看完图后给动作（旧的单动作格式 + markdown 围栏）
        #        —— 验证防御性解析和向后兼容
        '```json\n{"thought":"看到了，点这个按钮","action":"tap","x":300,"y":400}\n```',
        # 请求5  use_skill="list_skills"
        #        —— 验证"按需取技能说明文档"
        '{"thought":"我想看看有哪些技能","use_skill":"list_skills"}',
        # 请求6  use_skill="list_apps"
        #        —— 验证技能调用：系统应把已安装应用 + 包名回灌给模型
        '{"thought":"我想看看有哪些应用","use_skill":"list_apps"}',
        # 请求7  用拿到的包名 open_app
        #        —— 验证技能结果确实留在上下文里、能被用上
        '{"thought":"打开设置应用","actions":[{"action":"open_app","package":"com.android.settings"}]}',
        # 请求8  连点两次 + 中间 sleep 1ms
        #        —— 模拟双击
        '{"thought":"双击这个位置","actions":[{"action":"tap","x":100,"y":100},{"action":"sleep","duration_ms":1},{"action":"tap","x":100,"y":100}]}',
        # 请求9  actions 里混一个编造的动作名（shell）和一个合法动作
        #        —— 验证白名单：坏的那条丢掉、好的照常执行
        '{"thought":"执行几个操作","actions":[{"action":"shell","cmd":"ls"},{"action":"tap","x":200,"y":200}]}',
        # 请求10 坐标越界
        #        —— 验证夹紧
        '{"thought":"点一下屏幕外面","actions":[{"action":"tap","x":9999,"y":9999}]}',
        # 请求11 长等待但没声明 long_wait
        #        —— 验证夹到 5000ms，并把"要声明 long_wait"回灌给模型
        '{"thought":"等页面加载","actions":[{"action":"sleep","duration_ms":25000},{"action":"tap","x":300,"y":300}]}',
        # 请求12 finished=true
        #        —— 验证正常收尾
        '{"thought":"任务完成了","finished":true}',
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
        # 注意：带图的消息数会随步数**增长**，这是正常的 ——
        # 图片跟着它所属的那条消息一直留在历史里，是缓存能命中的前提。
        # 以前"只给最后一条挂图"，历史里的图被丢掉，导致前缀从第一条
        # user 就分叉，缓存全部落空（命中率≈0，费用翻好几倍）。
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
