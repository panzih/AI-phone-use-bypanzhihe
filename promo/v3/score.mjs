// score.mjs — 15 秒的配乐与音效，节拍表和画面共用 timeline.js。
// 128 BPM：一拍 0.469s，全片正好 32 拍 = 15.000s。
import { DUR, BPM, CUES, b } from './timeline.js';
export { DUR };

export default function score(S) {
  S.tempo(BPM);

  const drums = S.bus('drums', { gain: 0.85 });
  const bass = S.bus('bass', { gain: 0.62, duck: 0.5 });
  const music = S.bus('music', { gain: 0.40, reverb: 0.26, duck: 0.55, highpass: 190 });
  const sfx = S.bus('sfx', { gain: 0.85, reverb: 0.2 });

  // D 小调：Dm9 – B♭maj7 – F6 – Csus4，机械但不冷
  const prog = [['D3', 'm9'], ['Bb2', 'maj7'], ['F3', '6'], ['C3', 'sus4']];
  const bars = 8;

  for (let bar = 0; bar < bars; bar++) {
    const [root, q] = prog[bar % prog.length];
    const t = S.bar(bar);
    const thin = bar === 0 || bar === 7;

    // 第 6 小节（b20–b24）的最后半拍留口子：pad 收早、鼓与贝斯停掉，
    // 让 11.25s 那记 impact 有落下来的空间
    const breath = bar === 5;
    music.add(
      S.pad(S.chord(S.midi(root) + 12, q), S.bar(1) * (breath ? 0.66 : 0.96),
        { cutoff: thin ? 1100 : 2600 }),
      t,
    );

    for (let k = 0; k < 4; k++) {
      const bt = t + S.beat(k);
      const gap = breath && k === 3;
      if (!thin && bar >= 1 && bar <= 5 && !gap) {
        drums.add(S.kick({ decay: 0.26 }), bt, { gain: 0.72 });
        S.duck(bt);
      }
      for (let h = 0; h < 2; h++) {
        drums.add(S.hat({ decay: 0.028 }), bt + S.beat(0.5) * h, {
          gain: thin ? 0.12 : 0.3 - h * 0.08,
          pan: h ? 0.22 : -0.18,
        });
      }
      if (!thin && !gap && (k === 1 || k === 3)) drums.add(S.clap(), bt, { gain: 0.45 });
      if (!thin && !gap) {
        bass.add(S.bass(root, S.beat(0.42), { cutoff: 480, sub: 0.34 }),
          bt + S.beat(0.5), { gain: 0.9 });
      }
      if (bar >= 2 && bar <= 4 && k % 2 === 0) {
        const notes = [0, 3, 7, 10];
        music.add(S.pluck(S.midi(root) + 24 + notes[(bar + k) % 4], 0.22, { decay: 0.18 }),
          bt, { gain: 0.30, pan: (k - 1.5) * 0.25 });
      }
    }
  }

  for (const c of CUES) {
    if (c.sfx === 'impact') {
      // 结论那一记比开场更重：全片唯一一次满增益的 impact + crash
      const big = Math.abs(c.t - b(24)) < 0.01;
      sfx.add(S.impact(), c.t, { gain: big ? 1.0 : (c.t < 1 ? 0.85 : 0.72) });
      drums.add(S.crash(), c.t, { gain: big ? 0.52 : 0.3 });
      S.duck(c.t, c.t < 1 ? 0.6 : 1);
    }
    if (c.sfx === 'whoosh') sfx.add(S.whoosh(0.55), c.t - 0.38, { gain: 0.62 });
    if (c.sfx === 'snap') sfx.add(S.blip(), c.t, { gain: 0.30, pan: 0.1 });
    if (c.sfx === 'click') sfx.add(S.click(), c.t, { gain: 0.4, pan: -0.05 });
    if (c.sfx === 'bloom') sfx.add(S.shimmer(0.9), c.t, { gain: 0.32 });
    if (c.sfx === 'shimmer') sfx.add(S.shimmer(1.5), c.t, { gain: 0.42 });
  }

  sfx.add(S.riser(S.bar(1) * 0.75), b(24) - S.bar(1) * 0.75, { gain: 0.42 });
}
