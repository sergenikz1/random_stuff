// Хранилище в JSON-файле: игроки, кланы, лента сообщества. Плюс начисление прогресса.
// Вся логика — обычные функции без Next.js, поэтому её легко тестировать.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {
  levelInfo, unlockedFor, XP, combosFromTimes, dayStreakMultiplier, questsForDay, dayKey, weekKey,
  DEFAULT_EQUIPPED, DEFAULT_UNLOCKED, COSMETICS, ACHIEVEMENT_REWARDS, SEASON, REFERRAL_XP,
  COMMUNITY_REWARD_XP, COMMUNITY_MIN_REPS, REFERRAL_TIERS, PREMIUM_REWARDS, PREMIUM_XP_MULT,
  PREMIUM_PRICE_STARS, isGift, giftStars, earnedBadges, showcaseBadges,
} from '../pass.js';
import { rankOf, START_RATING, AVATARS } from '../duel.js';
import { QUEST_GEMS } from '../economy.js';
import { cleanLook, lookFor, cleanCrest, crestOf, CREST_CHARGES } from '../heraldry.js';
import { isPhotoUrl } from './photos.js';
import { ecoView, ensureEco, boostActive } from './economy.js';

const CLAN_MAX = 30;
const FEED_MAX = 80;

export class Store {
  constructor(file, opts = {}) {
    this.file = file;
    this.communityGoal = opts.communityGoal || 20000;
    this.premiumPrice = opts.premiumPrice || PREMIUM_PRICE_STARS;
    this.players = new Map();
    this.clans = new Map();
    this.feed = [];
    this.community = { week: weekKey(), reached: false };
    this.saveTimer = null;
    if (file && fs.existsSync(file)) {
      const data = JSON.parse(fs.readFileSync(file, 'utf8'));
      for (const p of data.players || []) this.players.set(p.id, migrate(p));
      for (const c of data.clans || []) this.clans.set(c.id, c);
      this.feed = data.feed || [];
      if (data.community) this.community = data.community;
      this.belts = data.belts || {};
      this.wars = data.wars || [];
      this.beltWeek = data.beltWeek || '';
    }
  }

  get(id) { return this.players.get(id); }

  // Создаёт игрока при первом входе. ref — id пригласившего (из ссылки ref_<id>).
  getOrCreate(id, defaults = {}, ref = '') {
    let p = this.players.get(id);
    let referral = null;
    if (!p) {
      p = migrate({
        id,
        name: cleanName(defaults.name) || 'Игрок',
        username: defaults.username || '',
        tgId: defaults.tgId || null,
        avatar: AVATARS[Math.abs(hashCode(id)) % 12],
      });
      this.players.set(id, p);
      const inviter = ref && ref !== id ? this.players.get(ref) : null;
      if (inviter) referral = this._applyReferral(inviter, p);
      this.addFeed('👋', `${p.name} присоединяется к PushDuel${inviter ? ` по приглашению ${inviter.name}` : ''}`);
      this.save();
    } else if (defaults.username && p.username !== defaults.username) {
      p.username = defaults.username;
    }
    // Ссылку на фото Telegram обновляем при каждом входе: пользователь мог сменить фото.
    if (isPhotoUrl(defaults.photo)) p.tgPhoto = defaults.photo;
    p.lastSeen = Date.now();
    return { player: p, referral };
  }

  _applyReferral(inviter, p) {
    p.invitedBy = inviter.id;
    p.xp += REFERRAL_XP.invitee;
    inviter.xp += REFERRAL_XP.inviter;
    inviter.invites = (inviter.invites || 0) + 1;
    addFriend(inviter, p);
    for (const [key, n] of REFERRAL_TIERS) {
      if (inviter.invites >= n) grantExtra(inviter, ACHIEVEMENT_REWARDS[key].items);
    }
    return { inviter: inviter.name, xp: REFERRAL_XP.invitee };
  }

  save() {
    if (!this.file || this.saveTimer) return;
    this.saveTimer = setTimeout(() => { this.saveTimer = null; this.flush(); }, 1500);
  }

  flush() {
    if (!this.file) return;
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify({
      version: 2,
      players: [...this.players.values()],
      clans: [...this.clans.values()],
      feed: this.feed,
      community: this.community,
      belts: this.belts || {},
      wars: this.wars || [],
      beltWeek: this.beltWeek || '',
    }));
    fs.renameSync(tmp, this.file);
  }

  addFeed(icon, text) {
    this.feed.unshift({ at: Date.now(), icon, text });
    if (this.feed.length > FEED_MAX) this.feed.length = FEED_MAX;
    this.save();
  }

  // ---- Неделя и цель сообщества ----
  weekReps(p) { return p.week === weekKey() ? p.weekReps : 0; }

  communityState() {
    const week = weekKey();
    if (this.community.week !== week) this.community = { week, reached: false };
    let total = 0, people = 0;
    for (const p of this.players.values()) {
      const r = this.weekReps(p);
      if (r > 0) { total += r; people += 1; }
    }
    return {
      week, goal: this.communityGoal, total, people, reached: this.community.reached,
      reward: COMMUNITY_REWARD_XP, minReps: COMMUNITY_MIN_REPS,
    };
  }

  // Выдаёт награду за цель недели, если игрок внёс достаточно. Возвращает опыт или 0.
  claimCommunity(p) {
    const st = this.communityState();
    if (!st.reached && st.total >= st.goal) {
      this.community.reached = true;
      st.reached = true;
      this.addFeed('🌍', `Сообщество выполнило цель недели: ${st.goal.toLocaleString('ru-RU')} отжиманий!`);
    }
    if (st.reached && p.communityWeek !== st.week && this.weekReps(p) >= COMMUNITY_MIN_REPS) {
      p.communityWeek = st.week;
      p.xp += COMMUNITY_REWARD_XP;
      grantExtra(p, ACHIEVEMENT_REWARDS.community.items);
      return COMMUNITY_REWARD_XP;
    }
    return 0;
  }

  // ---- Рейтинги ----
  leaderboard(me, type = 'rating', limit = 50) {
    let list = [...this.players.values()];
    let score;
    if (type === 'week') {
      list = list.filter((p) => this.weekReps(p) > 0);
      score = (p) => this.weekReps(p);
    } else if (type === 'friends') {
      const ids = new Set([me.id, ...me.friends]);
      list = list.filter((p) => ids.has(p.id));
      score = (p) => p.rating;
    } else {
      list = list.filter((p) => p.games > 0 || p.id === me.id);
      score = (p) => p.rating;
    }
    list.sort((a, b) => score(b) - score(a) || b.xp - a.xp);
    const rows = list.map((p, i) => ({ place: i + 1, ...publicCard(p, this), score: score(p), me: p.id === me.id }));
    return { type, top: rows.slice(0, limit), me: rows.find((r) => r.me) || null, total: rows.length };
  }

  // ---- Кланы ----
  clanScore(c) {
    let week = 0;
    for (const id of c.members) { const p = this.players.get(id); if (p) week += this.weekReps(p); }
    return week;
  }

  clanCard(c) {
    const owner = this.players.get(c.ownerId);
    return {
      id: c.id, name: c.name, emoji: c.emoji, crest: crestOf(c), about: c.about || '',
      members: c.members.length, max: CLAN_MAX, week: this.clanScore(c),
      owner: owner ? owner.name : '',
    };
  }

  clanFull(c) {
    const members = c.members.map((id) => this.players.get(id)).filter(Boolean)
      .map((p) => ({ ...publicCard(p, this), week: this.weekReps(p), owner: p.id === c.ownerId }))
      .sort((a, b) => b.week - a.week || b.rating - a.rating);
    const ranking = this.clanRanking();
    return { ...this.clanCard(c), place: ranking.findIndex((x) => x.id === c.id) + 1, of: ranking.length, roster: members };
  }

  clanRanking() {
    return [...this.clans.values()].map((c) => this.clanCard(c)).sort((a, b) => b.week - a.week || b.members - a.members);
  }

  createClan(p, { name, emoji, about, crest }) {
    if (p.clanId && this.clans.has(p.clanId)) throw new UserError('Сначала выйдите из текущего клана');
    const n = cleanName(name, 24);
    if (n.length < 3) throw new UserError('Название — от 3 символов');
    if ([...this.clans.values()].some((c) => c.name.toLowerCase() === n.toLowerCase())) throw new UserError('Такое название уже занято');
    let id;
    do id = crypto.randomBytes(4).toString('base64url').replace(/[-_]/g, '').slice(0, 5).toUpperCase();
    while (id.length < 5 || this.clans.has(id));
    const c = {
      id, name: n, emoji: CLAN_EMOJI.includes(emoji) ? emoji : '🛡️', about: cleanName(about, 120),
      ownerId: p.id, members: [p.id], createdAt: Date.now(),
    };
    if (crest) { c.crest = cleanCrest(crest); c.emoji = c.crest.charge; }
    this.clans.set(id, c);
    p.clanId = id;
    grantExtra(p, ACHIEVEMENT_REWARDS.captain.items);
    this.addFeed(c.emoji, `${p.name} основывает клан «${c.name}»`);
    return c;
  }

  // Капитан меняет герб клана.
  setCrest(p, crest) {
    const c = this.clans.get(p.clanId);
    if (!c) throw new UserError('Вы не в клане');
    if (c.ownerId !== p.id) throw new UserError('Герб меняет только капитан');
    c.crest = cleanCrest(crest, crestOf(c));
    c.emoji = c.crest.charge;
    return c;
  }

  joinClan(p, id) {
    const c = this.clans.get(String(id || '').toUpperCase());
    if (!c) throw new UserError('Клан не найден');
    if (c.members.includes(p.id)) return c;
    if (p.clanId && this.clans.has(p.clanId)) this.leaveClan(p);
    if (c.members.length >= CLAN_MAX) throw new UserError('В клане нет мест');
    c.members.push(p.id);
    p.clanId = c.id;
    this.addFeed(c.emoji, `${p.name} вступает в клан «${c.name}»`);
    return c;
  }

  leaveClan(p) {
    const c = this.clans.get(p.clanId);
    p.clanId = null;
    if (!c) return;
    c.members = c.members.filter((id) => id !== p.id);
    if (!c.members.length) { this.clans.delete(c.id); return; }
    if (c.ownerId === p.id) {
      // Капитанство переходит самому активному за неделю.
      c.ownerId = [...c.members].sort((a, b) =>
        this.weekReps(this.players.get(b)) - this.weekReps(this.players.get(a)))[0];
    }
  }
}

export class UserError extends Error {}

export const CLAN_EMOJI = CREST_CHARGES;

function migrate(p) {
  return {
    name: 'Игрок', avatar: '🦊', username: '', tgId: null,
    rating: START_RATING, games: 0, wins: 0, losses: 0, draws: 0,
    bestReps: 0, totalReps: 0, bestCombo: 0,
    xp: 0, extraUnlocks: [], equipped: { ...DEFAULT_EQUIPPED },
    winStreak: 0, bestWinStreak: 0, dayStreak: 0, lastDay: '', firstWinDay: '',
    quests: { day: '', prog: {}, done: [] }, history: [],
    week: '', weekReps: 0, communityWeek: '',
    clanId: null, friends: [], invites: 0, invitedBy: null,
    premium: null, gifts: {}, badges: [], bestDayStreak: 0,
    createdAt: Date.now(),
    ...p,
    look: cleanLook(p.look, lookFor(p.id)),
  };
}

function hashCode(s) { let h = 0; for (const ch of s) h = (h * 31 + ch.charCodeAt(0)) | 0; return h; }

export function cleanName(s, max = 16) {
  return String(s || '').replace(/[\u0000-\u001f\u007f<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, max);
}

function grantExtra(p, items) {
  for (const it of items) if (!p.extraUnlocks.includes(it)) p.extraUnlocks.push(it);
}

export function addFriend(a, b) {
  if (a.id === b.id) return;
  if (!a.friends.includes(b.id)) a.friends.push(b.id);
  if (!b.friends.includes(a.id)) b.friends.push(a.id);
}

export const isPremium = (p) => p.premium?.season === SEASON.id;

export function unlockedOf(p) {
  return unlockedFor(levelInfo(p.xp).level, p.extraUnlocks, isPremium(p));
}

// Сводка для проверки жетонов.
function badgeStats(p, store) {
  const clan = store && p.clanId ? store.clans.get(p.clanId) : null;
  return {
    ...p, level: levelInfo(p.xp).level, premium: isPremium(p),
    bestDayStreak: Math.max(p.bestDayStreak || 0, p.dayStreak || 0),
    isCaptain: !!clan && clan.ownerId === p.id,
  };
}

// Подарки премиум-трека: уровень → статус (locked · ready · sending · sent · failed).
export function giftStatus(p) {
  const level = levelInfo(p.xp).level;
  const prem = isPremium(p);
  const out = [];
  for (const [lvl, items] of Object.entries(PREMIUM_REWARDS)) {
    for (const key of items) {
      if (!isGift(key)) continue;
      const rec = p.gifts[`${SEASON.id}:${lvl}`];
      let status = 'locked';
      if (rec) status = rec.status;
      else if (prem && level >= Number(lvl)) status = 'ready';
      out.push({ level: Number(lvl), key, stars: giftStars(key), status, error: rec?.error || '' });
    }
  }
  return out;
}

// Облик аватара для клиента: если выбрано фото Telegram и оно есть — добавляем ссылку на него.
function lookOut(p, store) {
  const hasPhoto = isPhotoUrl(p.tgPhoto) || (!!store?.photoBot && !!p.tgId);
  return hasPhoto && p.look.src === 'tg' ? { ...p.look, photo: `/api/photo/${p.id}` } : p.look;
}

// Что видят другие игроки.
export function publicCard(p, store) {
  const clan = store && p.clanId ? store.clans.get(p.clanId) : null;
  return {
    id: p.id, name: p.name, avatar: p.avatar, look: lookOut(p, store), rating: p.rating, rank: rankOf(p.rating).name,
    level: levelInfo(p.xp).level, frame: p.equipped.frame, trail: p.equipped.trail,
    title: COSMETICS.title[p.equipped.title]?.name || '',
    wins: p.wins, games: p.games, bestReps: p.bestReps, totalReps: p.totalReps,
    bestWinStreak: p.bestWinStreak,
    clan: clan ? { id: clan.id, name: clan.name, emoji: clan.emoji, crest: crestOf(clan) } : null,
    tg: !!p.tgId,
    premium: isPremium(p),
    badges: showcaseBadges(earnedBadges(badgeStats(p, store)), p.badges),
    card: p.card || [],
    belt: store?.belts ? Number(Object.entries(store.belts).find(([, b]) => b?.holderId === p.id)?.[0]) || null : null,
  };
}

// Полный профиль для самого игрока.
export function privateProfile(p, store) {
  rollDay(p);
  const lv = levelInfo(p.xp);
  const quests = questsForDay(p.quests.day).map((q) => ({
    id: q.id, text: q.text, goal: q.goal, xp: q.xp,
    progress: Math.min(q.goal, p.quests.prog[q.kind] || 0),
    done: p.quests.done.includes(q.id),
  }));
  const streak = activeDayStreak(p);
  return {
    ...publicCard(p, store),
    losses: p.losses, draws: p.draws, bestCombo: p.bestCombo,
    xp: p.xp, ...lv, season: SEASON,
    unlocked: unlockedOf(p), equipped: p.equipped,
    winStreak: p.winStreak, dayStreak: streak, multiplier: dayStreakMultiplier(Math.max(1, streak)),
    playedToday: p.lastDay === dayKey(),
    eco: ecoView(p),
    weekReps: store ? store.weekReps(p) : 0,
    quests, history: p.history.slice(0, 30),
    invites: p.invites, friends: p.friends.length,
    earnedBadges: earnedBadges(badgeStats(p, store)), chosenBadges: p.badges,
    premiumPrice: store ? store.premiumPrice : PREMIUM_PRICE_STARS,
    gifts: giftStatus(p),
    achievements: Object.entries(ACHIEVEMENT_REWARDS).map(([id, a]) => ({
      id, need: a.need, items: a.items, done: a.items.every((it) => unlockedOf(p).includes(it)),
    })),
  };
}

const yesterdayOf = (day) => {
  const d = new Date(`${day}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() - 1);
  return d.toISOString().slice(0, 10);
};

// Серия «живая», если последняя активность была сегодня или вчера.
function activeDayStreak(p) {
  const today = dayKey();
  return p.lastDay === today || p.lastDay === yesterdayOf(today) ? p.dayStreak : 0;
}

function rollDay(p) {
  const today = dayKey();
  if (p.quests.day !== today) p.quests = { day: today, prog: {}, done: [] };
}

// act: { type: 'ranked'|'friend'|'bot'|'training', reps, repTimes, outcome: 'win'|'loss'|'draw'|null }
export function applyActivity(p, act, store) {
  rollDay(p);
  const today = dayKey();
  if (p.lastDay !== today) {
    p.dayStreak = p.lastDay === yesterdayOf(today) ? p.dayStreak + 1 : 1;
    p.lastDay = today;
    p.bestDayStreak = Math.max(p.bestDayStreak || 0, p.dayStreak);
  }

  const items = [];
  const reps = Math.max(0, act.reps | 0);
  const combo = combosFromTimes(act.repTimes || []);
  const isDuel = ['ranked', 'friend', 'tournament', 'title', 'spar', 'war'].includes(act.type);

  if (reps) items.push({ label: `Отжимания ×${reps}`, xp: reps * XP.perRep });
  if (combo.xp) items.push({ label: `Комбо (лучшее ×${combo.best})`, xp: combo.xp });
  if (isDuel && act.outcome) {
    items.push({
      label: { win: 'Победа', draw: 'Ничья', loss: 'За участие' }[act.outcome],
      xp: { win: XP.win, draw: XP.draw, loss: XP.loss }[act.outcome],
    });
  }
  if (act.type === 'bot' && act.outcome === 'win') items.push({ label: 'Победа над ботом', xp: XP.botWin });

  if (act.type === 'ranked') {
    if (act.outcome === 'win') {
      p.winStreak += 1;
      p.bestWinStreak = Math.max(p.bestWinStreak, p.winStreak);
      const steps = Math.min(XP.winStreakMaxSteps, p.winStreak - 1);
      if (steps > 0) items.push({ label: `Серия побед ×${p.winStreak}`, xp: steps * XP.winStreakStep });
    } else {
      p.winStreak = 0;
    }
  }
  if (isDuel && act.outcome === 'win' && p.firstWinDay !== today) {
    p.firstWinDay = today;
    items.push({ label: 'Первая победа дня', xp: XP.firstWinOfDay });
  }

  // Множитель ежедневной серии — на всё, кроме заданий.
  const mult = dayStreakMultiplier(p.dayStreak);
  const raw = items.reduce((s, i) => s + i.xp, 0);
  const boosted = Math.round(raw * mult);
  if (boosted > raw) items.push({ label: `Серия дней 🔥${p.dayStreak} ×${mult.toFixed(1)}`, xp: boosted - raw });
  if (isPremium(p) && boosted > 0) {
    items.push({ label: `Премиум ★ ×${PREMIUM_XP_MULT}`, xp: Math.round(boosted * (PREMIUM_XP_MULT - 1)) });
  }
  if (boostActive(p) && boosted > 0) items.push({ label: 'Буст опыта ×2 ⚡', xp: boosted });

  // Задания.
  const prog = p.quests.prog;
  const add = (k, v) => { prog[k] = (prog[k] || 0) + v; };
  add('reps', reps);
  if (act.type === 'ranked') add('plays', 1);
  if (act.type === 'ranked' && act.outcome === 'win') add('wins', 1);
  if (act.type === 'training') add('trainings', 1);
  if (act.type === 'friend') add('friendPlays', 1);
  prog.combo = Math.max(prog.combo || 0, combo.best);
  if (act.type !== 'training') prog.matchReps = Math.max(prog.matchReps || 0, reps);
  const questsDone = [];
  for (const q of questsForDay(p.quests.day)) {
    if (!p.quests.done.includes(q.id) && (prog[q.kind] || 0) >= q.goal) {
      p.quests.done.push(q.id);
      questsDone.push({ text: q.text, xp: q.xp, gems: QUEST_GEMS });
      ensureEco(p);
      p.gems += QUEST_GEMS;
      items.push({ label: `Задание: ${q.text}`, xp: q.xp });
    }
  }

  // Неделя: личный зачёт, клан и цель сообщества.
  const week = weekKey();
  if (p.week !== week) { p.week = week; p.weekReps = 0; }
  p.weekReps += reps;
  if (store?.onActivity) store.onActivity(p, reps); // очки клановой войны

  const before = levelInfo(p.xp).level;
  const unlockedBefore = new Set(unlockedOf(p));
  p.xp += items.reduce((s, i) => s + i.xp, 0);
  if (store) {
    const community = store.claimCommunity(p);
    if (community) items.push({ label: 'Цель сообщества выполнена 🌍', xp: community });
  }
  const total = items.reduce((s, i) => s + i.xp, 0);
  const after = levelInfo(p.xp).level;

  // Статистика и достижения.
  p.totalReps += reps;
  if (act.type !== 'training') p.bestReps = Math.max(p.bestReps, reps);
  p.bestCombo = Math.max(p.bestCombo, combo.best);
  if (p.winStreak >= 5) grantExtra(p, ACHIEVEMENT_REWARDS.winStreak5.items);
  const newRewards = unlockedOf(p).filter((k) => !unlockedBefore.has(k));

  if (store) {
    if (after > before && after % 5 === 0) store.addFeed('⭐', `${p.name} достигает ${after} уровня боевого пропуска`);
    if (p.winStreak >= 3 && act.outcome === 'win' && act.type === 'ranked') {
      store.addFeed('🔥', `${p.name}: ${p.winStreak} побед подряд!`);
    }
    store.save();
  }

  return {
    items, total, levelBefore: before, levelAfter: after, newRewards, questsDone,
    bestCombo: combo.best, dayStreak: p.dayStreak, multiplier: mult,
  };
}

export function pushHistory(p, entry) {
  p.history.unshift({ at: Date.now(), ...entry });
  if (p.history.length > 50) p.history.length = 50;
}

// Смена имени, аватара, косметики.
export function updateProfile(p, body, store = null) {
  if (body.name !== undefined) {
    const n = cleanName(body.name);
    if (n.length < 2) throw new UserError('Имя — от 2 символов');
    p.name = n;
  }
  const unlocked = new Set(unlockedOf(p));
  if (body.avatar !== undefined) {
    if (!unlocked.has(`avatar:${body.avatar}`)) throw new UserError('Этот аватар ещё не открыт');
    p.avatar = body.avatar;
  }
  if (body.look !== undefined) p.look = cleanLook(body.look, p.look);
  if (body.badges !== undefined) {
    const earned = new Set(earnedBadges(badgeStats(p, store)));
    if (!Array.isArray(body.badges) || body.badges.length > 3 || body.badges.some((b) => !earned.has(b))) {
      throw new UserError('Можно выбрать до трёх полученных жетонов');
    }
    p.badges = [...new Set(body.badges)];
  }
  if (body.equip) {
    const { cat, id } = body.equip;
    if (!(cat in DEFAULT_EQUIPPED) || !unlocked.has(`${cat}:${id}`)) throw new UserError('Этот предмет ещё не открыт');
    p.equipped = { ...p.equipped, [cat]: id };
  }
}

export { DEFAULT_UNLOCKED };
