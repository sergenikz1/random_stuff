// Экономика: монеты, кристаллы и билеты, наборы наклеек, арены и дорога трофеев, магазин.
// Общий модуль: описания читает клиент, а случайности и начисления считает только сервер.
//
// Кристаллы и билеты только зарабатываются и за Telegram Stars не продаются: иначе наборы
// со случайными наклейками стали бы платными лутбоксами.
import { PACKS, STICKERS, STICKER_RARITY } from './stickers.js';

export { PACKS };

// ---- Наборы наклеек (слоты и таймеры) ----
export const PACK_SLOTS = 4;

// Порядок наборов за победы — фиксированный цикл, чтобы редкие приходили предсказуемо.
export const PACK_CYCLE = ['basic', 'basic', 'rare', 'basic', 'rare', 'basic', 'epic', 'basic', 'rare', 'basic', 'rare', 'epic', 'basic', 'rare', 'legend'];

// Мгновенное открытие: 1 кристалл за каждые начатые 6 минут таймера.
export const gemsToOpen = (msLeft) => Math.max(1, Math.ceil(msLeft / 360000));

// Билет на турнир — за каждые 3 победы в рейтинге.
export const WINS_PER_TICKET = 3;

// Косметика, которая продаётся в магазине за монеты и встречается на дороге трофеев.
export const SHOP_POOL = [
  'avatar:🦈', 'avatar:🦉', 'avatar:🐝', 'avatar:🦩', 'avatar:🐧', 'avatar:🦬', 'avatar:🐊', 'avatar:🦥',
  'frame:ruby', 'frame:emerald', 'frame:sapphire', 'frame:obsidian',
  'title:lucky', 'title:collector', 'title:chesty',
];

// ---- Арены и дорога трофеев (кубки = рейтинг Эло) ----
export const ARENAS = [
  { id: 1, min: 0,    name: 'Агора',         emoji: '🫒', colors: ['#7fd36a', '#2f8f4a'] },
  { id: 2, min: 1050, name: 'Палестра',      emoji: '🤼', colors: ['#c9b08a', '#7a5a3a'] },
  { id: 3, min: 1150, name: 'Гимнасий',      emoji: '🏛️', colors: ['#ffbe5c', '#c9600f'] },
  { id: 4, min: 1300, name: 'Стадион',       emoji: '🏟️', colors: ['#5ee8d8', '#0b7fa8'] },
  { id: 5, min: 1450, name: 'Театр Диониса', emoji: '🎭', colors: ['#ff8fa5', '#b0173a'] },
  { id: 6, min: 1600, name: 'Олимпия',       emoji: '🔥', colors: ['#9fc4ff', '#2a4fd6'] },
  { id: 7, min: 1800, name: 'Олимп',         emoji: '⛰️', colors: ['#ffe27a', '#8a5a00'] },
];

export function arenaOf(trophies) {
  let a = ARENAS[0];
  for (const x of ARENAS) if (trophies >= x.min) a = x;
  return a;
}

// Награды дороги трофеев: забираются, когда лучший рейтинг дошёл до порога.
export const ROAD = [
  { at: 1025, reward: { coins: 50 } },
  { at: 1050, reward: { pack: 'rare' } },
  { at: 1075, reward: { tickets: 1 } },
  { at: 1100, reward: { gems: 10 } },
  { at: 1150, reward: { pack: 'epic' } },
  { at: 1200, reward: { coins: 150 } },
  { at: 1250, reward: { item: 'frame:ruby' } },
  { at: 1300, reward: { tickets: 2 } },
  { at: 1350, reward: { gems: 30 } },
  { at: 1400, reward: { pack: 'epic' } },
  { at: 1450, reward: { pack: 'legend' } },
  { at: 1500, reward: { item: 'title:collector' } },
  { at: 1550, reward: { tickets: 3 } },
  { at: 1600, reward: { pack: 'legend' } },
  { at: 1700, reward: { gems: 60 } },
  { at: 1800, reward: { item: 'frame:obsidian' } },
  { at: 1900, reward: { pack: 'legend' } },
  { at: 2000, reward: { gems: 100 } },
];

// ---- Магазин ----
export const PRICE = { avatar: 250, frame: 600, title: 400 };
export const STICKER_PRICE = { common: 60, rare: 180, epic: 500, legend: 1500 };
export const SHOP_PACKS = [
  { id: 'pack_rare',   pack: 'rare',   gems: 15 },
  { id: 'pack_epic',   pack: 'epic',   gems: 40 },
  { id: 'pack_legend', pack: 'legend', gems: 110 },
];
export const SHOP_TICKET = { id: 'ticket', gems: 25 };
export const BOOSTS = [
  { id: 'boost_xp', name: 'Двойной опыт на 3 часа', hours: 3, gems: 30 },
];
export const QUEST_GEMS = 5; // кристаллы за каждое выполненное задание дня

function seeded(seed) {
  let h = 2166136261;
  for (const ch of seed) h = Math.imul(h ^ ch.charCodeAt(0), 16777619) >>> 0;
  return () => { h = Math.imul(h ^ (h >>> 13), 2654435761) >>> 0; return h; };
}

// Предложения дня: три наклейки (одна — редкая или выше) и один предмет косметики.
// Одинаковые для всех, меняются в полночь МСК.
export function shopOffersForDay(day) {
  const next = seeded(`shop:${day}`);
  const out = [];
  const pick = (list) => list[next() % list.length];
  const rarePlus = STICKERS.filter((s) => s.rarity !== 'common');
  const common = STICKERS.filter((s) => s.rarity === 'common');
  const chosen = new Set();
  for (const pool of [common, common, rarePlus]) {
    let s = pick(pool);
    for (let i = 0; i < 10 && chosen.has(s.id); i++) s = pick(pool);
    chosen.add(s.id);
    out.push({ id: `offer_${day}_${out.length}`, sticker: s.id, coins: STICKER_PRICE[s.rarity] });
  }
  const item = pick(SHOP_POOL);
  out.push({ id: `offer_${day}_${out.length}`, item, coins: PRICE[item.split(':')[0]] || 300 });
  return out;
}

// Случайное число в диапазоне — принимает генератор, чтобы тесты были детерминированными.
export const randIn = ([a, b], rnd = Math.random) => a + Math.floor(rnd() * (b - a + 1));

export { STICKER_RARITY };
