// Кто пришёл: пользователь Telegram (подпись initData) или гость (только для разработки).
import crypto from 'node:crypto';
import { validateInitData } from './telegram.js';

// auth — строка вида «tma <initData>» или «guest <token>» (заголовок Authorization или hello по WebSocket).
// startParam — параметр запуска (ref_…, duel_…, clan_…), если клиент открыт по ссылке.
export function authenticate(auth, config, startParamHint = '') {
  const s = String(auth || '');
  const sp = /^[A-Za-z0-9_-]{0,64}$/.test(startParamHint) ? startParamHint : '';
  if (s.startsWith('tma ') && config.botToken) {
    const v = validateInitData(s.slice(4), config.botToken, config.initDataMaxAge);
    if (!v) return null;
    const u = v.user;
    const name = [u.first_name, u.last_name].filter(Boolean).join(' ') || u.username || 'Игрок';
    return { id: `tg${u.id}`, tgId: u.id, name, username: u.username || '', photo: u.photo_url || '', startParam: v.startParam || sp };
  }
  if (s.startsWith('guest ') && config.allowGuests) {
    const token = s.slice(6);
    if (token.length < 16 || token.length > 128) return null;
    const id = `g${crypto.createHash('sha256').update(token).digest('hex').slice(0, 14)}`;
    return { id, tgId: null, name: '', username: '', startParam: sp };
  }
  return null;
}

// Достаёт id пригласившего из параметра запуска ref_<id>.
export function refFrom(startParam) {
  const m = /^ref_([a-z0-9]{3,24})$/i.exec(startParam || '');
  return m ? m[1] : '';
}
