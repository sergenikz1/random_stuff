// Кладёт WASM MediaPipe из node_modules в public/mediapipe и пытается скачать модель позы,
// чтобы приложение не зависело от сторонних CDN. Без сети сборка всё равно пройдёт:
// клиент тогда возьмёт модель с серверов Google.
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const src = path.join(root, 'node_modules/@mediapipe/tasks-vision/wasm');
const dst = path.join(root, 'public/mediapipe/wasm');
fs.mkdirSync(dst, { recursive: true });
for (const f of fs.readdirSync(src)) fs.copyFileSync(path.join(src, f), path.join(dst, f));
console.log(`MediaPipe WASM → ${path.relative(root, dst)}`);

const model = path.join(root, 'public/mediapipe/pose_landmarker_lite.task');
if (!fs.existsSync(model)) {
  const url = 'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task';
  try {
    const r = await fetch(url);
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    fs.writeFileSync(model, Buffer.from(await r.arrayBuffer()));
    console.log('Модель позы скачана');
  } catch (e) {
    console.warn(`Модель не скачалась (${e.message}) — клиент возьмёт её с ${new URL(url).host}`);
  }
}
