#!/usr/bin/env python3
"""纸盒 60 秒片的声轨：解说（macOS say）+ 一套共用一个房间的合成音效。

    python3 sound.py            # → sfx.wav（48 kHz 立体声，给 render.py --sfx）

解说用 say 合成到 vo/ 下，再按下表摆到时间线上；音效全部来自 sfx_palette 的
五个材料（air / wood / glass / sub / bubble），同一个混响房间。
"""
import os, subprocess, wave
import numpy as np
import sys
sys.path.insert(0, "/tmp/onetake/scripts")
from sfx_palette import Score, air, wood, glass, sub, bubble, wobble, SR

HERE = os.path.dirname(os.path.abspath(__file__))
VO_DIR = os.path.join(HERE, "vo")
DUR = 60.0

# ── 解说词：时间、文本、语速 ────────────────────────────────────────────────
VO = [
    (0.9,  "一个纸箱，能装什么？", 175),
    (5.6,  "打开它。", 175),
    (13.7, "说一句话就行。", 175),
    (20.3, "剩下的它自己来。", 178),
    (24.0, "看屏幕、决定点哪儿、一步一步往下走。", 185),
    (30.2, "每一步都写在屏幕上，随时能停。", 182),
    (33.6, "模型自己选，不想花钱就用免费通道。", 185),
    (42.6, "它只在你手机上跑。", 178),
    (48.8, "装一个 APK，开一次无障碍权限。", 180),
    (56.2, "然后就不用管了。", 175),
]


def say(text, rate, path):
    subprocess.run(["say", "-v", "Tingting", "-r", str(rate), "-o", path[:-4] + ".aiff", text], check=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", path[:-4] + ".aiff",
                    "-ar", str(SR), "-ac", "1", path], check=True)
    os.remove(path[:-4] + ".aiff")
    with wave.open(path, "rb") as w:
        n = w.getnframes()
        return np.frombuffer(w.readframes(n), dtype=np.int16).astype(float) / 32768.0


def main():
    os.makedirs(VO_DIR, exist_ok=True)
    s = Score(dur=DUR, T60=1.25)

    # ── 解说：先合成，量出每句长度（后面音效要躲开它）──────────────────────
    lines = []
    for i, (t, text, rate) in enumerate(VO):
        sig = say(text, rate, os.path.join(VO_DIR, f"l{i:02d}.wav"))
        lines.append((t, sig))
        print(f"  VO {i:02d} @{t:5.1f}s  {len(sig)/SR:4.2f}s  {text}")

    # ── 音效 ────────────────────────────────────────────────────────────────
    # 开场：空气+一点玻璃泛音，箱子静静躺在台面上
    s.place(air(1.6, 90, 700, 1.2, 0.5), 0.15, 0.30, send=0.55)
    s.place(glass(196, 2.4, 0.35), 0.55, 0.10, send=0.7)
    s.place(glass(294, 2.0, 0.25), 1.35, 0.07, send=0.75)

    # 掀盖：纸的撕裂/翻折声 —— 低频 whoosh 起，翻过去那一下一记木响
    s.place(air(1.9, 120, 900, 0.9, 0.35), 5.15, 0.55, pan=-0.2, send=0.45, pan_to=0.35)
    s.place(wood(120, 0.20), 5.28, 0.42, pan=-0.15, send=0.3)
    s.place(air(1.1, 400, 2600, 1.6, 0.6), 6.30, 0.30, pan=0.2, send=0.5)
    s.place(wood(96, 0.26), 7.32, 0.50, send=0.35)
    s.place(sub(52, 0.9), 7.34, 0.34, send=0.25)

    # 下潜：一路往上抽的气
    s.place(air(3.2, 130, 3200, 0.8, 0.72), 9.55, 0.55, pan=0.0, send=0.5, pan_to=-0.1)
    s.place(sub(44, 1.6), 11.6, 0.42, send=0.35)
    s.place(bubble(320, 0.5), 12.75, 0.25, send=0.4)

    # 打字：每个字一记木响（11 个「把 WiFi 关掉」的字）
    for i in range(11):
        t = 14.7 + i / 8.6
        s.place(wood(210 + 18 * (i % 3), 0.055), t, 0.16, pan=-0.1 + 0.02 * i, send=0.15)
    # 发送
    s.place(wood(150, 0.14), 20.10, 0.45, send=0.3)
    # 水波：一记气泡 + 一片空气扫出去
    s.place(bubble(300, 0.7), 20.46, 0.40, send=0.5)
    s.place(air(1.5, 260, 1800, 1.1, 0.3), 20.48, 0.34, pan=-0.3, send=0.55, pan_to=0.4)
    s.place(glass(392, 1.9, 0.4), 20.52, 0.12, send=0.6)

    # 悬浮条升起 + 三条步骤
    s.place(air(0.8, 220, 1200, 1.3, 0.5), 23.35, 0.34, send=0.4)
    s.place(sub(58, 0.8), 23.42, 0.30, send=0.25)
    for i, (t, f) in enumerate([(25.0, 523), (27.3, 587), (29.6, 659)]):
        s.place(glass(f, 0.9, 0.5), t, 0.16, pan=0.25, send=0.5)
    # 字幕/条上的小刻度
    for t in [26.3, 28.6]:
        s.place(wood(300, 0.06), t, 0.10, pan=0.2, send=0.2)

    # 设置页张开：一层玻璃扫过去
    s.place(air(1.2, 900, 300, 1.0, 0.4), 32.15, 0.28, pan=0.1, send=0.55)
    s.place(glass(523, 1.6, 0.5), 32.30, 0.16, send=0.6)
    # 滑杆拖动
    for i in range(7):
        s.place(wood(340 + 6 * i, 0.035), 37.2 + i * 0.31, 0.07, pan=0.3, send=0.15)
    s.place(glass(784, 1.2, 0.5), 39.35, 0.13, send=0.55)

    # 翻页：纸张横扫
    s.place(air(0.85, 1600, 420, 1.5, 0.35), 41.98, 0.40, pan=0.35, send=0.4, pan_to=-0.35)
    s.place(air(0.75, 1400, 520, 1.5, 0.3), 42.45, 0.34, pan=-0.35, send=0.4, pan_to=0.25)
    for i, (t, f) in enumerate([(43.4, 523), (44.55, 622), (45.70, 740)]):
        s.place(glass(f, 1.1, 0.6), t, 0.15, pan=-0.2 + 0.2 * i, send=0.55)
        s.place(wood(260, 0.05), t, 0.09, send=0.15)

    # 折成卡片、掉回箱里
    s.place(air(0.9, 900, 260, 1.3, 0.4), 48.15, 0.30, send=0.45)
    s.place(air(1.2, 420, 140, 1.0, 0.55), 49.35, 0.34, pan=0.0, send=0.4)
    # 合盖：两记，第二记是落定
    s.place(wood(110, 0.22), 50.62, 0.48, send=0.35)
    s.place(sub(50, 1.0), 50.64, 0.30, send=0.25)
    s.place(wood(86, 0.30), 53.08, 0.66, send=0.4)
    s.place(sub(42, 1.5), 53.10, 0.52, send=0.3)
    # 压平：气往下走
    s.place(air(2.0, 700, 90, 0.9, 0.62), 54.40, 0.40, send=0.45)
    s.place(sub(38, 2.0), 55.55, 0.40, send=0.35)
    # 字标：三个音一起落
    for i, f in enumerate([392, 494, 587]):
        s.place(glass(f, 2.6, 0.55), 55.92 + i * 0.07, 0.13, pan=-0.2 + 0.2 * i, send=0.7)

    # ── 配乐床：极轻的 pad，只在没有人声时垫住空气 ─────────────────────────
    def pad(freqs, t0, t1, gain=0.055):
        n = int((t1 - t0) * SR)
        tt = np.arange(n) / SR
        sig = np.zeros(n)
        for k, f in enumerate(freqs):
            sig += np.sin(2 * np.pi * f * tt + k) * (0.8 ** k)
            sig += 0.22 * np.sin(2 * np.pi * f * 2 * tt + 0.7 * k)
        env = np.minimum(1, tt / 1.2) * np.minimum(1, (n / SR - tt) / 1.6)
        return sig * env * gain / len(freqs)

    for t0, t1, ch in [(0.0, 13.0, [98, 147, 196]),
                       (13.0, 32.0, [110, 165, 220]),
                       (32.0, 42.0, [87, 131, 175]),
                       (42.0, 54.0, [98, 147, 196]),
                       (54.0, 60.0, [73, 110, 147])]:
        s.place(pad(ch, t0, t1), t0, 1.0, send=0.6)

    mix = s.mix(peak_db=-9.0)

    # ── 把人声叠上去，并在人声处压住配乐 ────────────────────────────────────
    vo_track = np.zeros((len(mix), 2))
    for t, sig in lines:
        i0 = int(t * SR)
        n = min(len(sig), len(vo_track) - i0)
        if n > 0:
            vo_track[i0:i0 + n, 0] += sig[:n]
            vo_track[i0:i0 + n, 1] += sig[:n]
    # 人声包络（20 ms 窗 + 120 ms 平滑）
    env = np.abs(vo_track[:, 0])
    win = int(0.02 * SR)
    env = np.convolve(env, np.ones(win) / win, mode="same")
    env = np.convolve(env, np.ones(int(0.12 * SR)) / int(0.12 * SR), mode="same")
    duck = 1.0 - 0.62 * np.clip(env / (env.max() + 1e-9) * 1.6, 0, 1)
    out = mix * duck[:, None] + vo_track * 1.05

    # 首尾淡入淡出 + 归一
    n = len(out)
    fi, fo = int(0.5 * SR), int(1.2 * SR)
    out[:fi] *= np.linspace(0, 1, fi)[:, None]
    out[-fo:] *= np.linspace(1, 0, fo)[:, None]
    out = out / (np.abs(out).max() + 1e-9) * 0.70   # 峰值 -3.1 dBFS，给 AAC 留余量

    path = os.path.join(HERE, "sfx.wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(out, -1, 1) * 32767).astype(np.int16).tobytes())
    peak = 20 * np.log10(np.abs(out).max())
    print(f"{path}: {len(s.events)} 个音效事件 + {len(lines)} 句解说，峰值 {peak:.1f} dBFS，时长 {n/SR:.1f}s")


if __name__ == "__main__":
    main()
