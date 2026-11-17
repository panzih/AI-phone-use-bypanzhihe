// score.mjs — 28 秒配乐与音效。120 BPM，56 拍 = 28.000s。
// 前两镜给节奏，第三镜（猜坐标）把鼓撤掉留一条低频，第四镜重新进来，收尾一记落点。
import { DUR, BPM, CUES, b } from './timeline.js';
export { DUR };

export default function score(S) {
  S.tempo(BPM);

  const drums = S.bus('drums', { gain: 0.78 });
  const bass = S.bus('bass', { gain: 0.5, duck: 0.5 });
  const music = S.bus('music', { gain: 0.3, reverb: 0.22, duck: 0.5, highpass: 220 });
  const sfx = S.bus('sfx', { gain: 0.85, reverb: 0.16 });

  const prog = [['A2', 'm9'], ['F2', 'maj7'], ['C3', '6'], ['G2', 'sus4']];
  const bars = 14;

  for (let bar = 0; bar < bars; bar++) {
    const t = S.bar(bar);
    const [root, q] = prog[bar % prog.length];

    // 段落：0 引入 / 1–5 做法一 / 6–8 猜坐标（抽稀）/ 9–11 控件树 / 12 边界 / 13 落版
    const intro = bar === 0;
    const guess = bar >= 6 && bar <= 8;
    const edge = bar === 12;
    const outro = bar === 13;
    const thin = intro || edge || outro;
    const breath = bar === 13;

    music.add(
      S.pad(S.chord(S.midi(root) + 12, q), S.bar(1) * (breath ? 0.6 : 0.92),
        { cutoff: guess ? 900 : (thin ? 1000 : 2200) }),
      t,
    );

    for (let k = 0; k < 4; k++) {
      const bt = t + S.beat(k);
      const gap = breath && k >= 1;

      if (!thin && !guess && !gap) {
        drums.add(S.kick({ decay: 0.24 }), bt, { gain: 0.6 });
        S.duck(bt);
      }
      for (let h = 0; h < 2; h++) {
        drums.add(S.hat({ decay: 0.024 }), bt + S.beat(0.5) * h, {
          gain: thin ? 0.08 : (guess ? 0.09 : (gap ? 0.05 : 0.22 - h * 0.06)),
          pan: h ? 0.2 : -0.16,
        });
      }
      if (!thin && !guess && !gap && k === 2) drums.add(S.clap(), bt, { gain: 0.3 });
      if (!thin && !gap) {
        bass.add(S.bass(root, S.beat(0.4), { cutoff: guess ? 300 : 460, sub: 0.3 }),
          bt + S.beat(0.5), { gain: guess ? 0.55 : 0.85 });
      }
      if (!thin && !guess && bar >= 1 && bar <= 4 && k % 2 === 0) {
        const notes = [0, 3, 7, 10];
        music.add(S.pluck(S.midi(root) + 24 + notes[(bar + k) % 4], 0.18, { decay: 0.13 }),
          bt, { gain: 0.2, pan: (k - 1.5) * 0.2 });
      }
    }
  }

  for (const c of CUES) {
    if (c.sfx === 'impact') {
      const end = Math.abs(c.t - b(52)) < 0.01;
      sfx.add(S.impact(), c.t, { gain: end ? 0.95 : 0.8 });
      drums.add(S.crash(), c.t, { gain: end ? 0.45 : 0.24 });
      S.duck(c.t, end ? 1 : 0.6);
    }
    if (c.sfx === 'snap')  sfx.add(S.blip(), c.t, { gain: 0.28, pan: 0.08 });
    if (c.sfx === 'blip')  sfx.add(S.blip(), c.t, { gain: 0.22, pan: -0.1 });
    if (c.sfx === 'tick')  sfx.add(S.blip(), c.t, { gain: 0.14, pan: 0.14 });
    if (c.sfx === 'click') sfx.add(S.blip(), c.t, { gain: 0.3, pan: 0 });
    if (c.sfx === 'shimmer') sfx.add(S.shimmer(1.2), c.t, { gain: 0.4 });
  }
}
