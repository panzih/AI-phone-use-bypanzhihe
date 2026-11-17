// timeline.js — 时间轴：画面与声音共用这一份。

export const BPM = 120;
export const BEAT = 60 / BPM;          // 0.5s
export const BAR = BEAT * 4;           // 2.0s
export const b = n => n * BEAT;
export const revealOn = (hit, dur, reveal = 0) => hit - reveal * dur;

export const DUR = b(40);              // 20.000s，整十小节

export const SHOTS = [
  { id: 'hook',  t0: b(0),  t1: b(6) },   // 0.0–3.0   它看见了什么
  { id: 'snap',  t0: b(6),  t1: b(14) },  // 3.0–7.0   做法一：发截图
  { id: 'guess', t0: b(14), t1: b(22) },  // 7.0–11.0  猜坐标
  { id: 'tree',  t0: b(22), t1: b(30) },  // 11.0–15.0 做法二：控件树
  { id: 'edge',  t0: b(30), t1: b(34) },  // 15.0–17.0 说不清的屏
  { id: 'end',   t0: b(34), t1: DUR },    // 17.0–20.0 收尾
];

export const CUES = [
  { t: b(0.5),  sfx: 'impact' },
  { t: b(6),    sfx: 'snap' },      // 切到截图
  { t: b(10),   sfx: 'blip' },      // 字符带铺满
  { t: b(15),   sfx: 'click' },     // 点空
  { t: b(16.2), sfx: 'blip' },
  { t: b(22),   sfx: 'snap' },      // 切到控件树
  { t: b(23),   sfx: 'tick' },
  { t: b(23.8), sfx: 'tick' },
  { t: b(24.6), sfx: 'tick' },
  { t: b(30),   sfx: 'blip' },      // 切到边界
  { t: b(34),   sfx: 'impact' },    // 收尾
  { t: b(35),   sfx: 'shimmer' },
];

export const HOLDS = [[b(34), DUR]];
export const DROPS = [b(34)];
