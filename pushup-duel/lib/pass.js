// Боевой пропуск, косметика, опыт и ежедневные задания.
// Общий модуль: его импортируют и браузер, и сервер (там он считает опыт за матчи).

export const SEASON = {
  id: 1,
  name: 'Сезон 1 · Искра',
  ends: '2027-01-31',
};

export const MAX_LEVEL = 30;

// Сколько опыта нужно, чтобы перейти с уровня n на n+1.
export const xpForLevel = (n) => 100 + 15 * (n - 1);

export function levelInfo(xp) {
  let level = 1;
  let rest = Math.max(0, xp | 0);
  while (level < MAX_LEVEL && rest >= xpForLevel(level)) {
    rest -= xpForLevel(level);
    level += 1;
  }
  const need = level < MAX_LEVEL ? xpForLevel(level) : 0;
  return { level, xpInLevel: level < MAX_LEVEL ? rest : 0, xpNeed: need, max: level >= MAX_LEVEL };
}

// ---- Косметика ----
// frame — рамка аватара, trail — стиль скелета, burst — эффект на каждый повтор,
// victory — эффект на экране победы, title — подпись под именем, avatar — аватар.

export const CATEGORIES = {
  avatar:  'Аватары',
  frame:   'Рамки',
  skin:    'Скины',
  trail:   'Скелет',
  burst:   'Эффект повтора',
  victory: 'Эффект победы',
  title:   'Титулы',
  music:   'Музыка боя',
  gift:    'Подарки Telegram',
};

export const COSMETICS = {
  frame: {
    none:    { name: 'Без рамки' },
    neon:    { name: 'Неон' },
    ice:     { name: 'Лёд' },
    fire:    { name: 'Пламя' },
    gold:    { name: 'Золото' },
    rainbow: { name: 'Радуга' },
    galaxy:  { name: 'Галактика' },
    legion:  { name: 'Легион' },
    crown:   { name: 'Звёздная корона' },
  },
  // Скин — костюм, который рисуется поверх игрока по точкам тела во время боя.
  skin: {
    none:     { name: 'Без скина' },
    bones:    { name: 'Кости', about: 'Череп, рёбра и кости поверх тела' },
    robot:    { name: 'Робот', about: 'Стальные пластины и красный визор' },
    ninja:    { name: 'Ниндзя', about: 'Маска и развевающаяся повязка' },
    knight:   { name: 'Рыцарь', about: 'Шлем, наплечники и латные перчатки' },
    hero:     { name: 'Супергерой', about: 'Маска, эмблема и плащ' },
    cyber:    { name: 'Кибер', about: 'Неоновый каркас и светящееся ядро' },
    flame:    { name: 'Дух огня', about: 'Тело из пламени' },
  },
  trail: {
    classic:   { name: 'Классика' },
    neon:      { name: 'Неоновое свечение' },
    fire:      { name: 'Огненные руки' },
    ice:       { name: 'Ледяной' },
    lightning: { name: 'Молнии' },
    rainbow:   { name: 'Радужный' },
    shadow:    { name: 'Тень' },
    aurora:    { name: 'Северное сияние' },
  },
  burst: {
    pop:       { name: 'Простой +1' },
    sparks:    { name: 'Искры' },
    ring:      { name: 'Ударная волна' },
    confetti:  { name: 'Конфетти' },
    emoji:     { name: 'Бицепсы 💪' },
    fireworks: { name: 'Салют' },
    meteor:    { name: 'Метеор' },
    stars:     { name: 'Звездопад' },
  },
  victory: {
    none:      { name: 'Без эффекта' },
    confetti:  { name: 'Конфетти' },
    fireworks: { name: 'Фейерверк' },
    goldrain:  { name: 'Золотой дождь' },
    aurora:    { name: 'Сияние' },
  },
  title: {
    rookie:  { name: 'Новобранец' },
    pusher:  { name: 'Отжимной' },
    elbows:  { name: 'Железные локти' },
    storm:   { name: 'Гроза пола' },
    machine: { name: 'Машина для отжиманий' },
    god:     { name: 'Бог трицепса' },
    streak:  { name: 'Несгибаемый' },
    recruiter: { name: 'Вербовщик' },
    captain: { name: 'Капитан клана' },
    community: { name: 'Плечом к плечу' },
    patron:  { name: 'Звёздный' },
  },
  music: {
    none:      { name: 'Без музыки' },
    pulse:     { name: 'Пульс', about: 'Ровный электронный бит' },
    synthwave: { name: 'Синтвейв', about: 'Неон, ретро и арпеджио' },
    chiptune:  { name: '8-бит', about: 'Как на старой приставке' },
    dnb:       { name: 'Драм-н-бейс', about: 'Быстрые брейки, 174 BPM' },
    epic:      { name: 'Эпик', about: 'Барабаны войны и медь' },
  },
  // Подарки Telegram: ключ — цена подарка в звёздах.
  gift: {
    15:  { name: 'Подарок за 15 ★' },
    25:  { name: 'Подарок за 25 ★' },
    50:  { name: 'Подарок за 50 ★' },
    100: { name: 'Подарок за 100 ★' },
  },
  avatar: Object.fromEntries(
    ['🦊', '🐻', '🐯', '🦁', '🐺', '🐼', '🐸', '🦖', '🐙', '🦍', '🤖', '🥷',
     '🦄', '🐉', '💀', '👑', '⚡', '🔥', '🤝', '🦸', '💎'].map((a) => [a, { name: a }]),
  ),
};

// Что открыто у всех с самого начала.
export const DEFAULT_UNLOCKED = [
  'frame:none', 'skin:none', 'trail:classic', 'burst:pop', 'victory:none', 'title:rookie', 'music:none', 'music:pulse',
  ...['🦊', '🐻', '🐯', '🦁', '🐺', '🐼', '🐸', '🦖', '🐙', '🦍', '🤖', '🥷'].map((a) => `avatar:${a}`),
];

export const DEFAULT_EQUIPPED = { skin: 'none', frame: 'none', trail: 'classic', burst: 'pop', victory: 'none', title: 'rookie', music: 'pulse' };

// Награды по уровням пропуска.
export const PASS_REWARDS = {
  2:  ['trail:neon'],
  3:  ['frame:neon'],
  4:  ['burst:sparks'],
  5:  ['title:pusher'],
  6:  ['trail:fire'],
  7:  ['avatar:🦄'],
  8:  ['frame:ice'],
  9:  ['burst:ring', 'skin:bones'],
  10: ['victory:confetti'],
  11: ['trail:ice'],
  12: ['avatar:🐉'],
  13: ['frame:fire'],
  14: ['burst:confetti'],
  15: ['title:elbows', 'skin:robot'],
  16: ['trail:lightning'],
  17: ['avatar:💀'],
  18: ['frame:gold'],
  19: ['burst:emoji'],
  20: ['title:storm'],
  21: ['trail:rainbow', 'skin:ninja'],
  22: ['avatar:👑'],
  23: ['victory:fireworks'],
  24: ['frame:rainbow'],
  25: ['burst:fireworks'],
  26: ['title:machine'],
  27: ['trail:shadow', 'skin:knight'],
  28: ['avatar:⚡'],
  29: ['burst:meteor'],
  30: ['frame:galaxy', 'title:god', 'victory:goldrain', 'avatar:🔥'],
};

// Особые награды не из пропуска.
export const ACHIEVEMENT_REWARDS = {
  winStreak5: { need: 'Выиграй 5 рейтинговых дуэлей подряд', items: ['title:streak'] },
  invite1:    { need: 'Пригласи друга', items: ['avatar:🤝', 'music:synthwave'] },
  invite3:    { need: 'Пригласи 3 друзей', items: ['title:recruiter', 'music:chiptune'] },
  invite5:    { need: 'Пригласи 5 друзей', items: ['music:dnb'] },
  invite10:   { need: 'Пригласи 10 друзей', items: ['frame:legion', 'music:epic'] },
  captain:    { need: 'Создай клан', items: ['title:captain'] },
  community:  { need: 'Помоги сообществу выполнить цель недели', items: ['title:community'] },
};

export const REFERRAL_XP = { inviter: 200, invitee: 100 };
export const REFERRAL_TIERS = [['invite1', 1], ['invite3', 3], ['invite5', 5], ['invite10', 10]];

// ---- Премиум-пропуск за Telegram Stars ----
// Цена и подарки настраиваются переменными окружения (PASS_PRICE_STARS), здесь — значения по умолчанию.
// Подарки — гарантированные награды уровней, а не случайный розыгрыш: платный вход
// со случайным призом юридически похож на лотерею.
export const PREMIUM_PRICE_STARS = 299;
export const PREMIUM_XP_MULT = 1.2;
export const PREMIUM_REWARDS = {
  1:  ['title:patron'],
  3:  ['trail:aurora'],
  6:  ['skin:hero'],
  5:  ['burst:stars'],
  8:  ['gift:15'],
  10: ['avatar:🦸'],
  12: ['frame:crown'],
  14: ['skin:cyber'],
  16: ['gift:25'],
  20: ['victory:aurora'],
  22: ['skin:flame'],
  24: ['gift:50'],
  27: ['avatar:💎'],
  30: ['gift:100'],
};
export const isGift = (key) => key.startsWith('gift:');
export const giftStars = (key) => Number(key.split(':')[1]);
export const COMMUNITY_REWARD_XP = 300;
export const COMMUNITY_MIN_REPS = 50; // столько нужно внести за неделю, чтобы получить награду

export function unlockedFor(level, extra = [], premium = false) {
  const set = new Set(DEFAULT_UNLOCKED);
  for (let l = 2; l <= level; l++) for (const it of PASS_REWARDS[l] || []) set.add(it);
  if (premium) {
    for (let l = 1; l <= level; l++) for (const it of PREMIUM_REWARDS[l] || []) if (!isGift(it)) set.add(it);
  }
  for (const it of extra) set.add(it);
  return [...set];
}

// ---- Жетоны на карточке игрока ----
// rarity: common · rare · epic · legend. Игрок выбирает до трёх в витрину, иначе — самые редкие.
export const RARITY = {
  common: { name: 'Обычный', color: '#9fb6cf' },
  rare:   { name: 'Редкий', color: '#43a8ff' },
  epic:   { name: 'Эпический', color: '#b06bff' },
  legend: { name: 'Легендарный', color: '#ffb31a' },
};
export const RARITY_ORDER = ['legend', 'epic', 'rare', 'common'];

export const BADGES = [
  { id: 'first_win', icon: '🩸', name: 'Первая кровь',   need: 'Выиграй рейтинговую дуэль',   rarity: 'common', test: (s) => s.wins >= 1 },
  { id: 'wins10',    icon: '⚔️', name: 'Боец',           need: '10 побед в рейтинге',         rarity: 'rare',   test: (s) => s.wins >= 10 },
  { id: 'wins50',    icon: '🛡️', name: 'Ветеран',        need: '50 побед в рейтинге',         rarity: 'epic',   test: (s) => s.wins >= 50 },
  { id: 'wins200',   icon: '🏛️', name: 'Гладиатор',      need: '200 побед в рейтинге',        rarity: 'legend', test: (s) => s.wins >= 200 },
  { id: 'streak5',   icon: '🔥', name: 'Несгибаемый',    need: '5 побед подряд',              rarity: 'epic',   test: (s) => s.bestWinStreak >= 5 },
  { id: 'streak10',  icon: '☄️', name: 'Неудержимый',    need: '10 побед подряд',             rarity: 'legend', test: (s) => s.bestWinStreak >= 10 },
  { id: 'reps1k',    icon: '💪', name: 'Тысячник',       need: '1 000 отжиманий всего',       rarity: 'rare',   test: (s) => s.totalReps >= 1000 },
  { id: 'reps10k',   icon: '🦾', name: 'Стальные руки',  need: '10 000 отжиманий всего',      rarity: 'legend', test: (s) => s.totalReps >= 10000 },
  { id: 'record40',  icon: '🚀', name: 'Ракета',         need: '40 повторов за одну дуэль',   rarity: 'rare',   test: (s) => s.bestReps >= 40 },
  { id: 'record60',  icon: '⚡', name: 'Молния',         need: '60 повторов за одну дуэль',   rarity: 'epic',   test: (s) => s.bestReps >= 60 },
  { id: 'combo20',   icon: '🌀', name: 'Комбо-мастер',   need: 'Комбо ×20',                   rarity: 'epic',   test: (s) => s.bestCombo >= 20 },
  { id: 'days7',     icon: '📅', name: 'Неделя огня',    need: '7 дней подряд',               rarity: 'rare',   test: (s) => s.bestDayStreak >= 7 },
  { id: 'days30',    icon: '🗓️', name: 'Железная воля',  need: '30 дней подряд',              rarity: 'legend', test: (s) => s.bestDayStreak >= 30 },
  { id: 'recruiter', icon: '📣', name: 'Вербовщик',      need: 'Пригласи 3 друзей',           rarity: 'rare',   test: (s) => s.invites >= 3 },
  { id: 'leader',    icon: '👑', name: 'Лидер',          need: 'Пригласи 10 друзей',          rarity: 'legend', test: (s) => s.invites >= 10 },
  { id: 'captain',   icon: '🏴', name: 'Капитан',        need: 'Создай клан',                 rarity: 'common', test: (s) => !!s.isCaptain },
  { id: 'pass30',    icon: '🎖️', name: 'Сезон закрыт',   need: '30 уровень пропуска',         rarity: 'epic',   test: (s) => s.level >= 30 },
  { id: 'star',      icon: '🌟', name: 'Звёздный',       need: 'Купи премиум-пропуск',        rarity: 'legend', test: (s) => !!s.premium },
];

export function earnedBadges(stats) {
  return BADGES.filter((b) => b.test(stats)).map((b) => b.id);
}

export function showcaseBadges(earned, chosen = []) {
  const set = new Set(earned);
  const picked = chosen.filter((id) => set.has(id)).slice(0, 3);
  if (picked.length) return picked;
  return BADGES.filter((b) => set.has(b.id))
    .sort((a, b) => RARITY_ORDER.indexOf(a.rarity) - RARITY_ORDER.indexOf(b.rarity))
    .slice(0, 3).map((b) => b.id);
}

export const badgeById = (id) => BADGES.find((b) => b.id === id);

export function cosmeticName(key) {
  const [cat, id] = key.split(':');
  const c = COSMETICS[cat]?.[id];
  return c ? c.name : key;
}

// ---- Опыт ----
export const XP = {
  perRep: 3,
  win: 50,
  draw: 25,
  loss: 15,
  botWin: 20,
  comboPer5: 5,        // за каждые 5 повторов в комбо: ×5 → +5, ×10 → +10 …
  firstWinOfDay: 100,
  winStreakStep: 15,   // 2 победы подряд → +15, 3 → +30 … до 5 ступеней
  winStreakMaxSteps: 5,
};

export const COMBO_GAP_MS = 1500; // повторы чаще этого — продолжают комбо

// Множитель за ежедневную серию: 1-й день ×1.0, 2-й ×1.1 … 11-й и дальше ×2.0.
export const dayStreakMultiplier = (days) => 1 + 0.1 * Math.min(10, Math.max(0, days - 1));

// Победная серия в рейтинге даёт ещё и немного Эло сверху.
export const winStreakElo = (streak) => Math.min(5, Math.max(0, streak - 1));

// Комбо из времён повторов: максимальная длина и опыт за все вехи ×5, ×10, ….
export function combosFromTimes(times) {
  let cur = 0, best = 0, xp = 0, last = -Infinity;
  for (const t of times) {
    cur = t - last <= COMBO_GAP_MS ? cur + 1 : 1;
    last = t;
    if (cur > best) best = cur;
    if (cur % 5 === 0) xp += XP.comboPer5 * (cur / 5);
  }
  return { best, xp };
}

// ---- Ежедневные задания ----
export const QUEST_POOL = [
  { id: 'reps50',   text: 'Сделай 50 отжиманий',              goal: 50,  xp: 60,  kind: 'reps' },
  { id: 'reps120',  text: 'Сделай 120 отжиманий',             goal: 120, xp: 140, kind: 'reps' },
  { id: 'win1',     text: 'Выиграй онлайн-дуэль',             goal: 1,   xp: 80,  kind: 'wins' },
  { id: 'win3',     text: 'Выиграй 3 онлайн-дуэли',           goal: 3,   xp: 200, kind: 'wins' },
  { id: 'play3',    text: 'Сыграй 3 онлайн-дуэли',            goal: 3,   xp: 90,  kind: 'plays' },
  { id: 'combo10',  text: 'Собери комбо ×10',                 goal: 10,  xp: 80,  kind: 'combo' },
  { id: 'match30',  text: 'Сделай 30 повторов за одну дуэль', goal: 30,  xp: 100, kind: 'matchReps' },
  { id: 'train',    text: 'Пройди тренировку',                goal: 1,   xp: 50,  kind: 'trainings' },
  { id: 'friend',   text: 'Сыграй с другом по коду',          goal: 1,   xp: 70,  kind: 'friendPlays' },
];

export function dayKey(date = new Date()) {
  // Сутки по Москве: задания и серия меняются в полночь МСК.
  const msk = new Date(date.getTime() + 3 * 3600 * 1000);
  return msk.toISOString().slice(0, 10);
}

// Неделя с понедельника по МСК: ключ — дата понедельника.
export function weekKey(date = new Date()) {
  const msk = new Date(date.getTime() + 3 * 3600 * 1000);
  const dow = (msk.getUTCDay() + 6) % 7;
  msk.setUTCDate(msk.getUTCDate() - dow);
  return msk.toISOString().slice(0, 10);
}

export function questsForDay(day) {
  let h = 2166136261;
  for (const ch of day) h = Math.imul(h ^ ch.charCodeAt(0), 16777619) >>> 0;
  const pool = [...QUEST_POOL];
  const out = [];
  while (out.length < 3) {
    h = Math.imul(h ^ (h >>> 13), 2654435761) >>> 0;
    const i = h % pool.length;
    const q = pool.splice(i, 1)[0];
    // Не даём два задания одного вида в один день.
    if (out.some((o) => o.kind === q.kind)) continue;
    out.push(q);
  }
  return out;
}
