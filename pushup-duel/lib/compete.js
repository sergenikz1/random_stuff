// Чемпионские пояса арен и еженедельный турнир — общие описания.
import { weekKey } from './pass.js';

// ---- Пояса ----
// У каждой арены один пояс. Победил чемпиона своей арены в рейтинге или в титульном бою — пояс твой.
// Свободный пояс забирает первый, кто выиграл рейтинговый бой на этой арене.
// Кто держит пояс в момент смены недели, получает награду.
export const BELT_WEEKLY = (arenaId) => ({ gems: 10 + arenaId * 5, pack: arenaId >= 5 ? 'legend' : 'epic' });

// ---- Турнир недели ----
// Пятница 00:00 — воскресенье 23:59 по Москве. Вход — 1 билет, 10 матчей, 3 очка за победу, 1 за ничью.
export const TOURNEY = {
  matches: 10,
  winPoints: 3,
  drawPoints: 1,
  durationSec: 60,
  // Призы по местам. gift — подарок Telegram за столько звёзд (отправляет бот со своего баланса).
  prizes: [
    { places: [1, 1],   gift: 100, pack: 'legend', gems: 50, sticker: 'champ' },
    { places: [2, 3],   gift: 50,  pack: 'legend', gems: 30 },
    { places: [4, 10],  pack: 'epic', gems: 15 },
    { places: [11, 50], pack: 'rare', gems: 5 },
  ],
  minMatchesForPrize: 3, // чтобы попасть в призы, нужно сыграть хотя бы 3 матча
};

const MSK = 3 * 3600 * 1000;

// Неделя турнира = неделя (понедельник) по МСК; активен с пятницы по воскресенье включительно.
export function tourneyState(now = Date.now()) {
  const week = weekKey(new Date(now));
  const monday = Date.parse(`${week}T00:00:00Z`) - MSK;
  const start = monday + 4 * 86400000;
  const end = monday + 7 * 86400000;
  return { week, start, end, active: now >= start && now < end, upcoming: now < start };
}

export function prizeFor(place) {
  return TOURNEY.prizes.find((p) => place >= p.places[0] && place <= p.places[1]) || null;
}
