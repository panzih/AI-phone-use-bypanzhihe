// timeline.js — 时间轴：画面与声音共用这一份。

export const BPM = 120;
export const BEAT = 60 / BPM;          // 0.5s
export const BAR = BEAT * 4;           // 2.0s
export const b = n => n * BEAT;
export const revealOn = (hit, dur, reveal = 0) => hit - reveal * dur;

export const DUR = b(40);              // 20.000s，整十小节

export const SHOTS = [
  { id: 'hook',   t0: b(0),  t1: b(6) },   // 0.0–3.0   一个问题
  { id: 'apk',    t0: b(6),  t1: b(16) },  // 3.0–8.0   安装包
  { id: 'space',  t0: b(16), t1: b(26) },  // 8.0–13.0  占用空间
  { id: 'prompt', t0: b(26), t1: b(34) },  // 13.0–17.0 提示词
  { id: 'end',    t0: b(34), t1: DUR },    // 17.0–20.0 落版
];

export const CUES = [
  { t: b(0.5), sfx: 'impact' },
  { t: b(6),   sfx: 'snap' },     // 切安装包
  { t: b(10),  sfx: 'bloom' },    // 长条冲出去
  { t: b(16),  sfx: 'snap' },     // 切占用空间
  { t: b(20),  sfx: 'bloom' },
  { t: b(26),  sfx: 'snap' },     // 切提示词
  { t: b(34),  sfx: 'impact' },   // 落版
  { t: b(36),  sfx: 'shimmer' },
];

export const HOLDS = [[b(34), DUR]];
export const DROPS = [b(34)];
