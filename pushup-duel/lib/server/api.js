// REST API: всё, что не требует живого соединения. Next.js отдаёт сюда запросы из app/api/[...path].
import { authenticate, refFrom } from './auth.js';
import { unlockPack, openPack, claimRoad, buy, claimInbox, setCard } from './economy.js';
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
    this.compete = null;
  }

  // Загрузка ролика боя (тело — сам видеофайл, не JSON). Возвращает { status, body }.
  async uploadClip({ auth, startParam, buf, type, meta }) {
    const who = authenticate(auth, this.config, startParam);
    if (!who) return { status: 401, body: { error: 'Нужен вход через Telegram' } };
    if (!this.clips) return { status: 503, body: { error: 'Ролики недоступны' } };
    const { player: p } = this.store.getOrCreate(who.id, who, refFrom(who.startParam));
    if (who.tgId) p.tgId = who.tgId;
    try {
      return { status: 200, body: await this.clips.create(p, buf, type, meta) };
    } catch (e) {
      if (e instanceof UserError) return { status: 400, body: { error: e.message } };
      console.error('clips:', e);
      return { status: 500, body: { error: 'Ошибка сервера' } };
    }
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
            myClan: p.clanId && s.clans.get(p.clanId) ? this.withWar(s.clanFull(s.clans.get(p.clanId)), p) : null,
          };
        }
        case 'belts': return { belts: this.compete ? this.compete.beltsView(p) : [] };
        case 'tourney': {
          if (!this.compete) return { runs: [] };
          return { runs: this.compete.runsView(p), gems: p.gems };
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
        // Наборы наклеек: POST packs/<слот>/unlock · packs/<слот>/open { gems }
        case 'packs': {
          const slot = Number(b);
          if (!(slot >= 0 && slot < 4)) throw new UserError('Нет такого слота');
          if (c === 'unlock') { unlockPack(p, slot); s.save(); return { profile: privateProfile(p, s) }; }
          if (c === 'open') { const loot = openPack(p, slot, { gems: !!body.gems }); s.save(); return { loot, profile: privateProfile(p, s) }; }
          return undefined;
        }
        // Наклейки на карточке: POST card { items: [{ id, x, y, r, s }] }
        case 'card': {
          setCard(p, body.items);
          s.save();
          return { profile: privateProfile(p, s) };
        }
        // Почта: забрать награду (подарок Telegram — повторная отправка).
        case 'inbox': {
          const it = (p.inbox || []).find((x) => x.id === b);
          if (it?.gift) {
            if (!this.premium) throw new UserError('Подарки отключены');
            const r = await this.premium.sendStarsGift(p, it.gift, `PushDuel · ${it.title}`);
            if (r.status === 'sent') p.inbox = p.inbox.filter((x) => x.id !== b);
            s.save();
            return { gift: r, profile: privateProfile(p, s) };
          }
          const r = claimInbox(p, String(b || ''));
          s.save();
          return { ...r, profile: privateProfile(p, s) };
        }
        // Турниры: POST tourney/<small|big>/start — оплатить вход кристаллами.
        case 'tourney': {
          if (!this.compete) throw new UserError('Турниры недоступны');
          if (c === 'start') { this.compete.startRun(p, b); s.save(); return { runs: this.compete.runsView(p), profile: privateProfile(p, s) }; }
          return undefined;
        }
        case 'road': {
          const r = claimRoad(p, b);
          s.save();
          return { ...r, profile: privateProfile(p, s) };
        }
        case 'shop': {
          const r = buy(p, String(b || ''));
          s.save();
          return { ...r, profile: privateProfile(p, s) };
        }
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
          // Война: POST clans/war/declare { clanId } · clans/war/<id>/accept · clans/war/<id>/decline
          if (b === 'war') {
            if (!this.compete) throw new UserError('Войны недоступны');
            if (c === 'declare') { await this.compete.declareWar(p, body.clanId); s.save(); return this.myClan(p); }
            if (body.action === 'accept' || body.action === 'decline') { this.compete.respondWar(p, c, body.action === 'accept'); s.save(); return this.myClan(p); }
            return undefined;
          }
          if (b === 'crest') { s.setCrest(p, body.crest); s.save(); return this.myClan(p); }
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
    return { clan: clan ? this.withWar(this.store.clanFull(clan), p) : null, profile: privateProfile(p, this.store) };
  }

  // К клану — его война и кто из участников в сети (для спаррингов).
  withWar(full, p) {
    const online = new Set(this.hub.conns.keys());
    return {
      ...full,
      roster: full.roster.map((m) => ({ ...m, online: online.has(m.id) })),
      war: this.compete ? this.compete.warView(full.id, p) : null,
      captain: this.store.clans.get(full.id)?.ownerId === p.id,
    };
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
