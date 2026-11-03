// Сервер: монеты, кристаллы, билеты, наборы наклеек, коллекция и карточка, дорога трофеев,
// магазин и «почта» с наградами (турнир, пояса).
import {
  PACKS, PACK_SLOTS, PACK_CYCLE, gemsToOpen, ROAD, ARENAS, arenaOf, WINS_PER_TICKET,
  shopOffersForDay, SHOP_PACKS, SHOP_TICKET, BOOSTS, randIn,
} from '../economy.js';
import {
  STICKERS, STICKER_RARITY, MAX_COPIES, CARD_STICKERS_MAX, stickerById, stickersOf, rollRarity,
} from '../stickers.js';
import { dayKey, levelInfo, cosmeticName } from '../pass.js';
import { UserError, unlockedOf } from './store.js';

export function ensureEco(p) {
  if (p.coins === undefined) p.coins = 0;
  if (p.gems === undefined) p.gems = 0;
  if (p.tickets === undefined) p.tickets = 0;
  if (!Array.isArray(p.packs)) p.packs = Array(PACK_SLOTS).fill(null);
  if (p.packCycle === undefined) p.packCycle = 0;
  if (p.winsTowardTicket === undefined) p.winsTowardTicket = 0;
  if (!Array.isArray(p.roadClaimed)) p.roadClaimed = [];
  if (p.boostUntil === undefined) p.boostUntil = 0;
  if (!p.stickers || typeof p.stickers !== 'object') p.stickers = {};
  if (!Array.isArray(p.card)) p.card = [];
  if (!Array.isArray(p.inbox)) p.inbox = [];
  if (!p.shopBought || p.shopBought.day !== dayKey()) p.shopBought = { day: dayKey(), ids: [] };
  p.bestRating = Math.max(p.bestRating || 0, p.rating || 0);
  return p;
}

const packState = (c, now) => (!c ? 'empty' : !c.unlockAt ? 'locked' : c.unlockAt > now ? 'unlocking' : 'ready');

// Набор и прогресс к билету за победу в рейтинге.
export function awardWin(p) {
  ensureEco(p);
  const out = {};
  p.winsTowardTicket += 1;
  if (p.winsTowardTicket >= WINS_PER_TICKET) {
    p.winsTowardTicket = 0;
    p.tickets += 1;
    out.ticket = true;
  }
  const slot = p.packs.findIndex((c) => !c);
  if (slot < 0) return { ...out, full: true };
  const type = PACK_CYCLE[p.packCycle % PACK_CYCLE.length];
  p.packCycle += 1;
  p.packs[slot] = { type, at: Date.now(), unlockAt: 0 };
  return { ...out, slot, type };
}

function putPack(p, type) {
  const slot = p.packs.findIndex((c) => !c);
  if (slot < 0) throw new UserError('Все слоты наборов заняты — откройте один');
  p.packs[slot] = { type, at: Date.now(), unlockAt: 0 };
  return slot;
}

export function unlockPack(p, slot, now = Date.now()) {
  ensureEco(p);
  const c = p.packs[slot];
  if (!c) throw new UserError('Слот пуст');
  if (c.unlockAt) throw new UserError('Набор уже открывается');
  if (p.packs.some((x) => x && packState(x, now) === 'unlocking')) throw new UserError('Одновременно открывается только один набор');
  c.unlockAt = now + PACKS[c.type].unlockMin * 60000;
  return c;
}

// Добавить наклейку в коллекцию; сверх MAX_COPIES — монеты. Возвращает описание для экрана.
function addSticker(p, id) {
  const s = stickerById(id);
  const have = p.stickers[id] || 0;
  if (have >= MAX_COPIES) {
    const coins = STICKER_RARITY[s.rarity].dupCoins;
    p.coins += coins;
    return { id, rarity: s.rarity, isNew: false, converted: coins };
  }
  p.stickers[id] = have + 1;
  return { id, rarity: s.rarity, isNew: have === 0, converted: 0 };
}

// Содержимое набора считает только сервер. rnd — генератор для тестов.
export function rollPack(type, rnd = Math.random) {
  const def = PACKS[type];
  const out = { type, coins: randIn(def.coins, rnd), gems: 0, xp: def.xp, stickers: [] };
  if (rnd() < def.gemChance) out.gems = randIn(def.gems, rnd);
  for (let i = 0; i < def.count; i++) {
    const rarity = i === 0 && def.guarantee ? def.guarantee : rollRarity(rnd);
    const pool = stickersOf(rarity);
    out.stickers.push(pool[Math.floor(rnd() * pool.length)].id);
  }
  return out;
}

export function givePack(p, type, rnd = Math.random) {
  ensureEco(p);
  const roll = rollPack(type, rnd);
  const before = levelInfo(p.xp).level;
  p.coins += roll.coins;
  p.gems += roll.gems;
  p.xp += roll.xp;
  const stickers = roll.stickers.map((id) => addSticker(p, id));
  // Показываем по возрастанию редкости — самое ценное в конце.
  const order = ['common', 'rare', 'epic', 'legend'];
  stickers.sort((a, b) => order.indexOf(a.rarity) - order.indexOf(b.rarity));
  return { type, coins: roll.coins, gems: roll.gems, xp: roll.xp, stickers, levelBefore: before, levelAfter: levelInfo(p.xp).level };
}

export function openPack(p, slot, { gems = false } = {}, rnd = Math.random, now = Date.now()) {
  ensureEco(p);
  const c = p.packs[slot];
  if (!c) throw new UserError('Слот пуст');
  const st = packState(c, now);
  let spent = 0;
  if (st !== 'ready') {
    if (!gems) throw new UserError('Набор ещё не открылся');
    const left = st === 'unlocking' ? c.unlockAt - now : PACKS[c.type].unlockMin * 60000;
    spent = gemsToOpen(left);
    if (p.gems < spent) throw new UserError(`Нужно ${spent} 💎`);
    p.gems -= spent;
  }
  p.packs[slot] = null;
  return { ...givePack(p, c.type, rnd), spent };
}

// Раскладка наклеек на карточке: x, y — доли карточки, r — поворот в градусах, s — масштаб.
export function setCard(p, items) {
  ensureEco(p);
  if (!Array.isArray(items) || items.length > CARD_STICKERS_MAX) throw new UserError(`На карточку помещается до ${CARD_STICKERS_MAX} наклеек`);
  const used = {};
  const clean = items.map((it) => {
    const id = String(it?.id || '');
    if (!stickerById(id)) throw new UserError('Неизвестная наклейка');
    used[id] = (used[id] || 0) + 1;
    if (used[id] > (p.stickers[id] || 0)) throw new UserError('Такой наклейки нет в коллекции (или копий меньше)');
    const num = (v, lo, hi, d) => (Number.isFinite(Number(v)) ? Math.min(hi, Math.max(lo, Number(v))) : d);
    return { id, x: num(it.x, 0, 1, 0.5), y: num(it.y, 0, 1, 0.5), r: num(it.r, -180, 180, 0), s: num(it.s, 0.5, 2, 1) };
  });
  p.card = clean;
  return clean;
}

// ---- Почта с наградами ----
export function addInbox(p, item) {
  ensureEco(p);
  p.inbox.push({ id: Math.random().toString(36).slice(2, 10), at: Date.now(), ...item });
  if (p.inbox.length > 30) p.inbox.splice(0, p.inbox.length - 30);
}

export function claimInbox(p, id, rnd = Math.random) {
  ensureEco(p);
  const i = p.inbox.findIndex((x) => x.id === id);
  if (i < 0) throw new UserError('Награда не найдена');
  const it = p.inbox[i];
  if (it.gift) throw new UserError('Подарок Telegram забирается кнопкой «Повторить отправку»');
  p.inbox.splice(i, 1);
  if (it.gems) p.gems += it.gems;
  if (it.coins) p.coins += it.coins;
  if (it.tickets) p.tickets += it.tickets;
  const stickers = [];
  if (it.sticker) stickers.push(addSticker(p, it.sticker));
  const loot = it.pack ? givePack(p, it.pack, rnd) : null;
  if (loot && stickers.length) loot.stickers.push(...stickers);
  return { item: it, loot: loot || (stickers.length ? { type: 'basic', coins: 0, gems: 0, xp: 0, stickers, levelBefore: 0, levelAfter: 0 } : null) };
}

export function claimRoad(p, at) {
  ensureEco(p);
  const step = ROAD.find((r) => r.at === Number(at));
  if (!step) throw new UserError('Нет такой награды');
  if (p.roadClaimed.includes(step.at)) throw new UserError('Награда уже получена');
  if (p.bestRating < step.at) throw new UserError(`Откроется на ${step.at} 🏆`);
  const r = step.reward;
  if (r.pack) putPack(p, r.pack);
  if (r.coins) p.coins += r.coins;
  if (r.gems) p.gems += r.gems;
  if (r.tickets) p.tickets += r.tickets;
  if (r.item && !p.extraUnlocks.includes(r.item)) p.extraUnlocks.push(r.item);
  p.roadClaimed.push(step.at);
  return { reward: r };
}

export function buy(p, id, rnd = Math.random, now = Date.now()) {
  ensureEco(p);
  const offer = shopOffersForDay(dayKey()).find((o) => o.id === id);
  if (offer) {
    if (p.shopBought.ids.includes(id)) throw new UserError('Уже куплено сегодня');
    if (offer.item && unlockedOf(p).includes(offer.item)) throw new UserError('Уже есть в коллекции');
    if (p.coins < offer.coins) throw new UserError(`Нужно ${offer.coins} 🪙`);
    p.coins -= offer.coins;
    p.shopBought.ids.push(id);
    if (offer.item) { p.extraUnlocks.push(offer.item); return { name: cosmeticName(offer.item) }; }
    const got = addSticker(p, offer.sticker);
    return { name: `наклейка «${stickerById(offer.sticker).name}»`, sticker: got };
  }
  const pack = SHOP_PACKS.find((c) => c.id === id);
  if (pack) {
    if (p.gems < pack.gems) throw new UserError(`Нужно ${pack.gems} 💎`);
    p.gems -= pack.gems;
    return { loot: givePack(p, pack.pack, rnd) }; // набор из магазина открывается сразу
  }
  if (id === SHOP_TICKET.id) {
    if (p.gems < SHOP_TICKET.gems) throw new UserError(`Нужно ${SHOP_TICKET.gems} 💎`);
    p.gems -= SHOP_TICKET.gems;
    p.tickets += 1;
    return { tickets: p.tickets };
  }
  const boost = BOOSTS.find((b) => b.id === id);
  if (boost) {
    if (p.gems < boost.gems) throw new UserError(`Нужно ${boost.gems} 💎`);
    p.gems -= boost.gems;
    p.boostUntil = Math.max(now, p.boostUntil) + boost.hours * 3600000;
    return { boostUntil: p.boostUntil };
  }
  throw new UserError('Предложение не найдено');
}

export const boostActive = (p, now = Date.now()) => (p.boostUntil || 0) > now;

// Всё про экономику для профиля игрока.
export function ecoView(p, now = Date.now()) {
  ensureEco(p);
  const owned = new Set(unlockedOf(p));
  const arena = arenaOf(p.rating);
  const next = ARENAS.find((a) => a.min > p.rating) || null;
  return {
    coins: p.coins,
    gems: p.gems,
    tickets: p.tickets,
    winsTowardTicket: p.winsTowardTicket,
    packs: p.packs.map((c, slot) => {
      if (!c) return { slot, state: 'empty' };
      const state = packState(c, now);
      const msLeft = state === 'unlocking' ? c.unlockAt - now : state === 'locked' ? PACKS[c.type].unlockMin * 60000 : 0;
      return { slot, type: c.type, state, msLeft, openGems: state === 'ready' ? 0 : gemsToOpen(msLeft) };
    }),
    stickers: p.stickers,
    collected: Object.keys(p.stickers).length,
    totalStickers: STICKERS.length,
    inbox: p.inbox,
    trophies: p.rating,
    bestRating: p.bestRating,
    arena,
    nextArena: next,
    road: ROAD.map((r) => ({ ...r, status: p.roadClaimed.includes(r.at) ? 'claimed' : p.bestRating >= r.at ? 'ready' : 'locked' })),
    boostMs: Math.max(0, (p.boostUntil || 0) - now),
    shop: {
      day: dayKey(),
      offers: shopOffersForDay(dayKey()).map((o) => ({
        ...o,
        name: o.item ? cosmeticName(o.item) : stickerById(o.sticker).name,
        owned: o.item ? owned.has(o.item) : false,
        bought: p.shopBought.ids.includes(o.id),
      })),
      packs: SHOP_PACKS,
      ticket: SHOP_TICKET,
      boosts: BOOSTS,
    },
  };
}
