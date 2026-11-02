// Премиум-пропуск за Telegram Stars и выдача подарков Telegram.
//
// Покупка: клиент просит счёт → сервер создаёт ссылку createInvoiceLink (валюта XTR) →
// Telegram.WebApp.openInvoice → бот получает pre_checkout_query (проверяем и подтверждаем)
// → successful_payment (выдаём премиум). Подарки бот отправляет методом sendGift
// из собственного баланса звёзд, поэтому сумма подарков должна быть меньше цены пропуска.
import { SEASON, PREMIUM_REWARDS, isGift, giftStars, levelInfo } from '../pass.js';
import { isPremium, privateProfile, UserError } from './store.js';

export class Premium {
  constructor(store, { bot = null, hub = null, price, devPayments = false } = {}) {
    this.store = store;
    this.bot = bot;
    this.hub = hub;
    this.price = price;
    this.devPayments = devPayments;
    this.sending = new Set();
  }

  payloadFor(p) { return `pass:${SEASON.id}:${p.id}`; }

  async invoice(p) {
    if (isPremium(p)) throw new UserError('Премиум-пропуск этого сезона уже куплен');
    if (!this.bot) {
      if (this.devPayments) return { dev: true };
      throw new UserError('Оплата звёздами работает только в Telegram');
    }
    const link = await this.bot.createInvoiceLink({
      title: `Премиум-пропуск · ${SEASON.name}`,
      description: 'Премиум-трек: подарки Telegram, эксклюзивные эффекты и аватары, +20% опыта до конца сезона.',
      payload: this.payloadFor(p),
      currency: 'XTR',
      prices: [{ label: 'Премиум-пропуск', amount: this.price }],
    });
    return { link };
  }

  // pre_checkout_query: отвечаем за 10 секунд, что покупку можно провести.
  checkout(q) {
    const m = /^pass:(\d+):(\w+)$/.exec(q.invoice_payload || '');
    if (!m) return { ok: false, error: 'Неизвестный товар' };
    if (Number(m[1]) !== SEASON.id) return { ok: false, error: 'Сезон уже закончился' };
    if (q.currency !== 'XTR' || q.total_amount !== this.price) return { ok: false, error: 'Цена изменилась, откройте счёт заново' };
    const p = this.store.get(m[2]);
    if (!p) return { ok: false, error: 'Игрок не найден' };
    if (p.tgId && q.from && Number(q.from.id) !== Number(p.tgId)) return { ok: false, error: 'Счёт выставлен другому игроку' };
    if (isPremium(p)) return { ok: false, error: 'Пропуск уже куплен' };
    return { ok: true };
  }

  // successful_payment: выдаём премиум. Повторное уведомление не выдаёт его дважды.
  paid(payment, fromId) {
    const m = /^pass:(\d+):(\w+)$/.exec(payment.invoice_payload || '');
    if (!m) return null;
    const p = this.store.get(m[2]);
    if (!p) return null;
    if (p.premium?.chargeId === payment.telegram_payment_charge_id) return p;
    p.premium = {
      season: Number(m[1]), at: Date.now(), amount: payment.total_amount,
      chargeId: payment.telegram_payment_charge_id, buyer: fromId || null,
    };
    this.store.addFeed('🌟', `${p.name} получает премиум-пропуск`);
    this.store.save();
    this.pushProfile(p);
    return p;
  }

  // Режим разработки (без бота): «покупка» без оплаты, чтобы проверить премиум-трек.
  devGrant(p) {
    if (!this.devPayments || this.bot) throw new UserError('Недоступно');
    return this.paid({ invoice_payload: this.payloadFor(p), total_amount: this.price, telegram_payment_charge_id: `dev-${Date.now()}` });
  }

  // Забрать подарок с уровня премиум-трека.
  async claimGift(p, level) {
    const lvl = Number(level);
    const key = (PREMIUM_REWARDS[lvl] || []).find(isGift);
    if (!key) throw new UserError('На этом уровне нет подарка');
    if (!isPremium(p)) throw new UserError('Подарки — в премиум-пропуске');
    if (levelInfo(p.xp).level < lvl) throw new UserError(`Подарок откроется на ${lvl} уровне`);
    const id = `${SEASON.id}:${lvl}`;
    const rec = p.gifts[id];
    if (rec && (rec.status === 'sent' || rec.status === 'sending')) throw new UserError('Подарок уже отправлен');
    if (this.sending.has(`${p.id}:${id}`)) throw new UserError('Подарок уже отправляется');
    if (!p.tgId) throw new UserError('Подарки отправляются только пользователям Telegram');
    if (!this.bot) {
      if (!this.devPayments) throw new UserError('Бот не настроен');
      p.gifts[id] = { status: 'sent', at: Date.now(), giftId: 'dev' };
      this.store.save();
      return { status: 'sent' };
    }
    this.sending.add(`${p.id}:${id}`);
    p.gifts[id] = { status: 'sending', at: Date.now() };
    try {
      const gift = await this.pickGift(giftStars(key));
      await this.bot.sendGift({ user_id: p.tgId, gift_id: gift.id, text: `PushDuel · награда за ${lvl} уровень пропуска 💪` });
      p.gifts[id] = { status: 'sent', at: Date.now(), giftId: gift.id };
      this.store.addFeed('🎁', `${p.name} получает подарок Telegram за ${lvl} уровень пропуска`);
      return { status: 'sent' };
    } catch (e) {
      console.warn('sendGift:', e.message);
      p.gifts[id] = { status: 'failed', at: Date.now(), error: /balance|not enough|STARS/i.test(e.message) ? 'У бота закончились звёзды — подарок придёт позже' : 'Telegram не принял подарок, попробуйте позже' };
      return { status: 'failed', error: p.gifts[id].error };
    } finally {
      this.sending.delete(`${p.id}:${id}`);
      this.store.save();
      this.pushProfile(p);
    }
  }

  // Подарок нужной цены из каталога Telegram; лимитированные и распроданные пропускаем.
  async pickGift(stars) {
    const { gifts } = await this.bot.getAvailableGifts();
    const usable = gifts.filter((g) => !g.total_count || g.remaining_count > 0);
    const exact = usable.filter((g) => g.star_count === stars);
    const pool = exact.length ? exact : usable.filter((g) => g.star_count < stars).sort((a, b) => b.star_count - a.star_count).slice(0, 1);
    if (!pool.length) throw new Error(`no gift for ${stars} stars`);
    return pool[Math.floor(Math.random() * pool.length)];
  }

  pushProfile(p) {
    const conn = this.hub?.conns.get(p.id);
    if (conn && conn.ws.readyState === 1) conn.ws.send(JSON.stringify({ t: 'profile', profile: privateProfile(p, this.store) }));
  }
}
