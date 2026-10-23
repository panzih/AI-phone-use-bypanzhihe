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

⚠️ BlueStacks 上不要用 `adb reverse` + 127.0.0.1 —— 实测它只建 TCP
连接、不转发数据（服务端收到空流），应用侧报 `unexpected end of stream`。
10.0.2.2 是通的。

环境变量：

    MOCK_DELAY=2      每次响应前等 2 秒（方便看一闪而过的悬浮窗）
    MOCK_SCRIPT=...   full（默认）/ bounded
    MOCK_DUMP=<路径>  把每次请求的完整 payload 追加落盘，便于 grep
                      "端侧到底往消息里塞了什么"
"""

import json
import os
import sys
import time
# 必须用**多线程**版本：内置浏览器的 WebView 会自己发 GET 请求并保持
# keep-alive 连接，单线程 HTTPServer 会被那条连接占住，模型的 POST 进来时
# 服务端根本没在处理 —— 现场表现是"模型一直没返回"，而假模型日志里只有 1 次请求。
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

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

# 把每次请求的完整 payload 追加写到这个文件（不设就不写）。
# 用来查"端侧到底往消息里塞了什么"，比如长等待的警告有没有回灌。
DUMP_PATH = os.environ.get("MOCK_DUMP", "")

# 每一轮要回的回复。最后一个发完就停在 finished 上。
#
# 用环境变量 MOCK_SCRIPT 选择剧本：
#   MOCK_SCRIPT=full     完整剧本（默认）：覆盖各种边界情况
#   MOCK_SCRIPT=bounded  F1 有界性验证：永远要图 + 给一个能解析的动作
SCRIPT = os.environ.get("MOCK_SCRIPT", "full")

if SCRIPT == "browser":
    # 内置浏览器（T43）端到端验证剧本。
    #
    # 用法：MOCK_SCRIPT=browser MOCK_DELAY=1 python3 tools/mock_llm.py
    # 页面由本服务自己的 GET /testpage 提供（10.0.2.2 从模拟器可达），
    # 内容固定、可断言：一个标题、一个链接、一个输入框、一个按钮、一个输出区。
    #
    # 预期走完：打开浏览器 → 读一段源码 → 跑一段 JS → 按编号点按钮 → 收尾。
    # 每步的 user 消息里能看到**DOM 摘要**（编号列表），这就是"控件树"
    # 在浏览器模式下的形态 —— 用 MOCK_DUMP 落盘后 grep 就能看。
    # 想换被测页面就设 MOCK_PAGE（例如 MOCK_PAGE=https://example.com 验证真站点）
    PAGE = os.environ.get("MOCK_PAGE", "http://10.0.2.2:8765/testpage")
    SCRIPTED = [
        # 1 打开内置浏览器（模型自己决定用浏览器这条路）
        '{"thought":"这是网页任务，用内置浏览器打开测试页","actions":[{"action":"browser_open","url":"' + PAGE + '"}]}',
        # 2 读源码：只读 #kw 那一块，验证 selector 生效、且内容能回来
        '{"thought":"先看看搜索框背后的代码","actions":[{"action":"browser_html","selector":"#kw","max_chars":2000}]}',
        # 3 跑 JS：确认页面就绪并读出按钮文字
        '{"thought":"确认页面结构和按钮","actions":[{"action":"browser_eval","js":"JSON.stringify({title:document.title,btns:[...document.querySelectorAll(\'button\')].map(b=>b.textContent)})"}]}',
        # 4 按编号点按钮（编号来自 DOM 摘要，点完输出区应该变成"已点击按钮"）
        '{"thought":"点那个按钮","actions":[{"action":"tap","index":3}]}',
        # 5 读回结果，确认点击真的生效了
        '{"thought":"看看点击有没有生效","actions":[{"action":"browser_eval","js":"document.getElementById(\'out\').textContent"}]}',
        # 6 收尾
        '{"thought":"测试页按钮点过了，输出区显示已点击","finished":true,"summary":"内置浏览器测试完成：打开页面、读源码、执行 JS、按编号点击都正常"}',
    ]
elif SCRIPT == "review":
    # 「点击前复核」端到端剧本：
    #   主请求 1：给一个**坐标点击**（没有 index，所以 coords 档会触发复核）
    #   复核请求：故意说"偏左"，给出图内更正坐标 -> App 应改点并继续
    #   主请求 2：收尾
    # 复核请求不消耗主剧本索引（见 do_POST 里的 is_review）。
    SCRIPTED = [
        '{"thought":"先点一下目标","actions":[{"action":"tap","x":300,"y":400}]}',
        '{"thought":"复核改点后确认页面已变","finished":true,"summary":"点击前复核测试完成"}',
    ]
    REVIEW_SCRIPT = [
        '{"ok":false,"x":120,"y":130,"why":"偏左，往右一点"}',
    ]
elif SCRIPT == "bounded":
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

state = {"n": 0, "total": 0, "review_n": 0, "last_messages": None, "last_texts": None}

# 非 review 剧本也要有这个变量，避免 do_POST 里分支不存在
if "REVIEW_SCRIPT" not in globals():
    REVIEW_SCRIPT = ['{"ok":true}']


class Handler(BaseHTTPRequestHandler):
    PAGE_HTML = """<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>纸盒测试页</title></head>
<body>
<h1 id="h">纸盒浏览器测试</h1>
<p id="p">这一页专门给内置浏览器通道的端到端测试用。</p>
<a id="link1" href="/testpage?p=1">第一页链接</a>
<input id="kw" placeholder="搜索关键词">
<button id="btn" onclick="document.getElementById('out').textContent='已点击按钮'">点我</button>
<div id="out">还没点</div>
<script>window.__probe = 'ready';</script>
</body></html>"""

    def do_GET(self):
        # 给内置浏览器的端到端测试提供一个固定页面（模拟器访问 10.0.2.2:8765）
        if self.path.startswith("/testpage"):
            body = self.PAGE_HTML.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            print("  GET /testpage → 200")
            return
        self.send_response(404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        try:
            body = json.loads(raw)
        except Exception:
            body = {}

        serial = state["total"]
        state["total"] += 1

        # 把这一次的**完整请求体**原样落盘。
        #
        # 上面那些打印只看得到摘要（消息数、前缀、控件树片段），
        # 而"端侧往消息里塞了什么"往往正是要验证的东西 ——
        # 比如 long_wait 被夹紧后给模型的警告、技能返回的全文。
        # 摘要看不出来，全量 payload 可以 grep。
        #
        #     MOCK_DUMP=/tmp/payloads.jsonl python3 tools/mock_llm.py
        if DUMP_PATH:
            try:
                with open(DUMP_PATH, "a", encoding="utf-8") as f:
                    f.write(f"===== 第 {serial + 1} 次请求 =====\n")
                    f.write(raw.decode("utf-8", "replace"))
                    f.write("\n\n")
            except Exception as e:
                print(f"  [dump 失败] {e}")

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
        # 服务端只命中完整匹配的缓存前缀单元。这里粗略比较本次与上次
        # 的消息序列，先看前缀有没有被保住；图片只看消息类型，不能检验
        # base64 内容。涉及图片的严格核验请用 tools/check_prefix.py。
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

        # 复核请求是独立的一问一答，不参与主对话前缀比较；
        # 也不能改 last_texts，否则下一次主请求会被误判成"前缀断了"。
        system_text = "".join(
            m.get("content", "") for m in messages
            if isinstance(m, dict) and m.get("role") == "system" and isinstance(m.get("content"), str)
        )
        is_review = "点击前复核员" in system_text

        prefix = 0
        prev_len = len(state["last_texts"]) if state["last_texts"] else 0
        if not is_review:
            if state["last_texts"] is not None:
                for a, b in zip(state["last_texts"], texts):
                    if a == b:
                        prefix += 1
                    else:
                        break
            # 上一次的整条序列如果原样成为这次的前缀，就是 100% 命中
            state["last_texts"] = texts

        print(f"\n=== 第 {serial + 1} 次请求{'（点击前复核）' if is_review else ''} ===")
        print(f"  消息数    : {len(messages)}  上次 {prev_len} 条")
        if is_review:
            print("  复核请求  : 独立上下文（不参与主对话前缀）")
        else:
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

        # 按剧本回，超过剧本长度就一直是 finished。
        # 复核请求单独走 REVIEW_SCRIPT，不消耗主剧本索引 —— 否则每复核一次
        # 都会把主剧本往前推一格，端到端测试全部错位。
        if is_review:
            ridx = min(state["review_n"], len(REVIEW_SCRIPT) - 1)
            state["review_n"] += 1
            content = REVIEW_SCRIPT[ridx]
            print(f"  复核返回  : {content[:100]}")
        else:
            idx = min(state["n"], len(SCRIPTED) - 1)
            state["n"] += 1
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
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
