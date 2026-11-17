// score.mjs — 12 秒的配乐与音效。120 BPM，24 拍 = 12.000s。
import { DUR, BPM, CUES, b } from './timeline.js';
export { DUR };

export default function score(S) {
  S.tempo(BPM);

  const drums = S.bus('drums', { gain: 0.8 });
  const bass = S.bus('bass', { gain: 0.55, duck: 0.5 });
  const music = S.bus('music', { gain: 0.34, reverb: 0.2, duck: 0.5, highpass: 220 });
  const sfx = S.bus('sfx', { gain: 0.85, reverb: 0.14 });

  const prog = [['A2', 'm9'], ['F2', 'maj7'], ['C3', '6'], ['G2', 'sus4']];
  const bars = 6;

  for (let bar = 0; bar < bars; bar++) {
    const [root, q] = prog[bar % prog.length];
    const t = S.bar(bar);
    const intro = bar === 0;
    const outro = bar === 5;
    const thin = intro || outro;
    const breath = bar === 4;      // 10.0s 那记落版之前留口子

    music.add(
      S.pad(S.chord(S.midi(root) + 12, q), S.bar(1) * (breath ? 0.62 : 0.9),
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
      if (!thin && bar >= 1 && bar <= 3 && k % 2 === 0) {
        const notes = [0, 3, 7, 10];
        music.add(S.pluck(S.midi(root) + 24 + notes[(bar + k) % 4], 0.18, { decay: 0.13 }),
          bt, { gain: 0.2, pan: (k - 1.5) * 0.2 });
      }
    }
  }

  for (const c of CUES) {
    if (c.sfx === 'impact') {
      const end = Math.abs(c.t - b(20)) < 0.01;
      sfx.add(S.impact(), c.t, { gain: end ? 0.95 : 0.82 });
      drums.add(S.crash(), c.t, { gain: end ? 0.45 : 0.26 });
      S.duck(c.t, end ? 1 : 0.6);
    }
    if (c.sfx === 'snap')  sfx.add(S.blip(), c.t, { gain: 0.3, pan: 0.08 });
    if (c.sfx === 'bloom') sfx.add(S.shimmer(0.75), c.t, { gain: 0.28 });
    if (c.sfx === 'shimmer') sfx.add(S.shimmer(1.2), c.t, { gain: 0.4 });
  }
}
