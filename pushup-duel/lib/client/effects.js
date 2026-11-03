// Визуальные эффекты на canvas: стили скелета, всплески на повтор и праздник победы.
// Работает в CSS-пикселях; canvas сам подстраивается под devicePixelRatio.

import { drawSkin as paintSkin } from './skins.js';

const SEGMENTS = [['ls', 'rs'], ['ls', 'le'], ['le', 'lw'], ['rs', 're'], ['re', 'rw']];
const JOINTS = ['ls', 'rs', 'le', 're', 'lw', 'rw'];
const rand = (a, b) => a + Math.random() * (b - a);
const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];
const CONFETTI = ['#ff4d6d', '#ffd23f', '#43a8ff', '#6fe08a', '#b49bff', '#ff9f43', '#5ff6ff'];

export function createFx(canvas) {
  const ctx = canvas.getContext('2d');
  let w = 0, h = 0, dpr = 1;
  const parts = [];
  const echoes = [];

  function resize() {
    dpr = Math.min(2, window.devicePixelRatio || 1);
    w = canvas.clientWidth; h = canvas.clientHeight;
    canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  resize();

  function clear() { ctx.clearRect(0, 0, w, h); }

  function line(a, b, color, width, blur = 0, glow = color) {
    ctx.save();
    ctx.strokeStyle = color; ctx.lineWidth = width; ctx.lineCap = 'round';
    ctx.shadowBlur = blur; ctx.shadowColor = glow;
    ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
    ctx.restore();
  }

  function dot(p, r, fill, stroke, blur = 0, glow = fill) {
    ctx.save();
    ctx.shadowBlur = blur; ctx.shadowColor = glow;
    ctx.fillStyle = fill; ctx.beginPath(); ctx.arc(p.x, p.y, r, 0, Math.PI * 2); ctx.fill();
    if (stroke) { ctx.lineWidth = 2; ctx.strokeStyle = stroke; ctx.stroke(); }
    ctx.restore();
  }

  function jagged(a, b, amp) {
    const pts = [a];
    const n = 6;
    const dx = b.x - a.x, dy = b.y - a.y;
    const len = Math.hypot(dx, dy) || 1;
    const nx = -dy / len, ny = dx / len;
    for (let i = 1; i < n; i++) {
      const k = i / n;
      const off = rand(-amp, amp);
      pts.push({ x: a.x + dx * k + nx * off, y: a.y + dy * k + ny * off });
    }
    pts.push(b);
    return pts;
  }

  function poly(pts, color, width, blur, glow) {
    ctx.save();
    ctx.strokeStyle = color; ctx.lineWidth = width; ctx.lineJoin = 'round'; ctx.lineCap = 'round';
    ctx.shadowBlur = blur; ctx.shadowColor = glow;
    ctx.beginPath(); ctx.moveTo(pts[0].x, pts[0].y);
    for (const p of pts.slice(1)) ctx.lineTo(p.x, p.y);
    ctx.stroke(); ctx.restore();
  }

  // pose: { ls, rs, le, re, lw, rw, nose?, leye?, reye? } — точки в CSS-пикселях (или null)
  function drawPose(pose, trail = 'classic', now = performance.now()) {
    if (!pose) { echoes.length = 0; return; }
    const segs = SEGMENTS.filter(([a, b]) => pose[a] && pose[b]);
    const face = ['leye', 'nose', 'reye'].map((k) => pose[k]).filter(Boolean);
    const t = now / 1000;

    if (trail === 'shadow') {
      echoes.unshift(JSON.parse(JSON.stringify(pose)));
      if (echoes.length > 7) echoes.length = 7;
      echoes.forEach((e, i) => {
        if (i === 0) return;
        ctx.globalAlpha = 0.35 * (1 - i / 7);
        for (const [a, b] of SEGMENTS) if (e[a] && e[b]) line(e[a], e[b], '#7b2cff', 10, 18, '#3b0a8f');
      });
      ctx.globalAlpha = 1;
      for (const [a, b] of segs) { line(pose[a], pose[b], '#1a0636', 8, 22, '#a35cff'); line(pose[a], pose[b], '#c9a6ff', 2); }
      for (const j of JOINTS) if (pose[j]) dot(pose[j], 6, '#1a0636', '#c9a6ff', 14, '#a35cff');
    } else if (trail === 'neon') {
      for (const [a, b] of segs) { line(pose[a], pose[b], '#5ff6ff', 6, 24, '#5ff6ff'); line(pose[a], pose[b], '#e8ffff', 2); }
      for (const j of JOINTS) if (pose[j]) dot(pose[j], 6, '#ff5cf0', '#ffffff', 18, '#ff5cf0');
    } else if (trail === 'fire') {
      for (const [a, b] of segs) {
        const g = ctx.createLinearGradient(pose[a].x, pose[a].y, pose[b].x, pose[b].y);
        g.addColorStop(0, '#ff3d00'); g.addColorStop(0.5, '#ffb000'); g.addColorStop(1, '#ff3d00');
        line(pose[a], pose[b], g, 9, 26, '#ff6a00');
        line(pose[a], pose[b], '#fff3b0', 2.5);
      }
      for (const j of JOINTS) if (pose[j]) {
        dot(pose[j], 6, '#ffd23f', '#ff6a00', 16, '#ff6a00');
        if (Math.random() < 0.35) parts.push({ kind: 'dot', x: pose[j].x + rand(-6, 6), y: pose[j].y, vx: rand(-20, 20), vy: rand(-120, -50), g: -30, life: 0, max: rand(0.4, 0.8), size: rand(2, 4.5), color: pick(['#ffb000', '#ff6a00', '#ffe066']) });
      }
    } else if (trail === 'ice') {
      for (const [a, b] of segs) { line(pose[a], pose[b], '#9fe3ff', 9, 20, '#7fd8ff'); line(pose[a], pose[b], '#ffffff', 3); }
      for (const j of JOINTS) if (pose[j]) {
        dot(pose[j], 6, '#ffffff', '#4aa8ff', 14, '#9fe3ff');
        if (Math.random() < 0.12) parts.push({ kind: 'star', x: pose[j].x + rand(-14, 14), y: pose[j].y + rand(-14, 14), vx: 0, vy: rand(-10, 10), g: 0, life: 0, max: 0.6, size: rand(3, 6), color: '#e6f9ff' });
      }
    } else if (trail === 'lightning') {
      for (const [a, b] of segs) {
        const amp = Math.hypot(pose[b].x - pose[a].x, pose[b].y - pose[a].y) * 0.08;
        poly(jagged(pose[a], pose[b], amp), '#b388ff', 5, 22, '#7c4dff');
        poly(jagged(pose[a], pose[b], amp * 0.6), '#ffffff', 2, 10, '#e0d0ff');
      }
      for (const j of JOINTS) if (pose[j]) dot(pose[j], 5 + Math.random() * 2, '#ffffff', '#7c4dff', 20, '#b388ff');
    } else if (trail === 'aurora') {
      segs.forEach(([a, b], i) => {
        const g = ctx.createLinearGradient(pose[a].x, pose[a].y, pose[b].x, pose[b].y);
        const k = (Math.sin(t * 2 + i) + 1) / 2;
        g.addColorStop(0, `hsl(${150 + k * 40} 90% 60%)`);
        g.addColorStop(1, `hsl(${270 + k * 30} 90% 65%)`);
        line(pose[a], pose[b], g, 12, 30, `hsl(${160 + k * 100} 90% 60%)`);
        line(pose[a], pose[b], 'rgba(255,255,255,0.9)', 2);
      });
      for (const j of JOINTS) if (pose[j]) {
        dot(pose[j], 6, '#eafff6', '#7cf2c8', 16, '#9b7bff');
        if (Math.random() < 0.15) parts.push({ kind: 'dot', x: pose[j].x + rand(-10, 10), y: pose[j].y, vx: rand(-8, 8), vy: rand(-60, -20), g: 0, life: 0, max: 1, size: rand(1.5, 3), color: pick(['#7cf2c8', '#9b7bff', '#5ff6ff']) });
      }
    } else if (trail === 'rainbow') {
      segs.forEach(([a, b], i) => {
        const hue = (t * 120 + i * 50) % 360;
        line(pose[a], pose[b], `hsl(${hue} 95% 60%)`, 8, 20, `hsl(${hue} 95% 60%)`);
        line(pose[a], pose[b], 'rgba(255,255,255,0.8)', 2);
      });
      JOINTS.forEach((j, i) => pose[j] && dot(pose[j], 6, '#fff', `hsl(${(t * 120 + i * 60) % 360} 95% 55%)`, 12, '#fff'));
    } else {
      for (const [a, b] of segs) { line(pose[a], pose[b], 'rgba(255,255,255,0.95)', 5, 14, 'rgba(67,168,255,0.9)'); }
      for (const j of JOINTS) if (pose[j]) dot(pose[j], 6, '#ffffff', '#43a8ff', 8, '#43a8ff');
    }
    for (const p of face) dot(p, 4, '#ffffff', null, 6, 'rgba(0,0,0,0.4)');
  }

  function ring(x, y, color, speed = 380, width = 6, max = 0.6) {
    parts.push({ kind: 'ring', x, y, r: 10, vr: speed, life: 0, max, color, size: width });
  }

  function explode(x, y, n, colors, speed = [120, 420], opts = {}) {
    for (let i = 0; i < n; i++) {
      const a = rand(0, Math.PI * 2), v = rand(...speed);
      parts.push({ kind: opts.kind || 'streak', x, y, vx: Math.cos(a) * v, vy: Math.sin(a) * v, g: opts.g ?? 380,
        life: 0, max: rand(0.5, 1.0), size: rand(2, 4), color: pick(colors), drag: opts.drag ?? 0.985 });
    }
  }

  // Всплеск на каждый засчитанный повтор.
  function burst(kind, x, y) {
    switch (kind) {
      case 'sparks': explode(x, y, 22, ['#ffe066', '#ffb000', '#fff'], [180, 480]); break;
      case 'ring': ring(x, y, '#5ff6ff', 420, 7); ring(x, y, '#ffffff', 300, 3, 0.5); break;
      case 'confetti':
        for (let i = 0; i < 34; i++) {
          const a = rand(-Math.PI, 0), v = rand(200, 520);
          parts.push({ kind: 'rect', x, y, vx: Math.cos(a) * v, vy: Math.sin(a) * v, g: 520, life: 0, max: rand(1, 1.6),
            size: rand(5, 9), color: pick(CONFETTI), rot: rand(0, 6), vrot: rand(-10, 10), drag: 0.98 });
        }
        break;
      case 'emoji':
        for (let i = 0; i < 5; i++) {
          parts.push({ kind: 'text', text: pick(['💪', '💪', '🔥', '⚡']), x: x + rand(-60, 60), y: y + rand(-10, 20),
            vx: rand(-30, 30), vy: rand(-220, -140), g: 120, life: 0, max: 1.1, size: rand(26, 38), rot: rand(-0.4, 0.4), vrot: rand(-1, 1) });
        }
        break;
      case 'fireworks':
        for (let k = 0; k < 3; k++) {
          const fx = x + rand(-w * 0.3, w * 0.3), fy = rand(h * 0.18, h * 0.42);
          const hue = rand(0, 360);
          explode(fx, fy, 30, [`hsl(${hue} 100% 65%)`, `hsl(${(hue + 40) % 360} 100% 70%)`, '#fff'], [120, 320], { g: 160, kind: 'dot', drag: 0.97 });
        }
        break;
      case 'meteor':
        parts.push({ kind: 'meteor', x: w + 40, y: -40, tx: x, ty: y, life: 0, max: 0.28 });
        setTimeout(() => {
          ring(x, y, '#ffb000', 520, 8, 0.7);
          ring(x, y, '#ffffff', 360, 3, 0.5);
          explode(x, y, 28, ['#ffb000', '#ff5c00', '#fff3b0'], [200, 560]);
        }, 280);
        break;
      case 'stars':
        for (let i = 0; i < 16; i++) {
          parts.push({ kind: 'star', x: x + rand(-w * 0.4, w * 0.4), y: rand(-40, h * 0.2), vx: rand(-20, 20), vy: rand(260, 420), g: 0,
            life: 0, max: rand(0.8, 1.3), size: rand(5, 10), color: pick(['#ffe27a', '#fff6c2', '#ffd23f']) });
        }
        ring(x, y, '#ffe27a', 360, 5, 0.55);
        break;
      default: break;
    }
  }

  // Обновить и нарисовать частицы.
  let lastT = performance.now();
  function step(now = performance.now()) {
    const dt = Math.min(0.05, (now - lastT) / 1000);
    lastT = now;
    for (let i = parts.length - 1; i >= 0; i--) {
      const p = parts[i];
      p.life += dt;
      if (p.life >= p.max) { parts.splice(i, 1); continue; }
      const k = 1 - p.life / p.max;
      if (p.vx !== undefined) {
        p.vx *= p.drag || 1; p.vy *= p.drag || 1;
        p.vy += (p.g || 0) * dt;
        p.x += p.vx * dt; p.y += p.vy * dt;
      }
      if (p.vrot) p.rot += p.vrot * dt;
      ctx.save();
      ctx.globalAlpha = Math.max(0, Math.min(1, k * 1.4));
      if (p.kind === 'dot') {
        ctx.shadowBlur = 10; ctx.shadowColor = p.color; ctx.fillStyle = p.color;
        ctx.beginPath(); ctx.arc(p.x, p.y, p.size * (0.5 + k * 0.5), 0, Math.PI * 2); ctx.fill();
      } else if (p.kind === 'streak') {
        ctx.strokeStyle = p.color; ctx.lineWidth = p.size; ctx.lineCap = 'round'; ctx.shadowBlur = 8; ctx.shadowColor = p.color;
        ctx.beginPath(); ctx.moveTo(p.x, p.y); ctx.lineTo(p.x - p.vx * 0.04, p.y - p.vy * 0.04); ctx.stroke();
      } else if (p.kind === 'rect') {
        ctx.translate(p.x, p.y); ctx.rotate(p.rot); ctx.fillStyle = p.color;
        ctx.fillRect(-p.size / 2, -p.size / 4, p.size, p.size / 2);
      } else if (p.kind === 'text') {
        ctx.translate(p.x, p.y); ctx.rotate(p.rot || 0);
        ctx.font = `${p.size}px system-ui, "Apple Color Emoji", "Segoe UI Emoji"`; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
        ctx.fillText(p.text, 0, 0);
      } else if (p.kind === 'ring') {
        p.r += p.vr * dt;
        ctx.strokeStyle = p.color; ctx.lineWidth = p.size * k; ctx.shadowBlur = 16; ctx.shadowColor = p.color;
        ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2); ctx.stroke();
      } else if (p.kind === 'star') {
        ctx.translate(p.x, p.y); ctx.fillStyle = p.color; ctx.shadowBlur = 8; ctx.shadowColor = '#9fe3ff';
        const s = p.size * k;
        ctx.beginPath(); ctx.moveTo(0, -s); ctx.lineTo(s * 0.25, -s * 0.25); ctx.lineTo(s, 0); ctx.lineTo(s * 0.25, s * 0.25);
        ctx.lineTo(0, s); ctx.lineTo(-s * 0.25, s * 0.25); ctx.lineTo(-s, 0); ctx.lineTo(-s * 0.25, -s * 0.25); ctx.closePath(); ctx.fill();
      } else if (p.kind === 'meteor') {
        const q = p.life / p.max;
        const mx = p.x + (p.tx - p.x) * q, my = p.y + (p.ty - p.y) * q;
        const g = ctx.createLinearGradient(p.x, p.y, mx, my);
        g.addColorStop(0, 'rgba(255,176,0,0)'); g.addColorStop(1, '#fff3b0');
        ctx.globalAlpha = 1; ctx.strokeStyle = g; ctx.lineWidth = 10; ctx.lineCap = 'round'; ctx.shadowBlur = 30; ctx.shadowColor = '#ff7a00';
        ctx.beginPath(); ctx.moveTo(p.x + (p.tx - p.x) * Math.max(0, q - 0.5), p.y + (p.ty - p.y) * Math.max(0, q - 0.5)); ctx.lineTo(mx, my); ctx.stroke();
      }
      ctx.restore();
    }
  }

  // Скин-костюм поверх тела.
  function drawSkin(pose, skin, now = performance.now()) {
    if (!pose || !skin || skin === 'none') return;
    paintSkin(ctx, pose, skin, now / 1000, (e) => {
      parts.push({ kind: 'dot', x: e.x, y: e.y, vx: rand(-15, 15), vy: rand(-140, -60), g: -40, life: 0, max: rand(0.35, 0.7), size: rand(2.5, 5), color: e.color });
    });
  }

  return {
    resize, clear, drawPose, drawSkin, burst, step,
    get size() { return { w, h }; },
    get busy() { return parts.length > 0; },
  };
}

// Праздник на экране итога: конфетти, фейерверк или золотой дождь. Возвращает stop().
export function celebrate(canvas, kind) {
  if (!kind || kind === 'none') return () => {};
  const fx = createFx(canvas);
  const ctx = canvas.getContext('2d');
  const { w, h } = fx.size;
  let raf = 0;
  const start = performance.now();
  let lastSpawn = 0;
  const coins = [];

  function frame(now) {
    const t = (now - start) / 1000;
    fx.clear();
    if (t < 4.5 && now - lastSpawn > (kind === 'fireworks' ? 450 : 90)) {
      lastSpawn = now;
      if (kind === 'confetti') fx.burst('confetti', rand(0, w), -10);
      if (kind === 'fireworks') fx.burst('fireworks', w / 2, h * 0.3);
      if (kind === 'aurora') fx.burst('stars', w / 2, h * 0.4);
      if (kind === 'goldrain') for (let i = 0; i < 3; i++) coins.push({ x: rand(0, w), y: -20, v: rand(140, 260), r: rand(6, 11), ph: rand(0, 6) });
    }
    if (kind === 'goldrain') {
      for (let i = coins.length - 1; i >= 0; i--) {
        const c = coins[i];
        c.y += c.v / 60; c.ph += 0.15;
        if (c.y > h + 20) { coins.splice(i, 1); continue; }
        const sx = Math.abs(Math.cos(c.ph));
        ctx.save(); ctx.translate(c.x, c.y); ctx.scale(Math.max(0.15, sx), 1);
        const g = ctx.createRadialGradient(-c.r * 0.3, -c.r * 0.3, 1, 0, 0, c.r);
        g.addColorStop(0, '#fff6c2'); g.addColorStop(0.5, '#ffd23f'); g.addColorStop(1, '#b8860b');
        ctx.fillStyle = g; ctx.shadowBlur = 12; ctx.shadowColor = '#ffd23f';
        ctx.beginPath(); ctx.arc(0, 0, c.r, 0, Math.PI * 2); ctx.fill(); ctx.restore();
      }
    }
    if (kind === 'aurora') {
      for (let b = 0; b < 3; b++) {
        const g = ctx.createLinearGradient(0, 0, w, 0);
        const sh = Math.sin(t * 0.8 + b) * 0.2;
        g.addColorStop(0, 'rgba(124,242,200,0)');
        g.addColorStop(0.3 + sh, `rgba(124,242,200,${0.22 - b * 0.05})`);
        g.addColorStop(0.7 + sh, `rgba(155,123,255,${0.22 - b * 0.05})`);
        g.addColorStop(1, 'rgba(155,123,255,0)');
        ctx.fillStyle = g;
        ctx.beginPath();
        const y0 = h * (0.12 + b * 0.08);
        ctx.moveTo(0, y0);
        for (let x = 0; x <= w; x += 20) ctx.lineTo(x, y0 + Math.sin(x / 60 + t * 2 + b) * 26);
        ctx.lineTo(w, y0 + 160); ctx.lineTo(0, y0 + 160); ctx.closePath(); ctx.fill();
      }
    }
    fx.step(now);
    if (t < 7 || fx.busy || coins.length) raf = requestAnimationFrame(frame);
    else fx.clear();
  }
  raf = requestAnimationFrame(frame);
  return () => { cancelAnimationFrame(raf); fx.clear(); };
}

// Синтетический человек, отжимающийся анфас, — для примерки эффектов в пропуске.
export function syntheticPose(t, w, h) {
  const phase = (Math.sin(t * 2.4) + 1) / 2; // 0 — вверху, 1 — внизу
  const cx = w / 2;
  const floor = h * 0.86;
  const sy = h * 0.34 + phase * h * 0.32;
  const sw = w * (0.17 + phase * 0.03);
  const ex = w * (0.27 + phase * 0.1);
  const ey = sy + (floor - sy) * (0.5 - phase * 0.25);
  const wx = w * 0.29;
  return {
    pose: {
      ls: { x: cx - sw, y: sy }, rs: { x: cx + sw, y: sy },
      le: { x: cx - ex, y: ey }, re: { x: cx + ex, y: ey },
      lw: { x: cx - wx, y: floor }, rw: { x: cx + wx, y: floor },
      leye: { x: cx - 12, y: sy - h * 0.12 }, reye: { x: cx + 12, y: sy - h * 0.12 }, nose: { x: cx, y: sy - h * 0.09 },
    },
    phase,
  };
}
