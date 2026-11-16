#!/usr/bin/env python3
"""生成纸盒宣传片 v2 的时间线：把解说词逐段合成语音，按语音长度排场景时间。

用法：python3 make_timeline.py
产物：
  audio/sN.aiff     每段解说（macOS say，Tingting 音色）
  timeline.json     场景起止时间（渲染器 render2.swift 直接读）
输出到终端一份对照表，方便核对每段节奏。
"""
import json
import os
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
AUD = os.path.join(HERE, "audio")
VOICE = "Tingting"
RATE = 190

# (id, 场景名, 解说词, 前奏留白, 收尾留白)
SEGS = [
    ("s1", "开场", "手机里有一半的事，本来不该你亲手点。", 1.6, 1.4),
    ("s2", "痛点", "关 Wi-Fi、清缓存、把一段话复制出来——这些动作你重复过太多遍，每次还得自己盯着屏幕，一下一下点过去。", 0.5, 1.4),
    ("s3", "它怎么做", "纸盒换了个做法。你说一句话，它自己看屏幕、自己决定点哪里，一步一步做完。不用 root，也不用连着电脑。", 0.5, 1.2),
    ("s4", "主界面", "打开就是这样一个界面：一个输入框，一个发送键。", 0.5, 1.0),
    ("s5", "跑起来", "按下去，它就开始干活。屏幕下方那条写着它在干嘛，然后它自己打开了设置——你一根手指都没碰。", 0.5, 1.0),
    ("s6", "模型", "模型自己选。填自己的 Key，接 DeepSeek、OpenRouter 或者本机 Ollama 都行；不想花钱，就用内置的免费通道，开箱就能跑。", 0.5, 1.0),
    ("s7", "安全", "它只在你手机上跑。Key 加密存在系统 Keystore 里，没有广告，没有统计，每一步都留下日志。", 0.5, 1.0),
    ("s8", "三步", "上手只要三步：装一个 APK，开一次无障碍权限，然后说一句话。", 0.5, 1.0),
    ("s9", "收尾", "不绑定厂商，不限定机型。装一个 APK，开一次无障碍权限——然后就不用管了。", 0.8, 3.0),
]


def dur_of(path):
    out = subprocess.run(["afinfo", path], capture_output=True, text=True).stdout
    for line in out.splitlines():
        if "estimated duration" in line:
            return float(line.split(":")[1].strip().split(" ")[0])
    raise RuntimeError("读不到时长: " + path)


def main():
    os.makedirs(AUD, exist_ok=True)
    scenes, t = [], 0.0
    for sid, name, text, lead, tail in SEGS:
        wav = os.path.join(AUD, f"{sid}.aiff")
        subprocess.run(["say", "-v", VOICE, "-r", str(RATE), "-o", wav, text], check=True)
        d = dur_of(wav)
        start = round(t, 2)
        end = round(start + lead + d + tail, 2)
        scenes.append({
            "id": sid, "name": name, "start": start, "end": end,
            "audio": round(start + lead, 2), "audioDur": round(d, 2), "text": text,
        })
        t = end
    total = round(t, 2)
    with open(os.path.join(HERE, "timeline.json"), "w") as f:
        json.dump({"total": total, "scenes": scenes}, f, ensure_ascii=False, indent=2)

    print(f"{'场景':<10}{'起':>7}{'止':>7}{'时长':>7}{'解说起':>8}{'解说长':>8}")
    for s in scenes:
        print(f"{s['id']+' '+s['name']:<10}{s['start']:>7.2f}{s['end']:>7.2f}"
              f"{s['end']-s['start']:>7.2f}{s['audio']:>8.2f}{s['audioDur']:>8.2f}")
    print(f"总时长 {total}s")


if __name__ == "__main__":
    main()
