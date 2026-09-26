#!/usr/bin/env python3
"""
检查上下文缓存前缀有没有被破坏。

「前缀复用」是这套系统的省钱命脉：服务端按**最长公共前缀**复用已算过的内容，
重复部分单价只有新内容的 1/50。所以每一次请求的消息数组，必须是上一次的
**严格前缀 + 新追加**；只要中间有一条消息被改写，从那条起全部按未命中计价，
而表现只是"命中率掉下去"——不报错。

这个脚本把桥接目录里存下来的原始请求体逐对比一遍，把这件事变成可验证的。

    python3 tools/check_prefix.py [目录，默认 .bridge/pending]

判据（按重要性）：
  1. system 提示词逐字节相同 —— 它是前缀的第 0 个 token，一变全废
  2. 上一次的消息数组是这一次的前缀（逐条深比较，含图片 base64）
  3. 消息数只增不减
  4. 前缀里出现的图片字节没被换掉（换过 = 同一条消息内容变了 = 前缀断）

⚠️ **「上下文重开」不是失败。** 换模型 / 提示词版本变了 / 策略过期 / 用户手动清空
都会让上下文从头开始，这时前缀当然是 0——但那是**有意**的，而且重开之后
前缀必须重新稳定下来。所以脚本把这两种情况分开报：

    重开（OK）  消息数掉回很小（≤ REOPEN_MAX_MSGS）且从第 0/1 条就分叉
    断链（BAD） 消息数还是很大，却在中间某条分叉 —— 这才是真的把历史改坏了
"""

import json
import os
import sys

# 消息数掉到这个值以下、且从开头就分叉 → 判定为「有意重开」而不是断链
REOPEN_MAX_MSGS = 4

import json
import os
import sys


def msg_key(m):
    """把一条消息压成可比较的规范形式（深层排序键，图片按内容比）。"""
    return json.dumps(m, ensure_ascii=False, sort_keys=True)


def role_of(m):
    return m.get("role", "?")


def preview(m):
    c = m.get("content")
    if isinstance(c, str):
        t = c
    else:
        parts = []
        for p in c if isinstance(c, list) else []:
            if not isinstance(p, dict):
                continue
            if p.get("type") == "image_url":
                u = p.get("url", "")
                parts.append(f"[图 {len(u)}B base64]")
            else:
                parts.append(p.get("text", ""))
        t = "".join(parts)
    t = " ".join(t.split())
    return t[:88]


def load_all(folder):
    files = sorted(
        f for f in os.listdir(folder)
        if f.startswith("req-") and f.endswith(".json")
    )
    out = []
    for f in files:
        try:
            with open(os.path.join(folder, f), encoding="utf-8") as fh:
                body = json.load(fh)
            out.append((f, body.get("messages", [])))
        except Exception as e:
            print(f"  !! {f} 解析失败：{e}")
    return out


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else ".bridge/pending"
    reqs = load_all(folder)
    if len(reqs) < 2:
        print(f"{folder} 里只有 {len(reqs)} 份载荷，至少要两份才能比")
        return 1

    print(f"共 {len(reqs)} 份请求载荷，目录 {folder}\n")

    # ---- 1) system 提示词是否恒定 ----
    systems = {msg_key(m) for _, msgs in reqs for m in msgs if role_of(m) == "system"}
    n_sys = sum(1 for _, msgs in reqs for m in msgs if role_of(m) == "system")
    print("── system 提示词 ──")
    if len(systems) == 1:
        print(f"  ✅ 全部 {n_sys} 处逐字节相同（前缀的第 0 个 token 稳）")
    else:
        # 多种 system **可能**是正常的（换模型会重开上下文），所以这里只提示，
        # 真正的判据是下面逐对检查里"同一段上下文内 system 有没有变"。
        print(f"  ℹ️  有 {len(systems)} 种不同的 system 提示词（共 {n_sys} 处）")
        print("     换模型 / 提示词版本变化会**有意**重开上下文，这时它会变 —— 不算问题；")
        print("     要看的是「同一段上下文内部」它有没有变，下面逐对检查会给出结论。")
    print()

    # ---- 2) 逐对比前缀 ----
    print("── 逐对前缀检查 ──")
    bad = 0
    reopened = 0
    for i in range(1, len(reqs)):
        (pf, prev), (cf, cur) = reqs[i - 1], reqs[i]
        n = 0
        for a, b in zip(prev, cur):
            if msg_key(a) == msg_key(b):
                n += 1
            else:
                break
        grew = len(cur) >= len(prev)
        if n == len(prev) and grew:
            print(f"  ✅ {pf} → {cf}：前缀 {n}/{len(prev)} 条，本次共 {len(cur)} 条")
            continue

        # 掉回很小的消息数 + 从开头就分叉 → 有意重开
        if len(cur) <= REOPEN_MAX_MSGS and n == 0:
            reopened += 1
            why = "system 提示词变了" if n == 0 and (
                role_of(prev[0]) == "system" and role_of(cur[0]) == "system"
                and msg_key(prev[0]) != msg_key(cur[0])
            ) else "上下文被重开"
            print(f"  🔄 {pf} → {cf}：上下文重开（{why}），{len(prev)} 条 → {len(cur)} 条")
            continue

        bad += 1
        if n < len(prev):
            a = prev[n]
            b = cur[n] if n < len(cur) else None
            detail = (
                f"\n        第 {n} 条分叉：\n"
                f"          上次 {role_of(a)}: {preview(a)}\n"
                f"          这次 " + (f"{role_of(b)}: {preview(b)}" if b else "（这次没这条）")
            )
        else:
            detail = "\n        这次的消息数比上次少"
        print(f"  ❌ {pf} → {cf}：前缀 {n}/{len(prev)} 条，本次共 {len(cur)} 条{detail}")

    print()
    print("── 结论 ──")
    if bad == 0:
        print(f"  ✅ 没有断链。{len(reqs) - 1} 处相邻请求里：")
        print(f"       {len(reqs) - 1 - reopened} 处前缀完整、{reopened} 处是有意重开。")
        if len(systems) > 1:
            print(f"  ℹ️   system 提示词有 {len(systems)} 种 —— 对应 {reopened} 次重开，符合预期；")
            print("       只要**同一段上下文内**它没变，缓存就不会受影响。")
    else:
        print(f"  ❌ 有 {bad} 处**断链**（消息数没变小却在中间分叉）—— 这才是把历史改坏了。")
        print(f"     另：有意重开 {reopened} 处，system 提示词 {len(systems)} 种。")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
