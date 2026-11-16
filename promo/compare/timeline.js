// timeline.js — 时间轴：画面与声音共用这一份。

export const BPM = 120;
export const BEAT = 60 / BPM;          // 0.5s
export const BAR = BEAT * 4;           // 2.0s
export const b = n => n * BEAT;
export const revealOn = (hit, dur, reveal = 0) => hit - reveal * dur;

export const DUR = b(24);              // 12.000s，整六小节

export const SHOTS = [
  { id: 'hook',   t0: b(0),  t1: b(6) },   // 0.0–3.0    都能操作手机
  { id: 'apk',    t0: b(6),  t1: b(11) },  // 3.0–5.5    安装包
  { id: 'space',  t0: b(11), t1: b(16) },  // 5.5–8.0    占用空间
  { id: 'prompt', t0: b(16), t1: b(20) },  // 8.0–10.0   提示词
  { id: 'end',    t0: b(20), t1: DUR },    // 10.0–12.0  落版
];

export const CUES = [
  { t: b(0.5), sfx: 'impact' },
  { t: b(6),   sfx: 'snap' },
  { t: b(7),   sfx: 'bloom' },
  { t: b(11),  sfx: 'snap' },
  { t: b(12),  sfx: 'bloom' },
  { t: b(16),  sfx: 'snap' },
  { t: b(20),  sfx: 'impact' },
  { t: b(21),  sfx: 'shimmer' },
];

export const HOLDS = [[b(20), DUR]];
export const DROPS = [b(20)];
