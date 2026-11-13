// timeline.js — 时间轴：画面和声音共用这一份。

export const BPM = 128;
export const BEAT = 60 / BPM;          // 0.46875s
export const BAR = BEAT * 4;           // 1.875s
export const b = n => n * BEAT;
export const revealOn = (hit, dur, reveal = 0) => hit - reveal * dur;

export const DUR = b(32);              // 15.000s，整八小节

export const SHOTS = [
  { id: 'ask',  t0: b(0),  t1: b(4) },   // 0.00–1.88  一句话
  { id: 'open', t0: b(4),  t1: b(8) },   // 1.88–3.75  手机进来
  { id: 'page', t0: b(8),  t1: b(13) },  // 3.75–6.09  下载页
  { id: 'inst', t0: b(13), t1: b(18) },  // 6.09–8.44  安装器
  { id: 'done', t0: b(18), t1: b(24) },  // 8.44–11.25 计算器
  { id: 'say',  t0: b(24), t1: b(28) },  // 11.25–13.13 结论
  { id: 'end',  t0: b(28), t1: DUR },    // 13.13–15.00 落版
];

export const CUES = [
  { t: b(0.5), sfx: 'impact' },          // 开场一个字砸下来
  { t: b(4),   sfx: 'whoosh' },          // 手机进
  { t: b(8),   sfx: 'snap' },            // 切下载页
  { t: b(10),  sfx: 'click' },           // 点下载
  { t: b(13),  sfx: 'snap' },            // 切安装器
  { t: b(15),  sfx: 'click' },           // 点安装
  { t: b(18),  sfx: 'snap' },            // 切计算器
  { t: b(20),  sfx: 'bloom' },           // 装好
  { t: b(24),  sfx: 'impact' },          // 结论
  { t: b(28),  sfx: 'shimmer' },         // 落版
];

export const HOLDS = [[b(28), DUR]];
export const DROPS = [b(24)];
