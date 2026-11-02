// Соперники, их темп, рейтинг Эло и звания. Без DOM.

export const START_RATING = 1000;

export const RANKS = [
  { min: -Infinity, name: 'Новичок',  color: '#9aa3b5' },
  { min: 900,       name: 'Любитель', color: '#6fd3ff' },
  { min: 1100,      name: 'Атлет',    color: '#7cf29c' },
  { min: 1300,      name: 'Машина',   color: '#ffd23f' },
  { min: 1500,      name: 'Титан',    color: '#ff9f43' },
  { min: 1700,      name: 'Легенда',  color: '#ff5fa2' },
];

export function rankOf(rating) {
  let r = RANKS[0];
  for (const x of RANKS) if (rating >= x.min) r = x;
  return r;
}

const NAMES = [
  'Железный Ёж', 'Кот-Качок', 'Дед Бицепс', 'Гравитон', 'Сибирский Медведь',
  'Турбо-Бабушка', 'Трицепс 3000', 'Пельмень', 'Капитан Планка', 'Молния',
  'Бетономешалка', 'Тихий Жим', 'Лось', 'Мистер Пресс', 'Ракета',
  'Ночной Атлет', 'Борщ', 'Пружина', 'Гиря', 'Шторм',
];
export const AVATARS = ['🦊', '🐻', '🐯', '🦁', '🐺', '🐼', '🐸', '🦖', '🐙', '🦍', '🤖', '👽', '🥷', '🧙', '🦅', '🐲'];

const rand = (a, b) => a + Math.random() * (b - a);
const randInt = (a, b) => Math.floor(rand(a, b + 1));
const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];

export function makeOpponent(myRating, myAvatar) {
  const rating = Math.max(600, Math.round(myRating + randInt(-110, 150)));
  let avatar = pick(AVATARS);
  if (avatar === myAvatar) avatar = AVATARS[(AVATARS.indexOf(avatar) + 3) % AVATARS.length];
  return { name: pick(NAMES), avatar, rating, level: randInt(1, 20), badges: [], rank: rankOf(rating).name, title: '' };
}

// Бот-соперник: темп зависит от рейтинга, к концу он устаёт, иногда отдыхает.
// 1000 Эло ≈ 25 отжиманий в минуту, 1800 ≈ 45.
export class BotOpponent {
  constructor(rating, durationMs) {
    this.rating = rating;
    this.durationMs = durationMs;
    this.base = Math.min(1.1, Math.max(0.25, 0.47 + (rating - 1000) * 0.00052)) * rand(0.9, 1.1);
    this.count = 0;
    this.next = rand(900, 1700); // разминается не сразу
  }

  // elapsed — мс с начала боя. Возвращает количество новых повторов.
  tick(elapsed) {
    let added = 0;
    while (elapsed >= this.next && this.next <= this.durationMs) {
      this.count += 1;
      added += 1;
      const fatigue = 1 - 0.28 * Math.min(1, this.next / this.durationMs);
      let gap = (1000 / (this.base * fatigue)) * rand(0.75, 1.25);
      if (Math.random() < 0.06) gap += rand(1200, 3200); // передышка
      this.next += gap;
    }
    return added;
  }
}

// Эло: k побольше в первых матчах, чтобы рейтинг быстрее нашёл своё место.
export function eloDelta(my, opp, score, gamesPlayed) {
  const k = gamesPlayed < 10 ? 40 : 24;
  const expected = 1 / (1 + Math.pow(10, (opp - my) / 400));
  return Math.round(k * (score - expected));
}
