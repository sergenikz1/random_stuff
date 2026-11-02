// Счётчик на точках скелета из реального видео дуэли (528 кадров, 15 кадров/с).
// На видео приложение-оригинал насчитало 34 повтора; в нём есть вставки-мемы,
// на которых тоже находится «скелет», поэтому допускаем ±2.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { RepCounter } from '../lib/counter.js';

const frames = JSON.parse(fs.readFileSync(new URL('./fixtures/pushups-video.json', import.meta.url)));
const toLandmarks = (f) => {
  if (f.length === 1) return null;
  const lm = new Array(33).fill(null).map(() => ({ x: 0, y: 0, visibility: 0 }));
  [11, 12, 13, 14, 15, 16].forEach((idx, k) => {
    lm[idx] = { x: f[1 + k * 3], y: f[2 + k * 3], visibility: f[3 + k * 3] };
  });
  return lm;
};

const run = (strictness) => {
  const c = new RepCounter({ strictness });
  for (const f of frames) c.update(toLandmarks(f), f[0]);
  return c.count;
};

test('обычная строгость: столько же, сколько на видео', () => {
  const n = run('normal');
  assert.ok(n >= 32 && n <= 36, `насчитано ${n}`);
});

test('строгий режим считает не больше обычного', () => {
  assert.ok(run('strict') <= run('normal'));
});

test('неподвижный человек — ноль повторов', () => {
  const c = new RepCounter();
  const still = toLandmarks(frames.find((f) => f.length > 1));
  for (let t = 0; t < 10000; t += 33) {
    const jitter = still.map((p) => ({ ...p, y: p.y + (Math.random() - 0.5) * 0.01 }));
    c.update(jitter, t);
  }
  assert.equal(c.count, 0);
});
