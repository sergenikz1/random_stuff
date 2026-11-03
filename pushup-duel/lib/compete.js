// Пояса чемпионов арен, турниры «до трёх поражений» и клановые войны — общие описания.

// ---- Пояса ----
// У каждой арены один пояс. Победил чемпиона своей арены в рейтинге или в титульном бою — пояс твой.
// Свободный пояс забирает первый, кто выиграл рейтинговый бой на этой арене.
// Кто держит пояс в момент смены недели, получает награду.
export const BELT_WEEKLY = (arenaId) => ({ gems: 10 + arenaId * 5, pack: arenaId >= 5 ? 'legend' : 'epic' });

// ---- Турниры ----
// Вход за кристаллы. Играешь, пока не наберёшь 3 поражения или максимум побед; награда — по числу побед.
// Ничья не считается ни победой, ни поражением. Исход решает только сила — это не лотерея.
export const TOURNEYS = {
  small: {
    name: 'Малый турнир', entry: 10, maxWins: 6, losses: 3, durationSec: 60,
    // rewards[n] — награда за n побед в забеге
    rewards: [
      { coins: 20 }, { coins: 50 }, { pack: 'rare' }, { pack: 'rare', gems: 15 },
      { pack: 'epic', gems: 20 }, { pack: 'epic', gems: 40 }, { pack: 'legend', gems: 60 },
    ],
  },
  big: {
    name: 'Большой турнир', entry: 100, maxWins: 12, losses: 3, durationSec: 60,
    // gift — подарок Telegram за столько звёзд (отправляет бот со своего баланса)
    rewards: [
      { coins: 100 }, { coins: 200 }, { pack: 'rare' }, { pack: 'epic' }, { pack: 'epic', gems: 50 },
      { pack: 'legend' }, { pack: 'legend', gems: 100 }, { pack: 'legend', gems: 150 }, { pack: 'legend', gems: 200 },
      { pack: 'legend', gems: 200, gift: 15 }, { pack: 'legend', gems: 250, gift: 25 },
      { pack: 'legend', gems: 250, gift: 50 }, { pack: 'legend', gems: 300, gift: 100, sticker: 'champ' },
    ],
  },
};

// ---- Клановые войны ----
// Капитан объявляет войну другому клану, капитан того клана принимает. Война идёт 24 часа.
// Очки клана: каждое отжимание участников в любом режиме — 1 очко; победа в бою войны
// против игрока вражеского клана — ещё WAR.duelWin очков.
export const WAR = {
  hours: 24,
  duelWin: 50,
  duelSec: 60,
  proposalHours: 12,                       // столько живёт неотвеченный вызов
  reward: { win: { gems: 30, pack: 'epic' }, lose: { coins: 100 }, draw: { gems: 10 } },
  minReps: 20,                             // чтобы получить награду, нужно внести хотя бы столько очков
};
