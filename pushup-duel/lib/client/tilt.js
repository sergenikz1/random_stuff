// Наклон телефона для голографических наклеек.
// Один глобальный источник пишет CSS-переменные --tilt-x и --tilt-y (от -1 до 1) на <html>,
// и все голограммы на странице переливаются сами, без React-перерисовок.
//
// Источники по приоритету: DeviceOrientation в Telegram (Bot API 8.0+), deviceorientation
// браузера (на iOS — после разрешения), движение указателя, а без них — медленное «дыхание».
import { tg } from './tg.js';

let started = false;
let target = { x: 0, y: 0 };
let current = { x: 0, y: 0 };
let lastInput = 0;
let base = null; // положение телефона в момент старта — от него считаем наклон

const clamp = (v) => Math.max(-1, Math.min(1, v));

function fromAngles(beta, gamma) {
  // beta — наклон вперёд-назад, gamma — влево-вправо (градусы).
  if (!base) base = { beta, gamma };
  target = { x: clamp((gamma - base.gamma) / 25), y: clamp((beta - base.beta) / 25) };
  lastInput = performance.now();
}

function loop(now) {
  // Без датчиков — плавное «дыхание», чтобы голограмма всё равно жила.
  if (now - lastInput > 2500) target = { x: Math.sin(now / 1700) * 0.6, y: Math.cos(now / 2300) * 0.4 };
  current.x += (target.x - current.x) * 0.12;
  current.y += (target.y - current.y) * 0.12;
  const s = document.documentElement.style;
  s.setProperty('--tilt-x', current.x.toFixed(3));
  s.setProperty('--tilt-y', current.y.toFixed(3));
  requestAnimationFrame(loop);
}

export function startTilt() {
  if (started || typeof window === 'undefined') return;
  started = true;
  requestAnimationFrame(loop);

  const t = tg();
  if (t?.initData && t.isVersionAtLeast?.('8.0') && t.DeviceOrientation) {
    try {
      t.DeviceOrientation.start({ refresh_rate: 50 });
      t.onEvent('deviceOrientationChanged', () => {
        const d = t.DeviceOrientation;
        // Telegram отдаёт углы в радианах.
        const k = Math.abs(d.beta) > 7 || Math.abs(d.gamma) > 7 ? 1 : 180 / Math.PI;
        fromAngles(d.beta * k, d.gamma * k);
      });
    } catch { /* старый клиент */ }
  }

  window.addEventListener('deviceorientation', (e) => {
    if (e.beta == null || e.gamma == null) return;
    fromAngles(e.beta, e.gamma);
  });

  window.addEventListener('pointermove', (e) => {
    if (performance.now() - lastInput < 300 && base) return; // датчик важнее мыши
    target = { x: clamp((e.clientX / window.innerWidth) * 2 - 1), y: clamp((e.clientY / window.innerHeight) * 2 - 1) };
    lastInput = performance.now();
  });
}

// iOS Safari просит разрешение на датчики по нажатию пользователя.
export async function askTiltPermission() {
  try {
    const DOE = window.DeviceOrientationEvent;
    if (DOE?.requestPermission) await DOE.requestPermission();
  } catch { /* отказ — останутся мышь и «дыхание» */ }
}
