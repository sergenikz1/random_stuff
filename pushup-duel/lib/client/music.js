// Музыка боя, синтезированная на лету через WebAudio: без аудиофайлов и лицензий.
// Каждый трек — пошаговый секвенсор на 16 шагов с ударными, басом и мелодией.
// В последние 10 секунд боя темп ускоряется и добавляются тарелки (setIntensity(2)).

let ctx = null;
let master = null;
let timer = 0;
let current = null;
let step = 0;
let nextTime = 0;
let intensity = 1;
let noiseBuf = null;

function ac() {
  if (!ctx) {
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return null;
    ctx = new AC();
    master = ctx.createGain();
    master.gain.value = 0.16;
    const comp = ctx.createDynamicsCompressor();
    master.connect(comp).connect(ctx.destination);
    noiseBuf = ctx.createBuffer(1, ctx.sampleRate, ctx.sampleRate);
    const d = noiseBuf.getChannelData(0);
    for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
  }
  if (ctx.state === 'suspended') ctx.resume();
  return ctx;
}

const midi = (n) => 440 * 2 ** ((n - 69) / 12);

// ---- Инструменты ----
function env(g, t, a, peak, dec, sus = 0.0001) {
  g.gain.setValueAtTime(0.0001, t);
  g.gain.exponentialRampToValueAtTime(peak, t + a);
  g.gain.exponentialRampToValueAtTime(Math.max(0.0001, sus), t + a + dec);
}

function kick(t, vol = 1) {
  const o = ctx.createOscillator(), g = ctx.createGain();
  o.frequency.setValueAtTime(150, t);
  o.frequency.exponentialRampToValueAtTime(42, t + 0.12);
  env(g, t, 0.002, 0.9 * vol, 0.22);
  o.connect(g).connect(master); o.start(t); o.stop(t + 0.3);
}

function noise(t, { dur = 0.08, vol = 0.3, hp = 6000, bp = 0 } = {}) {
  const s = ctx.createBufferSource(); s.buffer = noiseBuf;
  const f = ctx.createBiquadFilter();
  f.type = bp ? 'bandpass' : 'highpass'; f.frequency.value = bp || hp;
  const g = ctx.createGain(); env(g, t, 0.001, vol, dur);
  s.connect(f).connect(g).connect(master); s.start(t, Math.random() * 0.5); s.stop(t + dur + 0.05);
}

const hat = (t, vol = 0.12, open = false) => noise(t, { dur: open ? 0.22 : 0.04, vol, hp: 7000 });
function snare(t, vol = 0.45) {
  noise(t, { dur: 0.16, vol, bp: 1800 });
  const o = ctx.createOscillator(), g = ctx.createGain();
  o.type = 'triangle'; o.frequency.setValueAtTime(220, t); o.frequency.exponentialRampToValueAtTime(140, t + 0.08);
  env(g, t, 0.001, vol * 0.6, 0.1);
  o.connect(g).connect(master); o.start(t); o.stop(t + 0.15);
}
function tom(t, pitch = 90, vol = 0.8) {
  const o = ctx.createOscillator(), g = ctx.createGain();
  o.frequency.setValueAtTime(pitch * 1.6, t); o.frequency.exponentialRampToValueAtTime(pitch, t + 0.18);
  env(g, t, 0.003, vol, 0.45);
  o.connect(g).connect(master); o.start(t); o.stop(t + 0.5);
  noise(t, { dur: 0.05, vol: 0.15, bp: 400 });
}

function synth(t, note, dur, { type = 'sawtooth', vol = 0.2, cutoff = 1800, q = 4, detune = 0, attack = 0.005 } = {}) {
  const f = ctx.createBiquadFilter(); f.type = 'lowpass'; f.Q.value = q;
  f.frequency.setValueAtTime(cutoff, t); f.frequency.exponentialRampToValueAtTime(Math.max(120, cutoff * 0.35), t + dur);
  const g = ctx.createGain(); env(g, t, attack, vol, dur * 0.9);
  f.connect(g).connect(master);
  const voices = detune ? [-detune, detune] : [0];
  for (const dt of voices) {
    const o = ctx.createOscillator(); o.type = type; o.frequency.value = midi(note); o.detune.value = dt;
    o.connect(f); o.start(t); o.stop(t + dur + 0.05);
  }
}

// ---- Треки ----
// x — удар, . — пауза. Мелодии — номера MIDI-нот по шагам (null — тишина).
const P = (s) => [...s].map((c) => c !== '.');
const AM = [57, 53, 48, 55]; // Am F C G — корни аккордов по тактам

const TRACKS = {
  pulse: {
    bpm: 124,
    play(t, i, bar) {
      if (i % 4 === 0) kick(t);
      if (i % 4 === 2) hat(t, 0.14);
      if (i % 8 === 4) snare(t, 0.25);
      if (i % 2 === 0) synth(t, AM[bar % 4] - 12 + (i % 4 === 2 ? 12 : 0), 0.18, { type: 'square', vol: 0.12, cutoff: 900 });
    },
  },
  synthwave: {
    bpm: 102,
    play(t, i, bar) {
      if (i === 0 || i === 8) kick(t);
      if (i === 4 || i === 12) snare(t, 0.5);
      if (i % 2 === 1) hat(t, 0.07);
      const root = AM[bar % 4];
      const chord = [0, 3, 7, 12, 7, 3];
      const third = bar % 4 === 0 ? 3 : 4;
      const arp = [0, third, 7, 12][i % 4];
      synth(t, root + 12 + arp, 0.14, { type: 'sawtooth', vol: 0.07, cutoff: 2600, detune: 8 });
      if (i % 2 === 0) synth(t, root - 12, 0.2, { type: 'sawtooth', vol: 0.14, cutoff: 700 });
      if (i === 0) for (const c of chord.slice(0, 3)) synth(t, root + c, 1.9, { type: 'triangle', vol: 0.035, cutoff: 1500, attack: 0.3 });
    },
  },
  chiptune: {
    bpm: 148,
    lead: [69, null, 72, 69, 76, null, 74, 72, 69, null, 67, 69, 72, null, 71, null],
    play(t, i, bar) {
      if (i % 4 === 0) noise(t, { dur: 0.05, vol: 0.35, bp: 120 });
      if (i % 8 === 4) noise(t, { dur: 0.09, vol: 0.25, bp: 2500 });
      if (i % 2 === 1) hat(t, 0.05);
      const shift = [0, -4, -9, -2][bar % 4];
      const n = this.lead[i];
      if (n) synth(t, n + shift, 0.11, { type: 'square', vol: 0.06, cutoff: 5000, q: 1 });
      if (i % 2 === 0) synth(t, AM[bar % 4] - 12 + (i % 4 ? 7 : 0), 0.12, { type: 'triangle', vol: 0.2, cutoff: 3000, q: 1 });
    },
  },
  dnb: {
    bpm: 174,
    k: P('x.........x.....'),
    s: P('....x.......x...'),
    g: P('.......x.x....x.'),
    play(t, i, bar) {
      if (this.k[i]) kick(t);
      if (this.s[i]) snare(t, 0.55);
      if (this.g[i]) snare(t, 0.12);
      hat(t, i % 2 ? 0.04 : 0.08);
      if (i % 8 === 0) synth(t, AM[bar % 4] - 24, 0.8, { type: 'sawtooth', vol: 0.16, cutoff: 500 + (bar % 2) * 400, detune: 18, q: 8 });
      if (i === 14 && bar % 2) synth(t, AM[bar % 4] + 24, 0.1, { type: 'square', vol: 0.05, cutoff: 3000 });
    },
  },
  epic: {
    bpm: 92,
    taiko: P('x..x..x.x...x.x.'),
    play(t, i, bar) {
      if (this.taiko[i]) tom(t, i % 4 === 0 ? 70 : 105, i % 4 === 0 ? 0.9 : 0.55);
      if (i === 8) snare(t, 0.35);
      const root = [50, 46, 53, 48][bar % 4];
      if (i === 0) for (const c of [0, 7, 12, 15]) synth(t, root + c, 1.4, { type: 'sawtooth', vol: 0.045, cutoff: 1300, attack: 0.08, detune: 6 });
      if (i % 2 === 0 && i >= 8) synth(t, root + 24 + [0, 3, 7, 10][(i / 2) % 4], 0.13, { type: 'sawtooth', vol: 0.05, cutoff: 2400 });
      if (i % 4 === 0) synth(t, root - 12, 0.35, { type: 'square', vol: 0.12, cutoff: 600 });
    },
  },
};

function schedule() {
  const tr = TRACKS[current];
  if (!tr) return;
  const bpm = tr.bpm * (intensity > 1 ? 1.08 : 1);
  const stepDur = 60 / bpm / 4;
  while (nextTime < ctx.currentTime + 0.15) {
    const i = step % 16;
    const bar = Math.floor(step / 16);
    tr.play(nextTime, i, bar);
    if (intensity > 1 && i % 8 === 0) noise(nextTime, { dur: 0.35, vol: 0.1, hp: 5000 }); // тарелки на финише
    nextTime += stepDur;
    step += 1;
  }
}

export const music = {
  tracks: Object.keys(TRACKS),
  play(id) {
    if (!TRACKS[id]) { this.stop(); return; }
    if (!ac()) return;
    this.stop();
    current = id; step = 0; intensity = 1;
    nextTime = ctx.currentTime + 0.05;
    timer = setInterval(schedule, 40);
    schedule();
  },
  stop() {
    clearInterval(timer);
    timer = 0;
    current = null;
  },
  setIntensity(n) { intensity = n; },
  get playing() { return current; },
  // Прослушать 6 секунд в коллекции.
  preview(id) {
    this.play(id);
    const mine = id;
    setTimeout(() => { if (current === mine) this.stop(); }, 6000);
  },
};
