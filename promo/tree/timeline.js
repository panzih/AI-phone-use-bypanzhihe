// timeline.js — 时间轴：画面与声音共用这一份。

export const BPM = 120;
export const BEAT = 60 / BPM;          // 0.5s
export const BAR = BEAT * 4;           // 2.0s
export const b = n => n * BEAT;
export const revealOn = (hit, dur, reveal = 0) => hit - reveal * dur;

export const DUR = b(56);              // 28.000s，整十四小节

export const SHOTS = [
  { id: 'hook',  t0: b(0),  t1: b(8) },   // 0.0–4.0   它看见了什么
  { id: 'snap',  t0: b(8),  t1: b(22) },  // 4.0–11.0  做法一：发截图
  { id: 'guess', t0: b(22), t1: b(32) },  // 11.0–16.0 猜坐标
  { id: 'tree',  t0: b(32), t1: b(46) },  // 16.0–23.0 做法二：控件树
  { id: 'edge',  t0: b(46), t1: b(52) },  // 23.0–26.0 说不清的屏
  { id: 'end',   t0: b(52), t1: DUR },    // 26.0–28.0 收尾
];

export const CUES = [
  { t: b(0.6),  sfx: 'impact' },
  { t: b(8),    sfx: 'snap' },      // 切到截图
  { t: b(18),   sfx: 'blip' },      // 字符带滚出
  { t: b(25),   sfx: 'click' },     // 点空
  { t: b(26.6), sfx: 'blip' },      // 落空第二声
  { t: b(32),   sfx: 'snap' },      // 切到控件树
  { t: b(34),   sfx: 'tick' },
  { t: b(35.5), sfx: 'tick' },
  { t: b(37),   sfx: 'tick' },
  { t: b(46),   sfx: 'blip' },      // 切到边界
  { t: b(52),   sfx: 'impact' },    // 收尾
  { t: b(53),   sfx: 'shimmer' },
];

export const HOLDS = [[b(52), DUR]];
export const DROPS = [b(52)];
