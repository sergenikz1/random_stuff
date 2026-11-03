// Сервер: монеты, кристаллы, сундуки, дорога трофеев и магазин.
import {
  CHESTS, CHEST_SLOTS, CHEST_CYCLE, CHEST_POOL, DUPLICATE_COINS, gemsToOpen, ROAD, ARENAS, arenaOf,
  shopOffersForDay, SHOP_CHESTS, BOOSTS, randIn,
} from '../economy.js';
import { dayKey, levelInfo, cosmeticName } from '../pass.js';
import { UserError, unlockedOf } from './store.js';

export function ensureEco(p) {
  if (p.coins === undefined) p.coins = 0;
  if (p.gems === undefined) p.gems = 0;
  if (!Array.isArray(p.chests)) p.chests = Array(CHEST_SLOTS).fill(null);
  if (p.chestCycle === undefined) p.chestCycle = 0;
  if (!Array.isArray(p.roadClaimed)) p.roadClaimed = [];
  if (p.boostUntil === undefined) p.boostUntil = 0;
  if (!p.shopBought || p.shopBought.day !== dayKey()) p.shopBought = { day: dayKey(), ids: [] };
  p.bestRating = Math.max(p.bestRating || 0, p.rating || 0);
  return p;
}

const chestState = (c, now) => (!c ? 'empty' : !c.unlockAt ? 'locked' : c.unlockAt > now ? 'unlocking' : 'ready');

// Сундук за победу в рейтинге: следующий по циклу, если есть свободный слот.
export function awardWinChest(p) {
  ensureEco(p);
  const slot = p.chests.findIndex((c) => !c);
  if (slot < 0) return { full: true };
  const type = CHEST_CYCLE[p.chestCycle % CHEST_CYCLE.length];
  p.chestCycle += 1;
  p.chests[slot] = { type, at: Date.now(), unlockAt: 0 };
  return { slot, type };
}

function putChest(p, type) {
  const slot = p.chests.findIndex((c) => !c);
  if (slot < 0) throw new UserError('Все слоты сундуков заняты — откройте один');
  p.chests[slot] = { type, at: Date.now(), unlockAt: 0 };
  return slot;
}

export function unlockChest(p, slot, now = Date.now()) {
  ensureEco(p);
  const c = p.chests[slot];
  if (!c) throw new UserError('Слот пуст');
  if (c.unlockAt) throw new UserError('Сундук уже открывается');
  if (p.chests.some((x) => x && chestState(x, now) === 'unlocking')) throw new UserError('Одновременно открывается только один сундук');
  c.unlockAt = now + CHESTS[c.type].unlockMin * 60000;
  return c;
}

// Содержимое сундука считает только сервер. rnd — генератор для тестов.
export function rollChest(p, type, rnd = Math.random) {
  const def = CHESTS[type];
  const owned = new Set(unlockedOf(p));
  const out = { type, coins: randIn(def.coins, rnd), gems: 0, items: [], xp: def.xp };
  if (rnd() < def.gemChance) out.gems = randIn(def.gems, rnd);
  // Деревянный сундук — один шанс на предмет, остальные — столько попыток, сколько предметов.
  const tries = Math.max(1, def.items);
  for (let i = 0; i < tries; i++) {
    if (rnd() >= def.itemChance) continue;
    const key = CHEST_POOL[Math.floor(rnd() * CHEST_POOL.length)];
    const duplicate = owned.has(key) || out.items.some((x) => x.key === key);
    out.items.push({ key, name: cosmeticName(key), duplicate });
    if (duplicate) out.coins += DUPLICATE_COINS;
  }
  return out;
}

function applyLoot(p, loot) {
  const before = levelInfo(p.xp).level;
  p.coins += loot.coins;
  p.gems += loot.gems;
  p.xp += loot.xp;
  for (const it of loot.items) if (!it.duplicate && !p.extraUnlocks.includes(it.key)) p.extraUnlocks.push(it.key);
  return { ...loot, levelBefore: before, levelAfter: levelInfo(p.xp).level };
}

export function openChest(p, slot, { gems = false } = {}, rnd = Math.random, now = Date.now()) {
  ensureEco(p);
  const c = p.chests[slot];
  if (!c) throw new UserError('Слот пуст');
  const st = chestState(c, now);
  let spent = 0;
  if (st !== 'ready') {
    if (!gems) throw new UserError('Сундук ещё не открылся');
    const left = st === 'unlocking' ? c.unlockAt - now : CHESTS[c.type].unlockMin * 60000;
    spent = gemsToOpen(left);
    if (p.gems < spent) throw new UserError(`Нужно ${spent} 💎`);
    p.gems -= spent;
  }
  p.chests[slot] = null;
  return { ...applyLoot(p, rollChest(p, c.type, rnd)), spent };
}

export function claimRoad(p, at) {
  ensureEco(p);
  const step = ROAD.find((r) => r.at === Number(at));
  if (!step) throw new UserError('Нет такой награды');
  if (p.roadClaimed.includes(step.at)) throw new UserError('Награда уже получена');
  if (p.bestRating < step.at) throw new UserError(`Откроется на ${step.at} 🏆`);
  const r = step.reward;
  if (r.chest) putChest(p, r.chest);
  if (r.coins) p.coins += r.coins;
  if (r.gems) p.gems += r.gems;
  if (r.item && !p.extraUnlocks.includes(r.item)) p.extraUnlocks.push(r.item);
  p.roadClaimed.push(step.at);
  return { reward: r };
}

export function buy(p, id, rnd = Math.random, now = Date.now()) {
  ensureEco(p);
  const offer = shopOffersForDay(dayKey()).find((o) => o.id === id);
  if (offer) {
    if (unlockedOf(p).includes(offer.item)) throw new UserError('Уже есть в коллекции');
    if (p.coins < offer.coins) throw new UserError(`Нужно ${offer.coins} 🪙`);
    p.coins -= offer.coins;
    p.extraUnlocks.push(offer.item);
    p.shopBought.ids.push(id);
    return { item: offer.item, name: cosmeticName(offer.item) };
  }
  const chest = SHOP_CHESTS.find((c) => c.id === id);
  if (chest) {
    if (p.gems < chest.gems) throw new UserError(`Нужно ${chest.gems} 💎`);
    p.gems -= chest.gems;
    // Сундук из магазина открывается сразу, слот не нужен.
    return { loot: applyLoot(p, rollChest(p, chest.chest, rnd)) };
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
    chests: p.chests.map((c, slot) => {
      if (!c) return { slot, state: 'empty' };
      const state = chestState(c, now);
      const msLeft = state === 'unlocking' ? c.unlockAt - now : state === 'locked' ? CHESTS[c.type].unlockMin * 60000 : 0;
      return { slot, type: c.type, state, msLeft, openGems: state === 'ready' ? 0 : gemsToOpen(msLeft) };
    }),
    trophies: p.rating,
    bestRating: p.bestRating,
    arena,
    nextArena: next,
    road: ROAD.map((r) => ({ ...r, status: p.roadClaimed.includes(r.at) ? 'claimed' : p.bestRating >= r.at ? 'ready' : 'locked' })),
    boostMs: Math.max(0, (p.boostUntil || 0) - now),
    shop: {
      day: dayKey(),
      offers: shopOffersForDay(dayKey()).map((o) => ({ ...o, name: cosmeticName(o.item), owned: owned.has(o.item) })),
      chests: SHOP_CHESTS,
      boosts: BOOSTS,
    },
  };
}
