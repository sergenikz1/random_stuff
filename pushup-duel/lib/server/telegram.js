// Telegram: проверка initData мини-приложения и маленький бот на long polling.
import crypto from 'node:crypto';

// https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app
export function validateInitData(initData, botToken, maxAgeSec = 24 * 3600, now = Date.now()) {
  if (!initData || !botToken) return null;
  const params = new URLSearchParams(initData);
  const hash = params.get('hash');
  if (!hash || !/^[0-9a-f]{64}$/i.test(hash)) return null;
  params.delete('hash');
  const dataCheck = [...params.entries()]
    .map(([k, v]) => `${k}=${v}`)
    .sort()
    .join('\n');
  const secret = crypto.createHmac('sha256', 'WebAppData').update(botToken).digest();
  const calc = crypto.createHmac('sha256', secret).update(dataCheck).digest();
  const given = Buffer.from(hash, 'hex');
  if (given.length !== calc.length || !crypto.timingSafeEqual(given, calc)) return null;
  const authDate = Number(params.get('auth_date')) || 0;
  if (maxAgeSec > 0 && now / 1000 - authDate > maxAgeSec) return null;
  let user = null;
  try { user = JSON.parse(params.get('user') || 'null'); } catch { return null; }
  if (!user || !user.id) return null;
  return { user, startParam: params.get('start_param') || '' };
}

// Подпись initData — нужна тестам, чтобы собрать «настоящие» данные Telegram.
export function signInitData(fields, botToken) {
  const params = new URLSearchParams(fields);
  const dataCheck = [...params.entries()].map(([k, v]) => `${k}=${v}`).sort().join('\n');
  const secret = crypto.createHmac('sha256', 'WebAppData').update(botToken).digest();
  params.set('hash', crypto.createHmac('sha256', secret).update(dataCheck).digest('hex'));
  return params.toString();
}

// Бот: на /start присылает кнопку, открывающую мини-приложение. Параметр после /start
// (ref_<id>, duel_<код>, clan_<код>) передаётся в приложение как ?sp=…, чтобы сработали
// приглашение, вызов на дуэль или вступление в клан.
export function startBot({ token, webAppUrl, onCheckout, onPaid, log = console }) {
  const api = async (method, body) => {
    const r = await fetch(`https://api.telegram.org/bot${token}/${method}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body || {}),
    });
    const j = await r.json();
    if (!j.ok) throw new Error(`${method}: ${j.description}`);
    return j.result;
  };
  const appUrl = (sp) => {
    if (!webAppUrl) return null;
    const url = new URL(webAppUrl);
    if (sp) url.searchParams.set('sp', sp);
    return url.toString();
  };

  let stopped = false;
  let offset = 0;

  if (webAppUrl) {
    api('setChatMenuButton', {
      menu_button: { type: 'web_app', text: 'Играть', web_app: { url: webAppUrl } },
    }).catch((e) => log.warn(e.message));
  }

  const texts = {
    duel: (code) => `Тебя вызвали на дуэль отжиманий! Комната ${code}. Жми кнопку — и в бой 💪`,
    clan: () => 'Тебя зовут в клан PushDuel. Жми кнопку, чтобы вступить 🛡️',
    def: () => 'PushDuel — дуэли отжиманий онлайн. Камера сама считает повторы, соперник отжимается одновременно с тобой. Кланы, рейтинг, боевой пропуск. Жми «Играть» 💪',
  };

  async function onMessage(msg) {
    if (msg.successful_payment) {
      const p = onPaid ? onPaid(msg.successful_payment, msg.from?.id) : null;
      await api('sendMessage', {
        chat_id: msg.chat.id,
        text: p ? 'Премиум-пропуск активирован! 🌟 Подарки Telegram ждут тебя на уровнях премиум-трека.' : 'Оплата получена, но игрок не найден — напиши нам, разберёмся.',
      });
      return;
    }
    if (typeof msg.text !== 'string' || !msg.text.startsWith('/start')) return;
    if (!webAppUrl) return;
    const payload = msg.text.split(/\s+/)[1] || '';
    const sp = /^(ref|duel|clan)_[A-Za-z0-9]{3,24}$/.test(payload) ? payload : '';
    const kind = sp.split('_')[0];
    await api('sendMessage', {
      chat_id: msg.chat.id,
      text: (texts[kind] || texts.def)(sp.split('_')[1]),
      reply_markup: { inline_keyboard: [[{ text: kind === 'duel' ? '⚔️ Принять вызов' : kind === 'clan' ? '🛡️ Вступить' : '💪 Играть', web_app: { url: appUrl(sp) } }]] },
    });
  }

  async function loop() {
    while (!stopped) {
      try {
        const updates = await api('getUpdates', { offset, timeout: 30, allowed_updates: ['message', 'pre_checkout_query'] });
        for (const upd of updates) {
          offset = upd.update_id + 1;
          if (upd.pre_checkout_query) {
            const q = upd.pre_checkout_query;
            const r = onCheckout ? onCheckout(q) : { ok: false, error: 'Покупки отключены' };
            await api('answerPreCheckoutQuery', { pre_checkout_query_id: q.id, ok: r.ok, ...(r.ok ? {} : { error_message: r.error }) })
              .catch((e) => log.warn('checkout:', e.message));
          }
          if (upd.message) await onMessage(upd.message).catch((e) => log.warn('bot:', e.message));
        }
      } catch (e) {
        log.warn('bot:', e.message);
        await new Promise((r) => setTimeout(r, 5000));
      }
    }
  }
  loop();

  return {
    stop: () => { stopped = true; },
    // Личное сообщение игроку с кнопкой в приложение (вызов на дуэль).
    notify: (chatId, text, buttonText, sp) => api('sendMessage', {
      chat_id: chatId,
      text,
      ...(appUrl(sp) ? { reply_markup: { inline_keyboard: [[{ text: buttonText, web_app: { url: appUrl(sp) } }]] } } : {}),
    }),
    createInvoiceLink: (body) => api('createInvoiceLink', body),
    getAvailableGifts: () => api('getAvailableGifts'),
    sendGift: (body) => api('sendGift', body),
  };
}
