// Скины-костюмы, которые рисуются поверх игрока по точкам тела.
// Все размеры считаются от ширины плеч, поэтому костюм «сидит» на любом расстоянии от камеры.

const dist = (a, b) => Math.hypot(b.x - a.x, b.y - a.y);
const mid = (a, b) => ({ x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 });
const ang = (a, b) => Math.atan2(b.y - a.y, b.x - a.x);
const rand = (a, b) => a + Math.random() * (b - a);

function limb(ctx, a, b, width, style, opts = {}) {
  ctx.save();
  ctx.lineCap = 'round';
  ctx.strokeStyle = style;
  ctx.lineWidth = width;
  if (opts.glow) { ctx.shadowBlur = opts.glow; ctx.shadowColor = opts.glowColor || style; }
  ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
  if (opts.edge) {
    ctx.shadowBlur = 0;
    ctx.strokeStyle = opts.edge; ctx.lineWidth = 2;
    const n = ang(a, b) + Math.PI / 2, o = width / 2;
    for (const s of [-1, 1]) {
      ctx.beginPath();
      ctx.moveTo(a.x + Math.cos(n) * o * s, a.y + Math.sin(n) * o * s);
      ctx.lineTo(b.x + Math.cos(n) * o * s, b.y + Math.sin(n) * o * s);
      ctx.stroke();
    }
  }
  ctx.restore();
}

function metal(ctx, a, b, w, c1 = '#e9eef5', c2 = '#7d8798') {
  const n = ang(a, b) + Math.PI / 2;
  const m = mid(a, b);
  const g = ctx.createLinearGradient(m.x - Math.cos(n) * w, m.y - Math.sin(n) * w, m.x + Math.cos(n) * w, m.y + Math.sin(n) * w);
  g.addColorStop(0, c2); g.addColorStop(0.45, c1); g.addColorStop(1, c2);
  return g;
}

function circle(ctx, p, r, fill, stroke, lw = 2, glow = 0, glowColor) {
  ctx.save();
  if (glow) { ctx.shadowBlur = glow; ctx.shadowColor = glowColor || fill; }
  ctx.beginPath(); ctx.arc(p.x, p.y, r, 0, Math.PI * 2);
  if (fill) { ctx.fillStyle = fill; ctx.fill(); }
  if (stroke) { ctx.lineWidth = lw; ctx.strokeStyle = stroke; ctx.stroke(); }
  ctx.restore();
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
}

// Геометрия тела из точек позы.
function body(pose) {
  const { ls, rs } = pose;
  if (!ls || !rs) return null;
  const sw = Math.max(40, dist(ls, rs));
  const sh = mid(ls, rs);
  const eyes = pose.leye && pose.reye ? mid(pose.leye, pose.reye) : null;
  const head = pose.nose ? { x: pose.nose.x, y: (eyes ? eyes.y : pose.nose.y) } : eyes || { x: sh.x, y: sh.y - sw * 0.55 };
  const R = Math.max(16, sw * 0.3);
  const tilt = ang(rs, ls) + (ls.x < rs.x ? Math.PI : 0); // наклон линии плеч
  const arms = [['ls', 'le', 'lw'], ['rs', 're', 'rw']]
    .map(([s, e, w]) => ({ s: pose[s], e: pose[e], w: pose[w] }))
    .filter((a) => a.s && a.e);
  return { sw, sh, head, R, tilt, arms, eyes };
}

function forArms(b, fn) {
  for (const a of b.arms) {
    fn(a.s, a.e, 'upper');
    if (a.w) fn(a.e, a.w, 'fore');
  }
}

// ---- Скины ----
const SKINS = {
  bones(ctx, b) {
    const bw = b.sw * 0.07;
    ctx.save();
    ctx.shadowBlur = 10; ctx.shadowColor = 'rgba(180,220,255,0.8)';
    // Позвоночник и рёбра.
    const spineTop = { x: b.sh.x, y: b.sh.y };
    const spineEnd = { x: b.sh.x, y: b.sh.y + b.sw * 0.75 };
    limb(ctx, spineTop, spineEnd, bw * 0.8, '#f5f2e8');
    for (let i = 0; i < 4; i++) {
      const y = b.sh.y + b.sw * (0.12 + i * 0.14);
      const w = b.sw * (0.42 - i * 0.05);
      ctx.strokeStyle = '#f5f2e8'; ctx.lineWidth = bw * 0.7; ctx.lineCap = 'round';
      ctx.beginPath(); ctx.ellipse(b.sh.x, y, w, b.sw * 0.07, 0, Math.PI * 0.05, Math.PI * 0.95); ctx.stroke();
    }
    limb(ctx, b.arms[0]?.s || b.sh, b.arms[1]?.s || b.sh, bw, '#f5f2e8'); // ключицы
    forArms(b, (p, q) => {
      limb(ctx, p, q, bw, '#f5f2e8');
      const n = ang(p, q) + Math.PI / 2;
      for (const end of [p, q]) for (const s of [-1, 1]) {
        circle(ctx, { x: end.x + Math.cos(n) * bw * 0.6 * s, y: end.y + Math.sin(n) * bw * 0.6 * s }, bw * 0.75, '#f5f2e8');
      }
    });
    // Череп.
    const H = b.head, R = b.R;
    circle(ctx, { x: H.x, y: H.y - R * 0.15 }, R, '#f5f2e8', '#c9c2ad', 2);
    ctx.fillStyle = '#f5f2e8';
    roundRect(ctx, H.x - R * 0.55, H.y + R * 0.45, R * 1.1, R * 0.55, R * 0.15); ctx.fill();
    ctx.shadowBlur = 0;
    ctx.fillStyle = '#14101a';
    for (const s of [-1, 1]) { ctx.beginPath(); ctx.ellipse(H.x + s * R * 0.38, H.y - R * 0.1, R * 0.26, R * 0.3, 0, 0, Math.PI * 2); ctx.fill(); }
    ctx.beginPath(); ctx.moveTo(H.x, H.y + R * 0.15); ctx.lineTo(H.x - R * 0.12, H.y + R * 0.38); ctx.lineTo(H.x + R * 0.12, H.y + R * 0.38); ctx.closePath(); ctx.fill();
    ctx.strokeStyle = '#14101a'; ctx.lineWidth = 1.5;
    for (let i = -2; i <= 2; i++) { ctx.beginPath(); ctx.moveTo(H.x + i * R * 0.18, H.y + R * 0.5); ctx.lineTo(H.x + i * R * 0.18, H.y + R * 0.95); ctx.stroke(); }
    ctx.restore();
  },

  robot(ctx, b, t) {
    const w = b.sw * 0.2;
    forArms(b, (p, q) => {
      limb(ctx, p, q, w, metal(ctx, p, q, w), { edge: '#3b4250' });
      circle(ctx, q, w * 0.42, '#5d6676', '#2a2f38', 2);
      circle(ctx, q, w * 0.14, '#ffcf3f');
    });
    for (const a of b.arms) if (a.w) {
      ctx.save(); ctx.translate(a.w.x, a.w.y); ctx.rotate(ang(a.e, a.w));
      ctx.fillStyle = metal(ctx, { x: -w, y: 0 }, { x: w, y: 0 }, w);
      roundRect(ctx, -w * 0.2, -w * 0.6, w * 1.1, w * 1.2, 5); ctx.fill(); ctx.strokeStyle = '#2a2f38'; ctx.lineWidth = 2; ctx.stroke();
      ctx.restore();
    }
    // Нагрудник с ядром.
    const cw = b.sw * 0.9, ch = b.sw * 0.5;
    ctx.save();
    ctx.translate(b.sh.x, b.sh.y); ctx.rotate(b.tilt);
    ctx.fillStyle = metal(ctx, { x: 0, y: -ch }, { x: 0, y: ch }, ch, '#dfe5ee', '#6f7888');
    roundRect(ctx, -cw / 2, -ch * 0.15, cw, ch, 12); ctx.fill(); ctx.strokeStyle = '#2a2f38'; ctx.lineWidth = 2.5; ctx.stroke();
    const pulse = 0.6 + 0.4 * Math.sin(t * 5);
    circle(ctx, { x: 0, y: ch * 0.33 }, ch * 0.22, `rgba(80,220,255,${0.6 + pulse * 0.4})`, '#1b2a3a', 3, 25 * pulse, '#4fe0ff');
    ctx.restore();
    // Голова с визором.
    const H = b.head, R = b.R;
    ctx.save();
    ctx.translate(H.x, H.y); ctx.rotate(b.tilt);
    ctx.fillStyle = metal(ctx, { x: -R, y: 0 }, { x: R, y: 0 }, R, '#eef2f8', '#6f7888');
    roundRect(ctx, -R, -R * 1.05, R * 2, R * 1.9, R * 0.45); ctx.fill(); ctx.strokeStyle = '#2a2f38'; ctx.lineWidth = 2.5; ctx.stroke();
    ctx.shadowBlur = 22; ctx.shadowColor = '#ff2a2a'; ctx.fillStyle = '#ff3b3b';
    roundRect(ctx, -R * 0.8, -R * 0.28, R * 1.6, R * 0.38, R * 0.18); ctx.fill();
    ctx.shadowBlur = 0; ctx.fillStyle = 'rgba(255,255,255,0.7)';
    const scan = ((t * 1.2) % 1) * R * 1.4 - R * 0.7;
    ctx.fillRect(scan, -R * 0.26, R * 0.12, R * 0.34);
    ctx.strokeStyle = '#2a2f38'; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.moveTo(0, -R * 1.05); ctx.lineTo(0, -R * 1.45); ctx.stroke();
    circle(ctx, { x: 0, y: -R * 1.5 }, R * 0.12, '#ffcf3f', null, 0, 12, '#ffcf3f');
    ctx.restore();
  },

  ninja(ctx, b, t) {
    const w = b.sw * 0.18;
    forArms(b, (p, q) => limb(ctx, p, q, w, '#15151d', { edge: '#2d2d3a' }));
    for (const a of b.arms) if (a.w) limb(ctx, a.e, a.w, w * 0.5, '#d8d0c0', {}); // обмотки
    for (const a of b.arms) if (a.w) circle(ctx, a.w, w * 0.45, '#15151d');
    const H = b.head, R = b.R;
    // Хвосты повязки развеваются.
    ctx.save();
    ctx.strokeStyle = '#e0243a'; ctx.lineWidth = R * 0.22; ctx.lineCap = 'round';
    for (const k of [0, 1]) {
      const sx = H.x + R * 0.9, sy = H.y - R * 0.45;
      ctx.beginPath(); ctx.moveTo(sx, sy);
      ctx.bezierCurveTo(sx + R * 0.8, sy + Math.sin(t * 6 + k) * R * 0.5, sx + R * 1.4, sy + R * 0.6 + Math.cos(t * 5 + k) * R * 0.5, sx + R * (1.9 + k * 0.2), sy + R * (0.4 + k * 0.6) + Math.sin(t * 7 + k) * R * 0.4);
      ctx.stroke();
    }
    ctx.restore();
    circle(ctx, H, R * 1.05, '#15151d', '#2d2d3a', 2);
    ctx.save(); ctx.translate(H.x, H.y); ctx.rotate(b.tilt);
    ctx.fillStyle = '#e8c9a5'; roundRect(ctx, -R * 0.85, -R * 0.32, R * 1.7, R * 0.5, R * 0.25); ctx.fill();
    ctx.fillStyle = '#101018';
    for (const s of [-1, 1]) { ctx.beginPath(); ctx.ellipse(s * R * 0.38, -R * 0.07, R * 0.17, R * 0.12, 0, 0, Math.PI * 2); ctx.fill(); }
    ctx.fillStyle = '#e0243a'; ctx.fillRect(-R * 1.02, -R * 0.62, R * 2.04, R * 0.24);
    ctx.restore();
  },

  knight(ctx, b, t) {
    const w = b.sw * 0.19;
    forArms(b, (p, q, part) => {
      limb(ctx, p, q, w, metal(ctx, p, q, w), { edge: '#c9a227' });
      if (part === 'fore') circle(ctx, p, w * 0.5, metal(ctx, p, q, w), '#c9a227', 2);
    });
    for (const a of b.arms) {
      // Наплечники.
      const out = a.s.x < b.sh.x ? -1 : 1;
      ctx.save(); ctx.translate(a.s.x, a.s.y); ctx.rotate(b.tilt);
      ctx.fillStyle = metal(ctx, { x: -w, y: -w }, { x: w, y: w }, w);
      ctx.beginPath(); ctx.ellipse(out * w * 0.2, -w * 0.1, w * 1.05, w * 0.75, 0, Math.PI, 0); ctx.closePath(); ctx.fill();
      ctx.strokeStyle = '#c9a227'; ctx.lineWidth = 3; ctx.stroke();
      ctx.restore();
      if (a.w) {
        ctx.save(); ctx.translate(a.w.x, a.w.y); ctx.rotate(ang(a.e, a.w));
        ctx.fillStyle = metal(ctx, { x: 0, y: -w }, { x: 0, y: w }, w);
        roundRect(ctx, -w * 0.3, -w * 0.55, w * 1.1, w * 1.1, 6); ctx.fill(); ctx.strokeStyle = '#c9a227'; ctx.lineWidth = 2; ctx.stroke();
        ctx.restore();
      }
    }
    // Шлем с прорезью и плюмажем.
    const H = b.head, R = b.R;
    ctx.save(); ctx.translate(H.x, H.y); ctx.rotate(b.tilt);
    ctx.strokeStyle = '#d4202f'; ctx.lineWidth = R * 0.32; ctx.lineCap = 'round';
    ctx.beginPath(); ctx.moveTo(0, -R * 1.05);
    ctx.quadraticCurveTo(R * 0.9, -R * 1.9 + Math.sin(t * 4) * R * 0.15, R * 1.5, -R * 1.2 + Math.sin(t * 5) * R * 0.2); ctx.stroke();
    ctx.fillStyle = metal(ctx, { x: -R, y: 0 }, { x: R, y: 0 }, R, '#f4f7fb', '#7b8494');
    ctx.beginPath(); ctx.moveTo(-R, R * 0.9); ctx.lineTo(-R, -R * 0.3); ctx.quadraticCurveTo(-R, -R * 1.15, 0, -R * 1.15);
    ctx.quadraticCurveTo(R, -R * 1.15, R, -R * 0.3); ctx.lineTo(R, R * 0.9); ctx.closePath(); ctx.fill();
    ctx.strokeStyle = '#c9a227'; ctx.lineWidth = 2.5; ctx.stroke();
    ctx.fillStyle = '#0d0f14';
    ctx.fillRect(-R * 0.75, -R * 0.25, R * 1.5, R * 0.2);
    ctx.fillRect(-R * 0.1, -R * 0.25, R * 0.2, R * 0.9);
    ctx.restore();
  },

  hero(ctx, b, t) {
    // Плащ — позади всего.
    const L = b.arms.find((a) => a.s.x < b.sh.x)?.s, Rr = b.arms.find((a) => a.s.x >= b.sh.x)?.s;
    if (L && Rr) {
      const flow = Math.sin(t * 3) * b.sw * 0.12;
      const g = ctx.createLinearGradient(b.sh.x, b.sh.y, b.sh.x, b.sh.y + b.sw * 1.6);
      g.addColorStop(0, '#d61f3c'); g.addColorStop(1, '#7a0d22');
      ctx.save(); ctx.fillStyle = g; ctx.globalAlpha = 0.92;
      ctx.beginPath(); ctx.moveTo(L.x, L.y); ctx.lineTo(Rr.x, Rr.y);
      ctx.quadraticCurveTo(Rr.x + b.sw * 0.6, Rr.y + b.sw * 0.9, Rr.x + b.sw * 0.5 + flow, Rr.y + b.sw * 1.6);
      ctx.lineTo(L.x - b.sw * 0.5 + flow, L.y + b.sw * 1.6);
      ctx.quadraticCurveTo(L.x - b.sw * 0.6, L.y + b.sw * 0.9, L.x, L.y); ctx.fill();
      ctx.restore();
    }
    const w = b.sw * 0.19;
    forArms(b, (p, q) => limb(ctx, p, q, w, metal(ctx, p, q, w, '#4f8dff', '#1b3fa8'), { edge: '#0d2470' }));
    for (const a of b.arms) if (a.w) circle(ctx, a.w, w * 0.55, '#d61f3c', '#7a0d22', 2);
    // Эмблема.
    const E = { x: b.sh.x, y: b.sh.y + b.sw * 0.22 }, s = b.sw * 0.22;
    ctx.save(); ctx.translate(E.x, E.y); ctx.rotate(b.tilt);
    ctx.fillStyle = '#d61f3c'; ctx.strokeStyle = '#ffd23f'; ctx.lineWidth = 3;
    ctx.beginPath(); ctx.moveTo(0, -s); ctx.lineTo(s * 1.1, 0); ctx.lineTo(0, s); ctx.lineTo(-s * 1.1, 0); ctx.closePath(); ctx.fill(); ctx.stroke();
    ctx.fillStyle = '#ffd23f'; ctx.shadowBlur = 12; ctx.shadowColor = '#ffd23f';
    ctx.beginPath();
    for (let i = 0; i < 10; i++) { const r = i % 2 ? s * 0.25 : s * 0.6; const a = -Math.PI / 2 + (i * Math.PI) / 5; ctx.lineTo(Math.cos(a) * r, Math.sin(a) * r); }
    ctx.closePath(); ctx.fill();
    ctx.restore();
    // Маска.
    const H = b.head, R = b.R;
    ctx.save(); ctx.translate(H.x, H.y); ctx.rotate(b.tilt);
    ctx.fillStyle = '#d61f3c';
    ctx.beginPath(); ctx.moveTo(-R * 1.05, -R * 0.35); ctx.quadraticCurveTo(0, -R * 0.6, R * 1.05, -R * 0.35);
    ctx.lineTo(R * 0.95, R * 0.2); ctx.quadraticCurveTo(0, R * 0.05, -R * 0.95, R * 0.2); ctx.closePath(); ctx.fill();
    ctx.fillStyle = '#fff';
    for (const sx of [-1, 1]) { ctx.beginPath(); ctx.ellipse(sx * R * 0.4, -R * 0.1, R * 0.24, R * 0.13, sx * 0.2, 0, Math.PI * 2); ctx.fill(); }
    ctx.restore();
  },

  cyber(ctx, b, t) {
    const w = b.sw * 0.14;
    ctx.save();
    forArms(b, (p, q) => {
      const n = ang(p, q) + Math.PI / 2;
      for (const s of [-1, 1]) {
        const o = { x: Math.cos(n) * w * 0.5 * s, y: Math.sin(n) * w * 0.5 * s };
        limb(ctx, { x: p.x + o.x, y: p.y + o.y }, { x: q.x + o.x, y: q.y + o.y }, 2.5, s < 0 ? '#5ff6ff' : '#ff4fd8', { glow: 14 });
      }
      const k = (t * 1.5) % 1;
      circle(ctx, { x: p.x + (q.x - p.x) * k, y: p.y + (q.y - p.y) * k }, 3.5, '#ffffff', null, 0, 14, '#5ff6ff');
      circle(ctx, q, w * 0.38, 'rgba(10,20,40,0.6)', '#5ff6ff', 2.5, 14, '#5ff6ff');
    });
    // Ядро.
    const C = { x: b.sh.x, y: b.sh.y + b.sw * 0.25 }, r = b.sw * 0.17;
    ctx.translate(C.x, C.y); ctx.rotate(t * 1.6);
    ctx.strokeStyle = '#5ff6ff'; ctx.lineWidth = 2.5; ctx.shadowBlur = 18; ctx.shadowColor = '#5ff6ff';
    ctx.beginPath(); for (let i = 0; i < 3; i++) { const a = (i * Math.PI * 2) / 3; ctx.lineTo(Math.cos(a) * r, Math.sin(a) * r); } ctx.closePath(); ctx.stroke();
    ctx.rotate(-t * 3.2); ctx.strokeStyle = '#ff4fd8'; ctx.shadowColor = '#ff4fd8';
    ctx.beginPath(); for (let i = 0; i < 3; i++) { const a = (i * Math.PI * 2) / 3; ctx.lineTo(Math.cos(a) * r * 0.6, Math.sin(a) * r * 0.6); } ctx.closePath(); ctx.stroke();
    ctx.restore();
    // Голова — шестиугольник со сканером.
    const H = b.head, R = b.R;
    ctx.save(); ctx.translate(H.x, H.y); ctx.rotate(b.tilt);
    ctx.strokeStyle = '#ff4fd8'; ctx.lineWidth = 3; ctx.shadowBlur = 18; ctx.shadowColor = '#ff4fd8'; ctx.fillStyle = 'rgba(20,0,40,0.45)';
    ctx.beginPath(); for (let i = 0; i < 6; i++) { const a = Math.PI / 6 + (i * Math.PI) / 3; ctx.lineTo(Math.cos(a) * R * 1.05, Math.sin(a) * R * 1.05); } ctx.closePath(); ctx.fill(); ctx.stroke();
    ctx.strokeStyle = '#5ff6ff'; ctx.shadowColor = '#5ff6ff';
    const y = Math.sin(t * 3) * R * 0.6;
    ctx.beginPath(); ctx.moveTo(-R * 0.8, y); ctx.lineTo(R * 0.8, y); ctx.stroke();
    ctx.fillStyle = '#5ff6ff';
    for (const s of [-1, 1]) ctx.fillRect(s * R * 0.4 - R * 0.15, -R * 0.2, R * 0.3, R * 0.12);
    ctx.restore();
  },

  flame(ctx, b, t, emit) {
    forArms(b, (p, q) => {
      const g = ctx.createLinearGradient(p.x, p.y, q.x, q.y);
      g.addColorStop(0, '#ff5a00'); g.addColorStop(0.5, '#ffb000'); g.addColorStop(1, '#ff3d00');
      limb(ctx, p, q, b.sw * 0.2, g, { glow: 30, glowColor: '#ff6a00' });
      limb(ctx, p, q, b.sw * 0.06, '#fff3b0', {});
      for (let i = 0; i < 2; i++) {
        const k = Math.random();
        emit({ x: p.x + (q.x - p.x) * k + rand(-6, 6), y: p.y + (q.y - p.y) * k, color: Math.random() < 0.5 ? '#ffb000' : '#ff5a00' });
      }
    });
    const H = b.head, R = b.R;
    const g = ctx.createRadialGradient(H.x, H.y, R * 0.1, H.x, H.y - R * 0.3, R * 1.4);
    g.addColorStop(0, '#fff3b0'); g.addColorStop(0.4, '#ffb000'); g.addColorStop(1, 'rgba(255,60,0,0)');
    ctx.save(); ctx.fillStyle = g;
    ctx.beginPath(); ctx.moveTo(H.x - R, H.y + R * 0.4);
    for (let i = 0; i <= 6; i++) {
      const x = H.x - R + (i * R * 2) / 6;
      const h = R * (1.1 + 0.5 * Math.sin(t * 9 + i * 1.7)) * (i % 2 ? 1.25 : 0.9);
      ctx.lineTo(x, H.y - h);
    }
    ctx.lineTo(H.x + R, H.y + R * 0.4); ctx.quadraticCurveTo(H.x, H.y + R * 1.2, H.x - R, H.y + R * 0.4); ctx.fill();
    ctx.fillStyle = '#fff';
    for (const s of [-1, 1]) { ctx.beginPath(); ctx.ellipse(H.x + s * R * 0.35, H.y, R * 0.14, R * 0.22, 0, 0, Math.PI * 2); ctx.fill(); }
    ctx.restore();
    if (Math.random() < 0.6) emit({ x: H.x + rand(-R, R), y: H.y - R, color: '#ffb000' });
  },
};

export function drawSkin(ctx, pose, skin, t, emit) {
  const fn = SKINS[skin];
  if (!fn || !pose) return;
  const b = body(pose);
  if (!b) return;
  fn(ctx, b, t, emit);
}
