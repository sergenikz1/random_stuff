// Короткие звуки синтезатором WebAudio — без файлов.
let ctx = null;
let enabled = true;

export function setSound(on) { enabled = on; }

function ac() {
  if (!ctx) {
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return null;
    ctx = new AC();
  }
  if (ctx.state === 'suspended') ctx.resume();
  return ctx;
}

// Вызывается на первом касании: iOS разрешает звук только после жеста.
export function unlockAudio() { if (enabled) ac(); }

function tone(freq, dur, { type = 'sine', vol = 0.18, delay = 0, slide = 0 } = {}) {
  if (!enabled) return;
  const a = ac();
  if (!a) return;
  const t0 = a.currentTime + delay;
  const o = a.createOscillator();
  const g = a.createGain();
  o.type = type;
  o.frequency.setValueAtTime(freq, t0);
  if (slide) o.frequency.exponentialRampToValueAtTime(freq * slide, t0 + dur);
  g.gain.setValueAtTime(0.0001, t0);
  g.gain.exponentialRampToValueAtTime(vol, t0 + 0.01);
  g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
  o.connect(g).connect(a.destination);
  o.start(t0);
  o.stop(t0 + dur + 0.02);
}

export const sfx = {
  rep: (streak = 0) => tone(520 + Math.min(streak, 12) * 45, 0.12, { type: 'triangle', slide: 1.5 }),
  opp: () => tone(260, 0.08, { type: 'sine', vol: 0.07 }),
  tick: () => tone(660, 0.09, { type: 'square', vol: 0.08 }),
  go: () => { tone(880, 0.25, { type: 'square', vol: 0.12 }); tone(1320, 0.3, { type: 'triangle', delay: 0.08 }); },
  combo: () => [0, 0.07, 0.14].forEach((d, i) => tone(660 * (1 + i * 0.26), 0.14, { type: 'triangle', delay: d })),
  win: () => [523, 659, 784, 1046].forEach((f, i) => tone(f, 0.28, { type: 'triangle', delay: i * 0.12 })),
  lose: () => [392, 330, 262].forEach((f, i) => tone(f, 0.3, { type: 'sine', delay: i * 0.16 })),
  level: () => [784, 988, 1175, 1568].forEach((f, i) => tone(f, 0.22, { type: 'square', vol: 0.09, delay: i * 0.09 })),
  whoosh: () => tone(180, 0.7, { type: 'sawtooth', vol: 0.05, slide: 6 }),
  impact: () => { tone(90, 0.5, { type: 'sine', vol: 0.4, slide: 0.4 }); tone(60, 0.6, { type: 'square', vol: 0.08, slide: 0.5 }); tone(1200, 0.25, { type: 'triangle', vol: 0.06, slide: 0.3 }); },
  found: () => { tone(740, 0.15, { type: 'triangle' }); tone(988, 0.25, { type: 'triangle', delay: 0.12 }); },
};
