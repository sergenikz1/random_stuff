// Экономика: монеты и кристаллы, сундуки, арены и дорога трофеев, магазин.
// Общий модуль: описания читает клиент, а случайности и начисления считает только сервер.
//
// Кристаллы только зарабатываются (дорога трофеев, задания, сундуки) и за Telegram Stars
// не продаются: иначе сундуки со случайным содержимым стали бы платными лутбоксами.

// ---- Сундуки ----
export const CHEST_SLOTS = 4;

export const CHESTS = {
  wood:   { name: 'Деревянный',  unlockMin: 10,  coins: [20, 40],   gems: [0, 0],  gemChance: 0,    items: 0, itemChance: 0.15, xp: 30,  colors: ['#c98b4f', '#7a4a22'] },
  silver: { name: 'Серебряный',  unlockMin: 60,  coins: [50, 90],   gems: [1, 3],  gemChance: 0.3,  items: 1, itemChance: 0.35, xp: 60,  colors: ['#eef3fa', '#8796ab'] },
  gold:   { name: 'Золотой',     unlockMin: 180, coins: [120, 200], gems: [3, 6],  gemChance: 0.6,  items: 1, itemChance: 0.7,  xp: 120, colors: ['#ffe27a', '#c98a0b'] },
  magic:  { name: 'Волшебный',   unlockMin: 480, coins: [250, 400], gems: [8, 15], gemChance: 1,    items: 2, itemChance: 1,    xp: 250, colors: ['#d9a6ff', '#6a2cd8'] },
};

// Порядок сундуков за победы — фиксированный цикл, чтобы редкие приходили предсказуемо.
export const CHEST_CYCLE = ['wood', 'wood', 'silver', 'wood', 'silver', 'wood', 'gold', 'wood', 'silver', 'wood', 'silver', 'gold', 'wood', 'silver', 'magic'];

// Мгновенное открытие: 1 кристалл за каждые начатые 6 минут таймера.
export const gemsToOpen = (msLeft) => Math.max(1, Math.ceil(msLeft / 360000));

// Косметика, которая выпадает только из сундуков и продаётся в магазине за монеты.
export const CHEST_POOL = [
  'avatar:🦈', 'avatar:🦉', 'avatar:🐝', 'avatar:🦩', 'avatar:🐧', 'avatar:🦬', 'avatar:🐊', 'avatar:🦥',
  'frame:ruby', 'frame:emerald', 'frame:sapphire', 'frame:obsidian',
  'title:lucky', 'title:collector', 'title:chesty',
];
export const DUPLICATE_COINS = 60; // повтор предмета превращается в монеты

// ---- Арены и дорога трофеев (кубки = рейтинг Эло) ----
export const ARENAS = [
  { id: 1, min: 0,    name: 'Дворовая площадка',   emoji: '🛝', colors: ['#7fd36a', '#2f8f4a'] },
  { id: 2, min: 1050, name: 'Подвальная качалка',  emoji: '🏚️', colors: ['#b4a08a', '#5d4a3a'] },
  { id: 3, min: 1150, name: 'Школьный спортзал',   emoji: '🏫', colors: ['#ffbe5c', '#c9600f'] },
  { id: 4, min: 1300, name: 'Фитнес-клуб',         emoji: '🏋️', colors: ['#5ee8d8', '#0b7fa8'] },
  { id: 5, min: 1450, name: 'Боксёрский ринг',     emoji: '🥊', colors: ['#ff8fa5', '#b0173a'] },
  { id: 6, min: 1600, name: 'Олимпийский стадион', emoji: '🏟️', colors: ['#9fc4ff', '#2a4fd6'] },
  { id: 7, min: 1800, name: 'Зал славы',           emoji: '🏛️', colors: ['#ffe27a', '#8a5a00'] },
];

export function arenaOf(trophies) {
  let a = ARENAS[0];
  for (const x of ARENAS) if (trophies >= x.min) a = x;
  return a;
}

// Награды дороги трофеев: забираются, когда лучший рейтинг дошёл до порога.
export const ROAD = [
  { at: 1025, reward: { coins: 50 } },
  { at: 1050, reward: { chest: 'silver' } },
  { at: 1075, reward: { gems: 10 } },
  { at: 1100, reward: { coins: 100 } },
  { at: 1150, reward: { chest: 'gold' } },
  { at: 1200, reward: { gems: 20 } },
  { at: 1250, reward: { item: 'frame:ruby' } },
  { at: 1300, reward: { chest: 'gold' } },
  { at: 1350, reward: { coins: 250 } },
  { at: 1400, reward: { gems: 30 } },
  { at: 1450, reward: { chest: 'magic' } },
  { at: 1500, reward: { item: 'title:collector' } },
  { at: 1550, reward: { coins: 400 } },
  { at: 1600, reward: { chest: 'magic' } },
  { at: 1700, reward: { gems: 60 } },
  { at: 1800, reward: { item: 'frame:obsidian' } },
  { at: 1900, reward: { chest: 'magic' } },
  { at: 2000, reward: { gems: 100 } },
];

// ---- Магазин ----
export const PRICE = { avatar: 250, frame: 600, title: 400 };
export const SHOP_CHESTS = [
  { id: 'chest_silver', chest: 'silver', gems: 15 },
  { id: 'chest_gold',   chest: 'gold',   gems: 40 },
  { id: 'chest_magic',  chest: 'magic',  gems: 110 },
];
export const BOOSTS = [
  { id: 'boost_xp', name: 'Двойной опыт на 3 часа', hours: 3, gems: 30 },
];
export const QUEST_GEMS = 5; // кристаллы за каждое выполненное задание дня

// Три предложения дня за монеты — одинаковые для всех, меняются в полночь МСК.
export function shopOffersForDay(day) {
  let h = 2166136261;
  for (const ch of `shop:${day}`) h = Math.imul(h ^ ch.charCodeAt(0), 16777619) >>> 0;
  const pool = [...CHEST_POOL];
  const out = [];
  while (out.length < 3 && pool.length) {
    h = Math.imul(h ^ (h >>> 13), 2654435761) >>> 0;
    const item = pool.splice(h % pool.length, 1)[0];
    const cat = item.split(':')[0];
    out.push({ id: `offer_${day}_${out.length}`, item, coins: PRICE[cat] || 300 });
  }
  return out;
}

// Случайное число в диапазоне — принимает генератор, чтобы тесты были детерминированными.
export const randIn = ([a, b], rnd = Math.random) => a + Math.floor(rnd() * (b - a + 1));
