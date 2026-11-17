// timeline.js — 时间轴：画面与声音共用这一份。
//
// 卡点规则（改这里的数之前先读）：
//   120 BPM → 一拍 0.5s、半拍 0.25s、一小节 2.0s。
//   1. 所有切点必须落在**小节线**上（t 是 2.0 的整数倍）
//   2. 镜头内每个元素出现的时刻必须是 **0.25 的整数倍**
//   3. 元素出现的动画时长固定 0.12s —— 短到像"啪"地一下落上去，才叫卡点
//   4. 每次出现都要在 CUES 里配一个音效，画面和声音同时发生

export const BPM = 120;
export const BEAT = 60 / BPM;          // 0.5s
export const BAR = BEAT * 4;           // 2.0s
export const b = n => n * BEAT;
export const revealOn = (hit, dur, reveal = 0) => hit - reveal * dur;

export const DUR = b(40);              // 20.000s，整十小节

export const SHOTS = [
  { id: 'hook',  t0: b(0),  t1: b(4) },   // 0–2   它看见了什么
  { id: 'snap',  t0: b(4),  t1: b(12) },  // 2–6   做法一：发截图
  { id: 'guess', t0: b(12), t1: b(20) },  // 6–10  猜坐标
  { id: 'tree',  t0: b(20), t1: b(28) },  // 10–14 做法二：控件树
  { id: 'edge',  t0: b(28), t1: b(32) },  // 14–16 说不清的屏
  { id: 'end',   t0: b(32), t1: DUR },    // 16–20 收尾
];

// 每一条都对应画面里某个元素"落上去"的那一下
export const CUES = [
  { t: b(0.5),  sfx: 'impact' },   // 第一行标题
  { t: b(1.5),  sfx: 'blip' },     // 第二行标题
  { t: b(2),    sfx: 'snap' },     // 切镜
  { t: b(5),    sfx: 'tick' },     // 手机和文本
  { t: b(6),    sfx: 'blip' },     // 689 KB
  { t: b(7),    sfx: 'blip' },     // 6–20 MB
  { t: b(12),   sfx: 'snap' },     // 切镜
  { t: b(13),   sfx: 'tick' },     // 放大图
  { t: b(13.5), sfx: 'tick' },     // 虚线框
  { t: b(15),   sfx: 'click' },    // 红点落空
  { t: b(16.5), sfx: 'blip' },     // 猜偏 20px
  { t: b(20),   sfx: 'snap' },     // 切镜
  { t: b(21),   sfx: 'tick' },     // 第 1 行
  { t: b(21.5), sfx: 'tick' },     // 第 2 行
  { t: b(22),   sfx: 'tick' },     // 第 3 行
  { t: b(22.5), sfx: 'tick' },     // 第 4 行
  { t: b(23),   sfx: 'tick' },     // 第 5 行
  { t: b(23.5), sfx: 'blip' },     // 922 字
  { t: b(25),   sfx: 'blip' },     // 同一屏 689 KB
  { t: b(25.5), sfx: 'blip' },     // 三十步 30 KB
  { t: b(26.5), sfx: 'blip' },     // 点 [7]
  { t: b(28),   sfx: 'blip' },     // 切到边界
  { t: b(29),   sfx: 'tick' },     // 占位文本
  { t: b(32),   sfx: 'impact' },   // 落版
  { t: b(34),   sfx: 'shimmer' },
];

export const HOLDS = [[b(32), DUR]];
export const DROPS = [b(32)];
