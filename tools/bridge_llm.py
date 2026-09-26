#!/usr/bin/env python3
"""
桥接 LLM —— 把纸盒的模型请求变成「写文件 → 等人/AI 回复 → 读文件」。

## 为什么有这个东西

纸盒的「接口地址」本来就是任意 OpenAI 兼容端点，所以可以拿掉真模型：
每次它要模型做决定，就把**完整请求体**落成一个文件，然后阻塞等待
一个回复文件；回复文件一到，就按 OpenAI 的格式包好还给它。

代价为零（不花 API 额度），而且请求体是原样存下来的，排查问题最方便。

## 目录结构

    .bridge/
      pending/req-0001.json     ← 纸盒发来的完整请求体（原样）
      pending/req-0001.md       ← 同一份请求的**可读版**（步号/任务/控件树/…）
      pending/req-0001.jpg      ← 这一轮附带的截图（如果有）
      pending/system-prompt.txt ← 系统提示词（只写一次，协议说明都在里面）
      done/res-0001.json        ← 回复放这里
      bridge.log                ← 收发流水

## 怎么回复

往 `done/res-0001.json` 写：

    {"content": "{\"thought\":\"...\",\"actions\":[{\"action\":\"tap\",\"index\":3}]}"}

或者干脆把模型该输出的那段 JSON 直接写进文件也行（不包 content 层），
脚本认不出来是外面那层时，就当整个文件是 content。

## 跑起来

    python3 tools/bridge_llm.py

纸盒里「设置 → 模型 → 接口地址」填 http://10.0.2.2:8766

模拟器/主机网络说明见 tools/mock_llm.py 的文档（BlueStacks 上 adb reverse
只建连不转发数据，必须用 10.0.2.2）。

## 环境变量

    BRIDGE_DIR     默认 ~/DSH/AI手机/.bridge
    BRIDGE_PORT    默认 8766
    BRIDGE_TIMEOUT 等回复的秒数，默认 900
    BRIDGE_HOLD    等了这么久还没人来处理就先放一个"稍等"回复让纸盒别干等
                   （默认 0 = 一直等）
"""

import base64
import json
import os
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    sys.stdout.reconfigure(line_buffering=True)
except Exception:
    pass

BASE = os.environ.get("BRIDGE_DIR", os.path.expanduser("~/DSH/AI手机/.bridge"))
PENDING = os.path.join(BASE, "pending")
DONE = os.path.join(BASE, "done")
PORT = int(os.environ.get("BRIDGE_PORT", "8766"))
TIMEOUT = float(os.environ.get("BRIDGE_TIMEOUT", "900"))

_seq_lock = threading.Lock()
_seq = {"n": 0}

# ---------------------------------------------------------------- 前缀统计
#
# 真模型返回的 usage 里有 prompt_cache_hit_tokens / prompt_cache_miss_tokens，
# 纸盒靠它算「上下文缓存命中率」那行日志。我们这边没有真 token 数，但
# **前缀长度是能精确算出来的** —— 而命中率 = 前缀 / 总数，这个比值是真的。
#
# 不补这一段的话，桥接模式下那行日志永远是「命中 0 / 未命中 0（命中率 无数据）」，
# 等于把"缓存有没有被打断"这个最重要自查指标给丢了。
#
# ⚠️ token 数本身是编的（每条消息按 PER_MSG_TOKENS 估），**只有比值可信**。
PER_MSG_TOKENS = 400

_prefix_lock = threading.Lock()
_prev_keys = {"v": None}


def prefix_stats(messages):
    """返回 (前缀条数, 上次总条数)。逐条深比较，图片 base64 也比。"""
    keys = [json.dumps(m, ensure_ascii=False, sort_keys=True) for m in messages]
    with _prefix_lock:
        prev = _prev_keys["v"]
        hit = 0
        if prev is not None:
            for a, b in zip(prev, keys):
                if a == b:
                    hit += 1
                else:
                    break
        prev_len = len(prev) if prev is not None else 0
        _prev_keys["v"] = keys
    return hit, prev_len


def log(msg):
    line = time.strftime("%H:%M:%S") + "  " + msg
    print(line)
    try:
        with open(os.path.join(BASE, "bridge.log"), "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass


def ensure_dirs():
    for d in (BASE, PENDING, DONE):
        os.makedirs(d, exist_ok=True)


# ---------------------------------------------------------------- 可读版

def split_sections(text):
    """把「# 标题」切段。返回 [(标题, 正文)]，第一段标题是 None。"""
    parts = re.split(r"^# ", text, flags=re.M)
    out = []
    first = parts[0]
    if first.strip():
        out.append((None, first))
    for p in parts[1:]:
        nl = p.find("\n")
        if nl < 0:
            out.append((p.strip(), ""))
        else:
            out.append((p[:nl].strip(), p[nl + 1:]))
    return out


def write_digest(n, body, raw):
    """写一份给人/AI 看的摘要。原样 JSON 太大（还带 base64 图），读摘要就够。"""
    messages = body.get("messages", [])
    lines = [f"# 请求 {n}", ""]

    # 系统提示词单独存一份，读一次就行
    sp = os.path.join(PENDING, "system-prompt.txt")
    if messages and messages[0].get("role") == "system" and not os.path.exists(sp):
        c = messages[0].get("content")
        with open(sp, "w", encoding="utf-8") as f:
            f.write(c if isinstance(c, str) else json.dumps(c, ensure_ascii=False, indent=2))

    # 最后一条 user 消息 = 这一步真正要看的东西
    last = None
    for m in reversed(messages):
        if m.get("role") == "user":
            last = m
            break
    if last is None:
        lines.append("（没有 user 消息，异常）")
        with open(os.path.join(PENDING, f"req-{n:04d}.md"), "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
        return

    content = last.get("content")
    text = content if isinstance(content, str) else "".join(
        p.get("text", "") for p in content if isinstance(p, dict) and p.get("type") != "image_url"
    )

    step = re.search(r"当前是第 (\d+) / (\d+) 步", text)
    task = re.search(r"用户的任务：([^\n]*)", text)
    lines.append(f"- 步号：**{step.group(1)} / {step.group(2)}**" if step else "- 步号：（没找到）")
    lines.append(f"- 任务：{task.group(1).strip()}" if task else "- 任务：（没找到）")
    lines.append(f"- 历史消息数：{len(messages)}")
    lines.append("")

    for title, bodytext in split_sections(text):
        if title is None:
            # 开头那段（步号/任务/上一批执行结果）
            lines.append("## 开头")
            lines.append(bodytext.strip())
            lines.append("")
            continue
        if title == "截图":
            lines.append("## 截图")
            lines.append("（见同目录的 .jpg，用看图工具打开）")
            lines.append("")
            continue
        lines.append("## " + title)
        lines.append(bodytext.strip())
        lines.append("")

    with open(os.path.join(PENDING, f"req-{n:04d}.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    # 截图单独存成 jpg，方便直接看
    if isinstance(content, list):
        for i, p in enumerate(content):
            if isinstance(p, dict) and p.get("type") == "image_url":
                url = p.get("url", "")
                m = re.match(r"data:image/\w+;base64,(.*)$", url, re.S)
                if m:
                    try:
                        blob = base64.b64decode(m.group(1))
                        ext = "jpg" if blob[:2] == b"\xff\xd8" else "png"
                        with open(os.path.join(PENDING, f"req-{n:04d}.{ext}"), "wb") as f:
                            f.write(blob)
                        log(f"    截图已存 pending/req-{n:04d}.{ext}（{len(blob)//1024} KB）")
                    except Exception as e:
                        log(f"    截图解码失败：{e}")
                break


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        try:
            body = json.loads(raw)
        except Exception:
            body = {}
        messages = body.get("messages", [])

        with _seq_lock:
            _seq["n"] += 1
            n = _seq["n"]

        with open(os.path.join(PENDING, f"req-{n:04d}.json"), "w", encoding="utf-8") as f:
            f.write(raw.decode("utf-8", "replace"))

        # 前缀统计要在写摘要**之前**算：它自己也依赖"上一次的 messages"这个状态
        hit, prev_len = prefix_stats(messages)

        step = ""
        for m in reversed(messages):
            if m.get("role") == "user":
                c = m.get("content")
                t = c if isinstance(c, str) else "".join(
                    p.get("text", "") for p in c if isinstance(p, dict)
                )
                s = re.search(r"当前是第 (\d+) /", t or "")
                step = f"第 {s.group(1)} 步" if s else ""
                break

        try:
            write_digest(n, body, raw)
        except Exception as e:
            log(f"  [{n:04d}] 摘要生成失败（不影响回复）：{e}")

        log(f"[{n:04d}] ← 收到请求 {step}  → pending/req-{n:04d}.md")
        print(f"        >>> 等回复：把内容写进 done/res-{n:04d}.json <<<")

        # 前缀复用自查：命中率是这套系统最关键的指标（打断 = 只烧钱不出错）
        if prev_len == 0:
            cache_line = "（第一轮，没有可复用的前缀）"
        elif hit == prev_len:
            cache_line = f"前缀复用 {hit}/{prev_len} 条 ✅ 完整"
        else:
            cache_line = f"前缀复用 {hit}/{prev_len} 条 ⚠️ 被打断（第 {hit} 条起分叉）"
        log(f"[{n:04d}]    消息 {len(messages)} 条（上次 {prev_len}）  {cache_line}")

        res_path = os.path.join(DONE, f"res-{n:04d}.json")
        content = None
        t0 = time.time()
        while time.time() - t0 < TIMEOUT:
            if os.path.exists(res_path):
                try:
                    with open(res_path, encoding="utf-8") as f:
                        txt = f.read()
                    try:
                        obj = json.loads(txt)
                        if isinstance(obj, dict) and isinstance(obj.get("content"), str):
                            content = obj["content"]
                        else:
                            content = txt
                    except Exception:
                        content = txt
                    break
                except Exception:
                    pass
            time.sleep(0.15)

        if content is None:
            content = json.dumps({
                "thought": "桥接超时",
                "failed": True,
                "summary": f"等了 {int(TIMEOUT)} 秒没有收到回复（看 .bridge/pending/req-{n:04d}.md）。",
            }, ensure_ascii=False)
            log(f"[{n:04d}] ⏱ 超时，回了一个 failed")

        took = time.time() - t0
        log(f"[{n:04d}] → 已回 {len(content)} 字符（等了 {took:.1f}s）：{content[:70]}")

        payload = {
            "id": f"bridge-{n}",
            "object": "chat.completion",
            "model": body.get("model", "bridge"),
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": "stop",
            }],
            # 给纸盒算「上下文缓存命中率」用。token 数是估的，**比值是真的**（见 prefix_stats）
            "usage": {
                "prompt_tokens": PER_MSG_TOKENS * len(messages),
                "completion_tokens": 0,
                "total_tokens": PER_MSG_TOKENS * len(messages),
                "prompt_cache_hit_tokens": PER_MSG_TOKENS * hit,
                "prompt_cache_miss_tokens": PER_MSG_TOKENS * max(0, len(messages) - hit),
            },
        }
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    ensure_dirs()
    log(f"桥接服务已启动：http://0.0.0.0:{PORT}/v1/chat/completions")
    log(f"目录：{BASE}")
    log(f"纸盒里接口地址填 http://10.0.2.2:{PORT}")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
