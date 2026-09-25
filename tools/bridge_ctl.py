#!/usr/bin/env python3
"""
桥接 LLM 的配套工具 —— 查待回复的请求、把回复写回去。

配合 tools/bridge_llm.py 用。三个动作：

    python3 tools/bridge_ctl.py next
        打印**最早一条还没回复**的请求摘要（就是 pending/req-XXXX.md 的内容），
        并在最后一行给出它的编号。没待办时打印 `NONE`。

    python3 tools/bridge_ctl.py reply <编号> '<JSON>'
        把回复写进 done/res-XXXX.json。传 '-' 表示从 stdin 读。
        会先校验 JSON 能不能解析（模型输出必须是合法 JSON，提前拦住）。

    python3 tools/bridge_ctl.py status
        一眼看全部：收到几条、回了几条、有没有卡住的。

JSON 里不用自己包 {"content": ...} 那层，这个工具会包。
"""

import json
import os
import sys

BASE = os.environ.get("BRIDGE_DIR", os.path.expanduser("~/DSH/AI手机/.bridge"))
PENDING = os.path.join(BASE, "pending")
DONE = os.path.join(BASE, "done")


def seqs():
    """pending 里的请求编号（升序）。"""
    if not os.path.isdir(PENDING):
        return []
    out = []
    for f in os.listdir(PENDING):
        m = f
        if m.startswith("req-") and m.endswith(".json"):
            try:
                out.append(int(m[4:-5]))
            except ValueError:
                pass
    return sorted(out)


def replied():
    if not os.path.isdir(DONE):
        return set()
    out = set()
    for f in os.listdir(DONE):
        if f.startswith("res-") and f.endswith(".json"):
            try:
                out.add(int(f[4:-5]))
            except ValueError:
                pass
    return out


def cmd_next():
    done = replied()
    todo = [n for n in seqs() if n not in done]
    if not todo:
        print("NONE")
        return 0
    n = todo[0]
    md = os.path.join(PENDING, f"req-{n:04d}.md")
    if os.path.exists(md):
        sys.stdout.write(open(md, encoding="utf-8").read())
    else:
        print(f"（没有摘要文件，直接看 req-{n:04d}.json）")
    print(f"\n--- 编号 {n} ---")
    others = [x for x in todo[1:]]
    if others:
        print(f"（后面还排着：{others}）")
    return 0


def cmd_reply(args):
    if len(args) < 2:
        print("用法：reply <编号> '<JSON>'   或   reply <编号> -", file=sys.stderr)
        return 2
    try:
        n = int(args[0])
    except ValueError:
        print(f"编号必须是数字，给了 {args[0]!r}", file=sys.stderr)
        return 2
    raw = sys.stdin.read() if args[1] == "-" else args[1]
    raw = raw.strip()
    if raw.startswith("```"):
        # 模型有时候会带 markdown 围栏，替它剥掉
        raw = raw.strip("`")
        if raw.lower().startswith("json"):
            raw = raw[4:]
        raw = raw.strip()
    try:
        json.loads(raw)
    except Exception as e:
        print(f"❌ 这不是合法 JSON，没写回去：{e}", file=sys.stderr)
        print("   纸盒那边会一直等，所以宁可在这里拦住。", file=sys.stderr)
        return 1
    os.makedirs(DONE, exist_ok=True)
    path = os.path.join(DONE, f"res-{n:04d}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"content": raw}, f, ensure_ascii=False)
    print(f"✅ 已回 {n:04d}（{len(raw)} 字符）")
    return 0


def cmd_status():
    s = seqs()
    d = replied()
    todo = [n for n in s if n not in d]
    print(f"目录      {BASE}")
    print(f"收到请求  {len(s)} 条" + (f"（{s[0]:04d}~{s[-1]:04d}）" if s else ""))
    print(f"已回复    {len([n for n in s if n in d])} 条")
    print(f"待回复    {todo if todo else '无'}")
    return 0


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "next":
        return cmd_next()
    if cmd == "reply":
        return cmd_reply(args)
    if cmd == "status":
        return cmd_status()
    print(f"不认识的动作：{cmd}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
