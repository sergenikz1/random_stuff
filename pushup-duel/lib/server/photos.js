// Фото профиля Telegram для аватаров.
// 1) Если мини-приложение прислало photo_url в initData — просто перенаправляем на него.
// 2) Иначе, если есть токен бота, берём последнее фото через Bot API и отдаём сами
//    (ссылка на файл содержит токен, поэтому клиенту её не показываем). Кэш — в памяти.
const OK_URL = /^https:\/\/([a-z0-9-]+\.)*(t\.me|telegram\.org|telegram\.me|telesco\.pe)\//i;
export const isPhotoUrl = (u) => typeof u === 'string' && u.length < 500 && OK_URL.test(u);

const DAY = 24 * 3600e3;

export class Photos {
  constructor(store, { botToken = '', fetchImpl = globalThis.fetch } = {}) {
    this.store = store;
    this.botToken = botToken;
    this.fetch = fetchImpl;
    this.cache = new Map(); // id → { at, buf, type } | { at, none: true }
  }

  // Есть ли у игрока фото, которое мы можем показать.
  has(p) {
    return !!p && (isPhotoUrl(p.tgPhoto) || (!!this.botToken && !!p.tgId));
  }

  // Ответ для GET /api/photo/<id>: { redirect } | { buf, type } | null.
  async get(id) {
    const p = this.store.players.get(String(id));
    if (!p) return null;
    if (isPhotoUrl(p.tgPhoto)) return { redirect: p.tgPhoto };
    if (!this.botToken || !p.tgId) return null;
    const hit = this.cache.get(p.id);
    if (hit && Date.now() - hit.at < (hit.none ? DAY / 24 : DAY)) return hit.none ? null : hit;
    try {
      const call = async (method, body) => {
        const r = await this.fetch(`https://api.telegram.org/bot${this.botToken}/${method}`, {
          method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body),
        });
        const j = await r.json();
        if (!j.ok) throw new Error(j.description);
        return j.result;
      };
      const photos = await call('getUserProfilePhotos', { user_id: p.tgId, limit: 1 });
      const sizes = photos.photos?.[0];
      if (!sizes?.length) { this.cache.set(p.id, { at: Date.now(), none: true }); return null; }
      // Берём размер около 320 px — аватару больше не нужно.
      const size = sizes.find((s) => s.width >= 300) || sizes[sizes.length - 1];
      const file = await call('getFile', { file_id: size.file_id });
      const img = await this.fetch(`https://api.telegram.org/file/bot${this.botToken}/${file.file_path}`);
      if (!img.ok) throw new Error(`file ${img.status}`);
      const out = { at: Date.now(), buf: Buffer.from(await img.arrayBuffer()), type: img.headers.get('content-type') || 'image/jpeg' };
      this.cache.set(p.id, out);
      return out;
    } catch {
      this.cache.set(p.id, { at: Date.now(), none: true });
      return null;
    }
  }
}
