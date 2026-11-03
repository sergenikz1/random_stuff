// Ролики боёв: клиент присылает записанное видео, сервер сохраняет его и, если есть бот,
//  1) отправляет ролик игроку в чат с ботом (копия у него остаётся),
//  2) готовит сообщение для пересылки (savePreparedInlineMessage): мини-приложение открывает
//     окно выбора чата (WebApp.shareMessage), и ролик уходит другу с кнопкой-приглашением.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { UserError } from './store.js';

export const CLIP_MAX_BYTES = 45 * 1024 * 1024; // боту можно загрузить до 50 МБ
const TYPES = { 'video/mp4': 'mp4', 'video/webm': 'webm' };
const KEEP_MS = 24 * 3600e3;

// Ссылка-приглашение: та же, что в приложении (ref_<id>).
export function inviteLink(config, playerId) {
  const sp = `ref_${playerId}`;
  if (config?.botUsername) {
    return config.appShortName
      ? `https://t.me/${config.botUsername}/${config.appShortName}?startapp=${sp}`
      : `https://t.me/${config.botUsername}?start=${sp}`;
  }
  return config?.webAppUrl ? `${config.webAppUrl.replace(/\/$/, '')}/?sp=${sp}` : '';
}

export function clipCaption(meta = {}) {
  const reps = Math.max(0, Math.min(999, Number(meta.reps) || 0));
  const opp = typeof meta.opp === 'string' ? meta.opp.slice(0, 24) : '';
  const oppReps = Math.max(0, Math.min(999, Number(meta.oppReps) || 0));
  const head = opp ? `Мой бой в PushDuel против ${opp}: ${reps}:${oppReps}` : `Мой подход в PushDuel: ${reps} отжиманий`;
  return `${head} 💪\nСможешь больше? Принимай вызов — камера сама считает повторы.`;
}

export class Clips {
  constructor({ dir = null, botToken = '', config = {}, fetchImpl = globalThis.fetch } = {}) {
    this.dir = dir;
    this.botToken = botToken;
    this.config = config;
    this.fetch = fetchImpl;
    this.mem = new Map(); // без каталога (тесты) — в памяти
    if (dir) fs.mkdirSync(dir, { recursive: true });
  }

  async call(method, body) {
    const isForm = typeof FormData !== 'undefined' && body instanceof FormData;
    const r = await this.fetch(`https://api.telegram.org/bot${this.botToken}/${method}`, {
      method: 'POST',
      ...(isForm ? { body } : { headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) }),
    });
    const j = await r.json();
    if (!j.ok) throw new Error(`${method}: ${j.description}`);
    return j.result;
  }

  // buf — Buffer, type — content-type, meta — { reps, opp, oppReps }.
  async create(p, buf, type, meta = {}) {
    const mime = String(type || '').split(';')[0].trim();
    const ext = TYPES[mime];
    if (!ext) throw new UserError('Нужен ролик MP4 или WebM');
    if (!buf?.length) throw new UserError('Пустой ролик');
    if (buf.length > CLIP_MAX_BYTES) throw new UserError('Ролик слишком большой (до 45 МБ)');
    this.cleanup();
    const id = crypto.randomBytes(12).toString('hex');
    const file = `${id}.${ext}`;
    if (this.dir) fs.writeFileSync(path.join(this.dir, file), buf);
    else this.mem.set(file, { buf, type: mime, at: Date.now() });

    const link = inviteLink(this.config, p.id);
    const caption = clipCaption(meta);
    const out = { id, url: `/api/clips/${file}`, link, caption, prepared: null, sentToChat: false };
    if (!this.botToken || !p.tgId) return out;

    const markup = link ? { inline_keyboard: [[{ text: '💪 Принять вызов', url: link }]] } : undefined;
    try {
      // MP4 — как видео, WebM Telegram показывает только файлом.
      const asVideo = ext === 'mp4';
      const form = new FormData();
      form.append('chat_id', String(p.tgId));
      form.append(asVideo ? 'video' : 'document', new Blob([buf], { type: mime }), `pushduel.${ext}`);
      form.append('caption', caption);
      if (asVideo) form.append('supports_streaming', 'true');
      if (markup) form.append('reply_markup', JSON.stringify(markup));
      const msg = await this.call(asVideo ? 'sendVideo' : 'sendDocument', form);
      out.sentToChat = true;
      const fileId = msg.video?.file_id || msg.animation?.file_id || msg.document?.file_id;
      if (fileId) {
        const result = asVideo
          ? { type: 'video', id, video_file_id: fileId, title: 'PushDuel', caption, reply_markup: markup }
          : { type: 'document', id, document_file_id: fileId, title: 'PushDuel', caption, reply_markup: markup };
        const prep = await this.call('savePreparedInlineMessage', {
          user_id: p.tgId, result, allow_user_chats: true, allow_group_chats: true, allow_channel_chats: true,
        });
        out.prepared = prep.id;
      }
    } catch (e) {
      console.error('clips:', e.message);
    }
    return out;
  }

  // Файл ролика для GET /api/clips/<id>.<ext>: { buf, type } | null.
  get(file) {
    if (!/^[0-9a-f]{24}\.(mp4|webm)$/.test(String(file))) return null;
    const type = file.endsWith('.mp4') ? 'video/mp4' : 'video/webm';
    if (!this.dir) { const m = this.mem.get(file); return m ? { buf: m.buf, type } : null; }
    const full = path.join(this.dir, file);
    return fs.existsSync(full) ? { buf: fs.readFileSync(full), type } : null;
  }

  // Ролики живут сутки: их назначение — отправить сразу после боя.
  cleanup(now = Date.now()) {
    if (!this.dir) {
      for (const [k, v] of this.mem) if (now - v.at > KEEP_MS) this.mem.delete(k);
      return;
    }
    for (const f of fs.readdirSync(this.dir)) {
      const full = path.join(this.dir, f);
      try { if (now - fs.statSync(full).mtimeMs > KEEP_MS) fs.unlinkSync(full); } catch { /* уже удалён */ }
    }
  }
}
