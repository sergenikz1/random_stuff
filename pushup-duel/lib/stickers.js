// Наклейки: каталог, редкости, наборы. Наклейки клеятся на карточку игрока;
// эпические и легендарные — голографические и переливаются от наклона телефона.

export const STICKER_RARITY = {
  common: { name: 'Обычная',     color: '#9fb6cf', dupCoins: 5 },
  rare:   { name: 'Редкая',      color: '#43a8ff', dupCoins: 15 },
  epic:   { name: 'Эпическая',   color: '#b06bff', dupCoins: 40 },
  legend: { name: 'Легендарная', color: '#ffb31a', dupCoins: 120 },
};
export const STICKER_RARITY_ORDER = ['common', 'rare', 'epic', 'legend'];
export const MAX_COPIES = 3;        // лишние копии сверх трёх превращаются в монеты
export const CARD_STICKERS_MAX = 6; // столько наклеек помещается на карточку

// art — эмодзи или короткая надпись (text: true), bg — цвет подложки надписи.
export const STICKERS = [
  // Обычные
  { id: 'biceps',  art: '💪', name: 'Бицепс',          rarity: 'common' },
  { id: 'fire',    art: '🔥', name: 'Огонь',           rarity: 'common' },
  { id: 'bolt',    art: '⚡', name: 'Разряд',          rarity: 'common' },
  { id: 'cup',     art: '🏆', name: 'Кубок',           rarity: 'common' },
  { id: 'fist',    art: '👊', name: 'Кулак',           rarity: 'common' },
  { id: 'steam',   art: '😤', name: 'Пар из ушей',     rarity: 'common' },
  { id: 'salute',  art: '🫡', name: 'Есть!',           rarity: 'common' },
  { id: 'turtle',  art: '🐢', name: 'Медленно, но верно', rarity: 'common' },
  { id: 'gorilla', art: '🦍', name: 'Горилла',         rarity: 'common' },
  { id: 'chicken', art: '🍗', name: 'Белок',           rarity: 'common' },
  { id: 'sweat',   art: '💦', name: 'Пот',             rarity: 'common' },
  { id: 'target',  art: '🎯', name: 'Цель',            rarity: 'common' },
  { id: 'cool',    art: '😎', name: 'Красавчик',       rarity: 'common' },
  { id: 'plus1',   art: '+1', name: 'Плюс один',       rarity: 'common', text: true, bg: '#22c55e' },
  // Редкие
  { id: 'again',   art: 'ЕЩЁ РАЗ', name: 'Ещё раз',    rarity: 'rare', text: true, bg: '#2a7fff' },
  { id: 'max',     art: 'MAX', name: 'Максимум',        rarity: 'rare', text: true, bg: '#ef4444' },
  { id: 'nogiveup', art: 'НЕ СДАЮСЬ', name: 'Не сдаюсь', rarity: 'rare', text: true, bg: '#111827' },
  { id: 'brain',   art: '🧠', name: 'Мозг-качок',      rarity: 'rare' },
  { id: 'robo',    art: '🦾', name: 'Стальная рука',   rarity: 'rare' },
  { id: 'bear',    art: '🐻', name: 'Медведь',         rarity: 'rare' },
  { id: 'rocket',  art: '🚀', name: 'Ракета',          rarity: 'rare' },
  { id: 'volcano', art: '🌋', name: 'Вулкан',          rarity: 'rare' },
  { id: 'hot',     art: '🥵', name: 'Жара',            rarity: 'rare' },
  { id: 'medal',   art: '🥇', name: 'Золото',          rarity: 'rare' },
  // Эпические (голограмма)
  { id: 'dragon',  art: '🐉', name: 'Дракон',          rarity: 'epic' },
  { id: 'crown',   art: '👑', name: 'Корона',          rarity: 'epic' },
  { id: 'diamond', art: '💎', name: 'Алмаз',           rarity: 'epic' },
  { id: 'unicorn', art: '🦄', name: 'Единорог',        rarity: 'epic' },
  { id: 'galaxy',  art: '🌌', name: 'Галактика',       rarity: 'epic' },
  { id: 'beast',   art: 'ЗВЕРЬ', name: 'Зверь',        rarity: 'epic', text: true, bg: '#7c3aed' },
  { id: 'storm',   art: '🌪️', name: 'Смерч',          rarity: 'epic' },
  // Легендарные (голограмма + искры)
  { id: 'champ',   art: 'ЧЕМПИОН', name: 'Чемпион',    rarity: 'legend', text: true, bg: '#b45309' },
  { id: 'pushduel', art: 'PUSHDUEL', name: 'PushDuel', rarity: 'legend', text: true, bg: '#0b5fc0' },
  { id: 'comet',   art: '☄️', name: 'Комета',          rarity: 'legend' },
  { id: 'phoenix', art: '🐦‍🔥', name: 'Феникс',         rarity: 'legend' },
  { id: 'goat',    art: '🐐', name: 'Величайший',      rarity: 'legend' },
];

export const stickerById = (id) => STICKERS.find((s) => s.id === id);
export const stickersOf = (rarity) => STICKERS.filter((s) => s.rarity === rarity);

// Наборы наклеек (заменили сундуки): таймер открытия, сколько наклеек, гарантия редкости.
export const PACKS = {
  basic:  { name: 'Набор',             unlockMin: 10,  count: 3, guarantee: null,     coins: [20, 40],   gems: [0, 0],  gemChance: 0,   xp: 30,  colors: ['#7fd3ff', '#2a7fff'] },
  rare:   { name: 'Редкий набор',      unlockMin: 60,  count: 4, guarantee: 'rare',   coins: [50, 90],   gems: [1, 3],  gemChance: 0.3, xp: 60,  colors: ['#9fe3ff', '#0b9aa8'] },
  epic:   { name: 'Эпический набор',   unlockMin: 180, count: 5, guarantee: 'epic',   coins: [120, 200], gems: [3, 6],  gemChance: 0.6, xp: 120, colors: ['#d9a6ff', '#6a2cd8'] },
  legend: { name: 'Легендарный набор', unlockMin: 480, count: 6, guarantee: 'legend', coins: [250, 400], gems: [8, 15], gemChance: 1,   xp: 250, colors: ['#ffe27a', '#d9770b'] },
};

// Шансы редкости для каждой наклейки в наборе (кроме гарантированной).
export const RARITY_ODDS = { common: 0.72, rare: 0.22, epic: 0.05, legend: 0.01 };

export function rollRarity(rnd = Math.random) {
  let x = rnd();
  for (const r of STICKER_RARITY_ORDER) {
    if (x < RARITY_ODDS[r]) return r;
    x -= RARITY_ODDS[r];
  }
  return 'common';
}
