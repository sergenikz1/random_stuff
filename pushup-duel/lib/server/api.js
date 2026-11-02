// REST API: всё, что не требует живого соединения. Next.js отдаёт сюда запросы из app/api/[...path].
import { authenticate, refFrom } from './auth.js';
import {
  privateProfile, publicCard, applyActivity, pushHistory, updateProfile, UserError, CLAN_EMOJI,
} from './store.js';

const MAX_SOLO_MS = 10 * 60 * 1000;

export class Api {
  constructor(store, hub, config) {
    this.store = store;
    this.hub = hub;
    this.config = config;
    this.soloAt = new Map();
    this.premium = null; // задаёт server.mjs
  }

  // Возвращает { status, body }.
  async handle({ method, path, query, body, auth, startParam }) {
    const who = authenticate(auth, this.config, startParam);
    if (!who) return { status: 401, body: { error: 'Нужен вход через Telegram' } };
    const { player: p, referral } = this.store.getOrCreate(who.id, who, refFrom(who.startParam));
    if (who.tgId) p.tgId = who.tgId;
    const [a, b, c] = path;
    try {
      const out = await this.route(method, a, b, c, p, query, body || {}, referral);
      if (out === undefined) return { status: 404, body: { error: 'Нет такого метода' } };
      return { status: 200, body: out };
    } catch (e) {
      if (e instanceof UserError) return { status: 400, body: { error: e.message } };
      console.error('api:', e);
      return { status: 500, body: { error: 'Ошибка сервера' } };
    }
  }

  async route(method, a, b, c, p, query, body, referral) {
    const s = this.store;
    if (method === 'GET') {
      switch (a) {
        case 'me': {
          const bonus = s.claimCommunity(p);
          if (bonus) s.save();
          return {
            profile: privateProfile(p, s), community: s.communityState(), referral,
            communityBonus: bonus, stats: this.hub.stats(),
            config: { botUsername: this.config.botUsername || '', appShortName: this.config.appShortName || '', clanEmoji: CLAN_EMOJI },
          };
        }
        case 'leaderboard': return s.leaderboard(p, ['rating', 'week', 'friends'].includes(query.type) ? query.type : 'rating');
        case 'feed': return { feed: s.feed.slice(0, 40), community: s.communityState(), stats: this.hub.stats() };
        case 'clans': {
          if (b) {
            const clan = s.clans.get(b.toUpperCase());
            if (!clan) throw new UserError('Клан не найден');
            return { clan: s.clanFull(clan), mine: p.clanId === clan.id };
          }
          const all = s.clanRanking();
          const q = String(query.q || '').toLowerCase().trim();
          return {
            clans: (q ? all.filter((x) => x.name.toLowerCase().includes(q) || x.id.toLowerCase() === q) : all).slice(0, 50),
            myClan: p.clanId && s.clans.get(p.clanId) ? s.clanFull(s.clans.get(p.clanId)) : null,
          };
        }
        case 'players': {
          const t = s.get(String(b || ''));
          if (!t) throw new UserError('Игрок не найден');
          return {
            player: publicCard(t, s),
            online: this.hub.conns.has(t.id),
            friend: p.friends.includes(t.id),
            me: t.id === p.id,
            recent: t.history.slice(0, 5).map((h) => ({ at: h.at, mode: h.mode, outcome: h.outcome, reps: h.reps, oppReps: h.oppReps, opp: h.opp })),
          };
        }
        default: return undefined;
      }
    }
    if (method === 'POST') {
      switch (a) {
        case 'profile':
          updateProfile(p, body, s);
          s.save();
          return { profile: privateProfile(p, s) };
        case 'solo': return this.solo(p, body);
        case 'premium': {
          if (!this.premium) throw new UserError('Покупки отключены');
          if (b === 'invoice') return this.premium.invoice(p);
          if (b === 'dev') { this.premium.devGrant(p); return { profile: privateProfile(p, s) }; }
          return undefined;
        }
        case 'gifts': {
          if (!this.premium) throw new UserError('Подарки отключены');
          const r = await this.premium.claimGift(p, b);
          return { ...r, profile: privateProfile(p, s) };
        }
        case 'clans': {
          if (!b) { s.createClan(p, body); s.save(); return this.myClan(p); }
          if (b === 'leave') { s.leaveClan(p); s.save(); return { ok: true }; }
          if (c === 'join') { s.joinClan(p, b); s.save(); return this.myClan(p); }
          return undefined;
        }
        default: return undefined;
      }
    }
    return undefined;
  }

  myClan(p) {
    const clan = this.store.clans.get(p.clanId);
    return { clan: clan ? this.store.clanFull(clan) : null, profile: privateProfile(p, this.store) };
  }

  // Тренировка или бой с ботом: проверяем правдоподобие и начисляем опыт.
  solo(p, body) {
    const type = body.type === 'bot' ? 'bot' : 'training';
    const durationMs = Math.min(MAX_SOLO_MS, Math.max(0, Number(body.durationMs) || 0));
    let times = Array.isArray(body.repTimes) ? body.repTimes.map(Number).filter((t) => Number.isFinite(t) && t >= 0) : [];
    times = times.filter((t, i) => i === 0 || t - times[i - 1] >= 250).slice(0, 400);
    if (times.length && times[times.length - 1] > durationMs + 1000) throw new UserError('Странное время повторов');
    const reps = times.length;
    const now = Date.now();
    if (now - (this.soloAt.get(p.id) || 0) < 8000) throw new UserError('Слишком часто — передохните пару секунд');
    this.soloAt.set(p.id, now);
    const outcome = type === 'bot' && ['win', 'loss', 'draw'].includes(body.outcome) ? body.outcome : null;
    const progress = applyActivity(p, { type, reps, repTimes: times, outcome }, this.store);
    pushHistory(p, {
      mode: type, outcome, reps, oppReps: type === 'bot' ? Number(body.oppReps) || 0 : null,
      opp: type === 'bot' ? String(body.oppName || 'Бот').slice(0, 24) : null, delta: 0, durationMs,
    });
    this.store.save();
    return { progress, profile: privateProfile(p, this.store) };
  }
}
