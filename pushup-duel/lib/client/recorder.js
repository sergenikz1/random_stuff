// Запись ролика боя прямо в приложении: кадр камеры + скелет и эффекты + табло + подпись-приглашение.
// Всё рисуется на отдельный холст 9:16, который пишет MediaRecorder. Звук не пишем.

const W = 720, H = 1280, FPS = 30, MAX_MS = 3 * 60 * 1000;

// MP4 Telegram показывает как видео; WebM уйдёт файлом. Берём MP4, если браузер умеет.
export function pickMime() {
  if (typeof MediaRecorder === 'undefined') return '';
  const list = ['video/mp4;codecs=avc1.42E01E', 'video/mp4;codecs=avc1', 'video/mp4', 'video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm'];
  return list.find((t) => { try { return MediaRecorder.isTypeSupported(t); } catch { return false; } }) || '';
}

export const canRecord = () => typeof document !== 'undefined'
  && !!pickMime() && typeof HTMLCanvasElement !== 'undefined' && !!HTMLCanvasElement.prototype.captureStream;

const fontOf = () => (typeof document !== 'undefined' ? getComputedStyle(document.body).fontFamily : 'sans-serif');

// Табло и подпись: плоско, в цветах приложения.
function drawHud(ctx, hud, font) {
  const navy = 'rgba(16, 33, 63, 0.88)', ochre = '#d9a63a', white = '#ffffff';
  // Верхняя плашка.
  ctx.fillStyle = navy;
  ctx.fillRect(24, 28, W - 48, hud.opp ? 190 : 150);
  ctx.strokeStyle = ochre; ctx.lineWidth = 4;
  ctx.strokeRect(24, 28, W - 48, hud.opp ? 190 : 150);
  ctx.textBaseline = 'middle';
  ctx.fillStyle = ochre; ctx.font = `800 22px ${font}`; ctx.textAlign = 'center';
  ctx.fillText(hud.tag, W / 2, 58);
  ctx.fillStyle = white; ctx.font = `800 64px ${font}`;
  ctx.fillText(hud.time, W / 2, 112);
  ctx.font = `800 28px ${font}`;
  ctx.textAlign = 'left'; ctx.fillText(hud.me.name.slice(0, 14), 52, 112);
  if (hud.opp) {
    ctx.textAlign = 'right'; ctx.fillText(hud.opp.name.slice(0, 14), W - 52, 112);
    ctx.font = `800 64px ${font}`;
    ctx.textAlign = 'left'; ctx.fillStyle = '#8fd17f'; ctx.fillText(String(hud.me.reps), 52, 176);
    ctx.textAlign = 'right'; ctx.fillStyle = '#ff8a7a'; ctx.fillText(String(hud.opp.reps), W - 52, 176);
    const total = hud.me.reps + hud.opp.reps, share = total ? hud.me.reps / total : 0.5;
    const x0 = 170, x1 = W - 170, y = 168;
    ctx.fillStyle = '#c0392b'; ctx.fillRect(x0, y, x1 - x0, 16);
    ctx.fillStyle = '#3d7a35'; ctx.fillRect(x0, y, (x1 - x0) * share, 16);
  } else {
    ctx.textAlign = 'right'; ctx.fillStyle = ochre; ctx.font = `800 64px ${font}`;
    ctx.fillText(String(hud.me.reps), W - 52, 112);
  }
  // Нижняя подпись-приглашение.
  ctx.fillStyle = navy; ctx.fillRect(0, H - 120, W, 120);
  ctx.fillStyle = ochre; ctx.fillRect(0, H - 120, W, 5);
  ctx.textAlign = 'center'; ctx.fillStyle = white; ctx.font = `800 34px ${font}`;
  ctx.fillText('PUSHDUEL · сможешь больше?', W / 2, H - 76);
  ctx.fillStyle = ochre; ctx.font = `700 26px ${font}`;
  ctx.fillText(hud.invite, W / 2, H - 36);
  // Мигающая точка записи.
  if (Math.floor(performance.now() / 600) % 2 === 0) {
    ctx.fillStyle = '#c0392b'; ctx.beginPath(); ctx.arc(W - 46, H - 150, 10, 0, Math.PI * 2); ctx.fill();
  }
}

export class ClipRecorder {
  // video — <video> камеры, overlay — холст со скелетом/эффектами, getHud — данные табло, mirror — зеркалить ли кадр.
  constructor({ video, overlay, getHud, mirror = true }) {
    this.video = video; this.overlay = overlay; this.getHud = getHud; this.mirror = mirror;
    this.canvas = document.createElement('canvas');
    this.canvas.width = W; this.canvas.height = H;
    this.ctx = this.canvas.getContext('2d');
    this.mime = pickMime();
    this.font = fontOf();
    this.chunks = [];
    this.raf = 0;
    this.startedAt = 0;
  }

  draw() {
    const { ctx, video: v, overlay } = this;
    ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H);
    if (v && v.videoWidth) {
      // Как на экране: кадр «cover» по центру, фронтальная камера — зеркально.
      const s = Math.max(W / v.videoWidth, H / v.videoHeight);
      const dw = v.videoWidth * s, dh = v.videoHeight * s;
      ctx.save();
      if (this.mirror) { ctx.translate(W, 0); ctx.scale(-1, 1); }
      ctx.drawImage(v, (W - dw) / 2, (H - dh) / 2, dw, dh);
      ctx.restore();
    }
    // Скелет и эффекты: холст экрана растягиваем «cover» так же, как кадр.
    if (overlay && overlay.width) {
      const s = Math.max(W / overlay.width, H / overlay.height);
      const dw = overlay.width * s, dh = overlay.height * s;
      ctx.drawImage(overlay, (W - dw) / 2, (H - dh) / 2, dw, dh);
    }
    drawHud(ctx, this.getHud(), this.font);
  }

  start(onAutoStop) {
    const stream = this.canvas.captureStream(FPS);
    this.rec = new MediaRecorder(stream, { mimeType: this.mime, videoBitsPerSecond: 2_500_000 });
    this.rec.ondataavailable = (e) => { if (e.data?.size) this.chunks.push(e.data); };
    this.rec.start(1000);
    this.startedAt = performance.now();
    const loop = () => {
      this.draw();
      if (performance.now() - this.startedAt > MAX_MS) { onAutoStop?.(); return; }
      this.raf = requestAnimationFrame(loop);
    };
    loop();
  }

  get ms() { return this.startedAt ? performance.now() - this.startedAt : 0; }

  // Останавливает запись и отдаёт ролик { blob, url, type, ms }.
  stop() {
    cancelAnimationFrame(this.raf);
    const ms = this.ms;
    return new Promise((resolve) => {
      if (!this.rec || this.rec.state === 'inactive') { resolve(null); return; }
      this.rec.onstop = () => {
        const type = (this.mime.split(';')[0]) || 'video/webm';
        const blob = new Blob(this.chunks, { type });
        resolve(blob.size ? { blob, url: URL.createObjectURL(blob), type, ms } : null);
      };
      this.rec.stop();
      this.rec.stream.getTracks().forEach((t) => t.stop());
    });
  }
}
