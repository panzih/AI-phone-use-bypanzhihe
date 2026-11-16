// score.mjs — 20 秒的配乐与音效，节拍表和画面共用 timeline.js。
// 120 BPM：一拍 0.5s，全片 40 拍 = 20.000s。
import { DUR, BPM, CUES, b } from './timeline.js';
export { DUR };

export default function score(S) {
  S.tempo(BPM);

  const drums = S.bus('drums', { gain: 0.8 });
  const bass = S.bus('bass', { gain: 0.55, duck: 0.5 });
  const music = S.bus('music', { gain: 0.34, reverb: 0.22, duck: 0.5, highpass: 220 });
  const sfx = S.bus('sfx', { gain: 0.85, reverb: 0.16 });

  // 三个对比段用同一组和声，靠疏密区分：A 小调，干净、不煽情
  const prog = [['A2', 'm9'], ['F2', 'maj7'], ['C3', '6'], ['G2', 'sus4']];
  const bars = 10;

  for (let bar = 0; bar < bars; bar++) {
    const [root, q] = prog[bar % prog.length];
    const t = S.bar(bar);
    const intro = bar === 0;          // 开场只留 pad
    const outro = bar === 9;          // 落版
    const thin = intro || outro;
    // 第 9 小节（16–18s）后半段留口子，让 17.0s 那记 impact 有落下来的空间
    const breath = bar === 8;

    music.add(
      S.pad(S.chord(S.midi(root) + 12, q), S.bar(1) * (breath ? 0.62 : 0.94),
        { cutoff: thin ? 1000 : 2300 }),
      t,
    );

    for (let k = 0; k < 4; k++) {
      const bt = t + S.beat(k);
      const gap = breath && k >= 1;
      if (!thin && !gap) {
        drums.add(S.kick({ decay: 0.24 }), bt, { gain: 0.6 });
        S.duck(bt);
      }
      for (let h = 0; h < 2; h++) {
        drums.add(S.hat({ decay: 0.024 }), bt + S.beat(0.5) * h, {
          gain: thin ? 0.10 : (gap ? 0.05 : 0.22 - h * 0.06),
          pan: h ? 0.2 : -0.16,
        });
      }
      if (!thin && !gap && k === 2) drums.add(S.clap(), bt, { gain: 0.32 });
      if (!thin && !gap) {
        bass.add(S.bass(root, S.beat(0.4), { cutoff: 460, sub: 0.3 }),
          bt + S.beat(0.5), { gain: 0.85 });
      }
      // 数字段加一层很轻的拨弦，4 个音一组往上走，配合"数字在跳"
      if (!thin && bar >= 1 && bar <= 7 && k % 2 === 0) {
        const notes = [0, 3, 7, 10];
        music.add(S.pluck(S.midi(root) + 24 + notes[(bar + k) % 4], 0.2, { decay: 0.15 }),
          bt, { gain: 0.22, pan: (k - 1.5) * 0.2 });
      }
    }
  }

  for (const c of CUES) {
    if (c.sfx === 'impact') {
      const end = Math.abs(c.t - b(34)) < 0.01;
      sfx.add(S.impact(), c.t, { gain: end ? 0.95 : 0.8 });
      drums.add(S.crash(), c.t, { gain: end ? 0.45 : 0.26 });
      S.duck(c.t, end ? 1 : 0.6);
    }
    if (c.sfx === 'snap')  sfx.add(S.blip(), c.t, { gain: 0.3, pan: 0.08 });
    if (c.sfx === 'bloom') sfx.add(S.shimmer(0.85), c.t, { gain: 0.3 });
    if (c.sfx === 'shimmer') sfx.add(S.shimmer(1.4), c.t, { gain: 0.4 });
  }
}
