// Живая часть: подключения по WebSocket, подбор соперников, комнаты с другом и сами дуэли.
// Сервер — судья: он ведёт таймер, принимает повторы и считает рейтинг.
import { authenticate, refFrom } from './auth.js';
import {
  applyActivity, pushHistory, privateProfile, publicCard, addFriend, UserError, updateProfile,
} from './store.js';
import { eloDelta } from '../duel.js';
import { winStreakElo } from '../pass.js';
import { awardWin } from './economy.js';
import { arenaOf, ARENAS } from '../economy.js';
import { TOURNEYS, WAR } from '../compete.js';

const DURATIONS = [30, 60, 90, 120];

export class Hub {
  constructor(store, config, opts = {}) {
    this.store = store;
    this.config = config;
    this.notify = opts.notify || null;           // (tgId, text, button, startParam) => Promise
    this.compete = opts.compete || null;         // пояса и турнир (lib/server/compete.js)
    this.tourneyMs = opts.tourneyMs ?? TOURNEYS.small.durationSec * 1000;
    this.sparMs = opts.sparMs ?? 60000;
    this.warMs = opts.warMs ?? WAR.duelSec * 1000;
    this.countdownMs = opts.countdownMs ?? 3500;
    this.rankedMs = opts.rankedMs ?? 60000;
    this.readyTimeoutMs = opts.readyTimeoutMs ?? 60000;
    this.minRepGapMs = opts.minRepGapMs ?? 280;
    this.conns = new Map();      // playerId -> conn
    this.queue = [];             // { conn, since }
    this.rooms = new Map();      // code -> { code, host, durationMs, createdAt, invited }
    this.matches = new Set();
    this.challengeAt = new Map();
    this.mmTimer = setInterval(() => this.matchmake(), opts.mmIntervalMs ?? 1000);
    this.statsTimer = setInterval(() => this.broadcastStats(), 5000);
  }

  close() {
    clearInterval(this.mmTimer);
    clearInterval(this.statsTimer);
    for (const m of this.matches) clearTimeout(m.timer);
  }

  stats() {
    let playing = 0;
    for (const m of this.matches) if (m.phase !== 'over') playing += 2;
    return { online: this.conns.size, queue: this.queue.length, playing };
  }

  broadcastStats() {
    const s = { t: 'stats', ...this.stats() };
    for (const c of this.conns.values()) send(c, s);
  }

  // ---- Подключения ----
  attach(ws) {
    const conn = { ws, player: null, state: 'idle', match: null, room: null };
    const helloTimer = setTimeout(() => { if (!conn.player) ws.close(4001, 'auth timeout'); }, 10000);
    ws.on('message', (raw) => {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      try {
        if (!conn.player) {
          if (msg.t === 'hello') { clearTimeout(helloTimer); this.hello(conn, msg); }
          return;
        }
        const r = this.handle(conn, msg);
        if (r && typeof r.catch === 'function') r.catch((e) => reportError(conn, e, msg));
      } catch (e) {
        reportError(conn, e, msg);
      }
    });
    ws.on('close', () => { clearTimeout(helloTimer); this.drop(conn); });
    ws.on('error', () => {});
  }

  hello(conn, msg) {
    const who = authenticate(msg.auth, this.config, msg.startParam || '');
    if (!who) { send(conn, { t: 'auth_failed' }); conn.ws.close(4003, 'auth'); return; }
    const { player, referral } = this.store.getOrCreate(who.id, who, refFrom(who.startParam));
    if (who.tgId) player.tgId = who.tgId;
    const old = this.conns.get(player.id);
    if (old && old !== conn) {
      send(old, { t: 'kicked', msg: 'Вы вошли с другого устройства' });
      this.drop(old);
      old.ws.close(4000, 'replaced');
    }
    conn.player = player;
    this.conns.set(player.id, conn);
    send(conn, {
      t: 'welcome',
      profile: privateProfile(player, this.store),
      referral,
      stats: this.stats(),
      config: {
        rankedSec: this.rankedMs / 1000,
        botUsername: this.config.botUsername || '',
        appShortName: this.config.appShortName || '',
      },
    });
  }

  drop(conn) {
    if (!conn.player) return;
    this.leaveQueue(conn);
    this.closeRoom(conn);
    if (conn.match) this.leaveMatch(conn, 'disconnect');
    if (this.conns.get(conn.player.id) === conn) this.conns.delete(conn.player.id);
  }

  handle(conn, msg) {
    switch (msg.t) {
      case 'ping': return send(conn, { t: 'pong', at: msg.at });
      case 'queue': return this.joinQueue(conn, ['small', 'big', 'clan', 'war'].includes(msg.kind) ? msg.kind : 'ranked');
      case 'unqueue': return this.leaveQueue(conn, true);
      case 'room_create': return this.createRoom(conn, msg.durationSec);
      case 'room_join': return this.joinRoom(conn, msg.code);
      case 'room_leave': return this.closeRoom(conn, true);
      case 'challenge': return this.challenge(conn, msg.playerId, msg.durationSec, !!msg.title);
      case 'ready': return this.ready(conn);
      case 'rep': return this.rep(conn);
      case 'forfeit': return conn.match && this.leaveMatch(conn, 'forfeit');
      case 'profile': {
        updateProfile(conn.player, msg.body || {}, this.store);
        this.store.save();
        return send(conn, { t: 'profile', profile: privateProfile(conn.player, this.store) });
      }
      default: return undefined;
    }
  }

  ensureIdle(conn) {
    if (conn.match) throw new UserError('Вы уже в матче');
    this.leaveQueue(conn);
    this.closeRoom(conn);
  }

  // ---- Рейтинговая очередь ----
  // kind: ranked — рейтинг; small/big — турнирный забег; clan — спарринг с соклановцем;
  // war — бой войны против игрока вражеского клана.
  joinQueue(conn, kind = 'ranked') {
    this.ensureIdle(conn);
    const p = conn.player;
    if (kind === 'small' || kind === 'big') {
      if (!this.compete) throw new UserError('Турниры недоступны');
      this.compete.canPlayRun(p, kind);
    }
    if (kind === 'clan' && !p.clanId) throw new UserError('Спарринги — для участников клана');
    if (kind === 'war' && !(p.clanId && this.compete?.warOf(p.clanId, ['active']))) throw new UserError('Ваш клан сейчас не воюет');
    this.queue.push({ conn, since: Date.now(), kind });
    conn.state = 'queue';
    send(conn, { t: 'queued', kind, ...this.stats() });
    this.matchmake();
  }

  leaveQueue(conn, notify = false) {
    const i = this.queue.findIndex((q) => q.conn === conn);
    if (i >= 0) {
      this.queue.splice(i, 1);
      conn.state = 'idle';
      if (notify) send(conn, { t: 'unqueued' });
    }
  }

  // Кого с кем можно свести в одной очереди.
  compatible(a, b) {
    if (a.kind !== b.kind) return false;
    if (a.kind === 'clan') return a.conn.player.clanId === b.conn.player.clanId;
    if (a.kind === 'war') return !!this.compete?.warOpponents(a.conn.player, b.conn.player);
    return true;
  }

  // Окно по рейтингу расширяется, пока человек ждёт: 100 Эло сразу, +40 за каждую секунду.
  // В спаррингах и боях войны рейтинг не важен — сводим первых подходящих.
  matchmake() {
    const now = Date.now();
    const q = this.queue;
    for (let i = 0; i < q.length; i++) {
      const a = q[i];
      let best = -1, bestDiff = Infinity;
      for (let j = i + 1; j < q.length; j++) {
        const b = q[j];
        if (!this.compatible(a, b)) continue;
        const waited = (now - Math.min(a.since, b.since)) / 1000;
        const window = a.kind === 'clan' || a.kind === 'war' ? Infinity : Math.min(1500, 100 + 40 * waited);
        const diff = Math.abs(a.conn.player.rating - b.conn.player.rating);
        if (diff <= window && diff < bestDiff) { best = j; bestDiff = diff; }
      }
      if (best >= 0) {
        const b = q[best];
        q.splice(best, 1);
        q.splice(i, 1);
        i -= 1;
        if (a.kind === 'small' || a.kind === 'big') this.startMatch('tournament', a.conn, b.conn, this.tourneyMs, { tkind: a.kind });
        else if (a.kind === 'clan') this.startMatch('spar', a.conn, b.conn, this.sparMs);
        else if (a.kind === 'war') this.startMatch('war', a.conn, b.conn, this.warMs);
        else this.startMatch('ranked', a.conn, b.conn, this.rankedMs);
      }
    }
  }

  // ---- Комнаты с другом ----
  createRoom(conn, durationSec, invited = null, title = null) {
    this.ensureIdle(conn);
    const sec = DURATIONS.includes(Number(durationSec)) ? Number(durationSec) : 60;
    let code;
    do code = String(1000 + Math.floor(Math.random() * 9000));
    while (this.rooms.has(code));
    const room = { code, host: conn, durationMs: sec * 1000, createdAt: Date.now(), invited, title };
    this.rooms.set(code, room);
    conn.room = room;
    conn.state = 'room';
    send(conn, { t: 'room_created', code, durationSec: sec, invited: invited ? invited.name : null, title });
    return room;
  }

  joinRoom(conn, code) {
    const room = this.rooms.get(String(code || ''));
    if (!room || Date.now() - room.createdAt > 30 * 60 * 1000) throw new UserError('Комната не найдена или уже закрыта');
    if (room.host === conn) throw new UserError('Это ваша комната — отправьте код другу');
    this.ensureIdle(conn);
    this.rooms.delete(room.code);
    room.host.room = null;
    // Титульный бой может принять только сам чемпион.
    if (room.title && room.invited && conn.player.id !== room.invited.id) throw new UserError('Это вызов за пояс другому игроку');
    this.startMatch(room.title ? 'title' : 'friend', room.host, conn, room.durationMs, { title: room.title });
  }

  closeRoom(conn, notify = false) {
    const room = conn.room;
    if (!room) return;
    this.rooms.delete(room.code);
    conn.room = null;
    conn.state = 'idle';
    if (notify) send(conn, { t: 'room_closed' });
  }

  // Вызов конкретного игрока: создаём комнату и шлём ему сообщение от бота.
  async challenge(conn, playerId, durationSec, title = false) {
    const target = this.store.get(String(playerId || ''));
    if (!target || target.id === conn.player.id) throw new UserError('Игрок не найден');
    const beltArena = title ? this.compete?.canChallengeTitle(conn.player, target) : null;
    if (title && !beltArena) throw new UserError('Титульные бои недоступны');
    const key = `${conn.player.id}>${target.id}`;
    if (Date.now() - (this.challengeAt.get(key) || 0) < 60000) throw new UserError('Этого игрока можно вызывать раз в минуту');
    this.challengeAt.set(key, Date.now());
    const room = this.createRoom(conn, durationSec, target, beltArena);
    const online = this.conns.get(target.id);
    if (online && online.state === 'idle') {
      send(online, { t: 'challenged', code: room.code, from: publicCard(conn.player, this.store), durationSec: room.durationMs / 1000, title: beltArena });
    }
    const beltName = beltArena ? ARENAS.find((a) => a.id === beltArena).name : '';
    let delivered = !!online;
    if (this.notify && target.tgId) {
      try {
        await this.notify(target.tgId, beltArena
          ? `${conn.player.name} бросает тебе вызов за пояс арены «${beltName}» 🥊 Комната ${room.code}. Защити титул!`
          : `${conn.player.name} вызывает тебя на дуэль отжиманий (${fmtSec(room.durationMs / 1000)})! Комната ${room.code}.`,
        beltArena ? '🥊 Защитить пояс' : '⚔️ Принять вызов', `duel_${room.code}`);
        delivered = true;
      } catch (e) { console.warn('notify:', e.message); }
    }
    send(conn, { t: 'challenge_sent', to: target.name, delivered });
  }

  // ---- Матч ----
  startMatch(mode, a, b, durationMs, extra = {}) {
    const m = {
      id: Math.random().toString(36).slice(2, 10),
      mode, durationMs, phase: 'prep', ready: new Set(), title: extra.title || null, tkind: extra.tkind || null,
      sides: [a, b].map((conn) => ({ conn, player: conn.player, reps: [], left: false })),
      liveAt: 0, endAt: 0, timer: null,
    };
    this.matches.add(m);
    for (const s of m.sides) {
      s.conn.match = m;
      s.conn.state = 'match';
      const opp = m.sides.find((x) => x !== s);
      // Рейтинговый бой против чемпиона своей арены — тоже бой за пояс.
      const oppBelt = this.compete?.beltOf(opp.player.id);
      const beltAtStake = m.title || (mode === 'ranked' && oppBelt && oppBelt === arenaOf(s.player.rating).id ? oppBelt : null)
        || (mode === 'ranked' && this.compete?.beltOf(s.player.id) === arenaOf(opp.player.rating).id ? this.compete.beltOf(s.player.id) : null);
      send(s.conn, {
        t: 'matched', matchId: m.id, mode, durationMs, tkind: m.tkind,
        opponent: publicCard(opp.player, this.store),
        belt: beltAtStake || null,
      });
    }
    m.timer = setTimeout(() => {
      if (m.phase === 'prep') this.cancelMatch(m, 'Соперник так и не встал в упор лёжа — матч отменён');
    }, this.readyTimeoutMs);
    return m;
  }

  ready(conn) {
    const m = conn.match;
    if (!m || m.phase !== 'prep') return;
    m.ready.add(conn.player.id);
    const opp = m.sides.find((s) => s.conn !== conn);
    send(opp.conn, { t: 'opp_ready' });
    if (m.ready.size === 2) {
      clearTimeout(m.timer);
      m.phase = 'live';
      m.liveAt = Date.now() + this.countdownMs;
      m.endAt = m.liveAt + m.durationMs;
      for (const s of m.sides) send(s.conn, { t: 'start', countdownMs: this.countdownMs, durationMs: m.durationMs });
      m.timer = setTimeout(() => this.finish(m, 'time'), this.countdownMs + m.durationMs + 300);
    }
  }

  rep(conn) {
    const m = conn.match;
    if (!m || m.phase !== 'live') return;
    const now = Date.now();
    if (now < m.liveAt - 150 || now > m.endAt + 300) return;
    const side = m.sides.find((s) => s.conn === conn);
    const last = side.reps[side.reps.length - 1];
    if (last !== undefined && now - m.liveAt - last < this.minRepGapMs) return; // слишком часто — не человек
    side.reps.push(Math.max(0, now - m.liveAt));
    const opp = m.sides.find((s) => s !== side);
    send(opp.conn, { t: 'opp_rep', count: side.reps.length });
    send(conn, { t: 'rep_ok', count: side.reps.length });
  }

  leaveMatch(conn, reason) {
    const m = conn.match;
    if (!m) return;
    if (m.phase === 'prep') {
      this.cancelMatch(m, reason === 'forfeit' ? 'Соперник отказался от матча' : 'Соперник отключился', conn);
      return;
    }
    if (m.phase !== 'live') return;
    m.sides.find((s) => s.conn === conn).left = true;
    this.finish(m, reason);
  }

  cancelMatch(m, text, by = null) {
    clearTimeout(m.timer);
    m.phase = 'over';
    this.matches.delete(m);
    for (const s of m.sides) {
      s.conn.match = null;
      s.conn.state = 'idle';
      send(s.conn, { t: 'match_cancelled', msg: s.conn === by ? 'Матч отменён' : text });
    }
  }

  finish(m, reason) {
    if (m.phase === 'over') return;
    clearTimeout(m.timer);
    m.phase = 'over';
    this.matches.delete(m);
    const [A, B] = m.sides;
    let sa;
    if (A.left !== B.left) sa = A.left ? 0 : 1;
    else sa = A.reps.length > B.reps.length ? 1 : A.reps.length < B.reps.length ? 0 : 0.5;
    const scores = [sa, 1 - sa];
    const outcome = (s) => (s === 1 ? 'win' : s === 0 ? 'loss' : 'draw');

    const ratingsBefore = [A.player.rating, B.player.rating];
    const arenasBefore = [arenaOf(A.player.rating).id, arenaOf(B.player.rating).id];
    const deltas = [0, 0];
    const streakBonus = [0, 0];
    if (m.mode === 'ranked') {
      deltas[0] = eloDelta(ratingsBefore[0], ratingsBefore[1], scores[0], A.player.games);
      deltas[1] = eloDelta(ratingsBefore[1], ratingsBefore[0], scores[1], B.player.games);
    }

    m.sides.forEach((s, i) => {
      const p = s.player;
      const o = m.sides[1 - i];
      const res = outcome(scores[i]);
      if (m.mode === 'ranked') {
        p.games += 1;
        if (res === 'win') p.wins += 1; else if (res === 'loss') p.losses += 1; else p.draws += 1;
      }
      const progress = applyActivity(p, { type: m.mode, reps: s.reps.length, repTimes: s.reps, outcome: res }, this.store);
      if (m.mode === 'ranked') {
        // Бонус Эло за серию побед считаем после applyActivity: там обновилась серия.
        if (res === 'win') streakBonus[i] = winStreakElo(p.winStreak);
        p.rating = Math.max(100, p.rating + deltas[i] + streakBonus[i]);
        p.bestRating = Math.max(p.bestRating || 0, p.rating);
        // Набор наклеек и прогресс к билету за победу в рейтинге.
        if (res === 'win') s.reward = awardWin(p);
      }
      if (m.mode === 'tournament' && this.compete) s.run = this.compete.recordRun(p, m.tkind, res);
      if (m.mode === 'friend' || m.mode === 'spar') addFriend(p, o.player);
      pushHistory(p, {
        mode: m.mode, outcome: res, reps: s.reps.length, oppReps: o.reps.length,
        opp: o.player.name, oppAvatar: o.player.avatar, delta: deltas[i] + streakBonus[i], durationMs: m.durationMs,
      });
      s.progress = progress;
    });

    // Лента: заметные победы.
    const winner = sa === 1 ? A : sa === 0 ? B : null;
    // Пояса: рейтинговый бой или титульный.
    let belts = {};
    if (winner && this.compete) {
      const loser = winner === A ? B : A;
      const wi = winner === A ? 0 : 1;
      if (m.mode === 'ranked') belts = this.compete.onRanked(winner.player, loser.player, arenasBefore[wi], arenasBefore[1 - wi]);
      if (m.mode === 'title' && m.title) belts = this.compete.onTitle(m.title, winner.player, loser.player);
      if (m.mode === 'war') m.warNote = { [winner.player.id]: this.compete.warDuel(winner.player, loser.player) };
    }
    if (winner) {
      const loser = winner === A ? B : A;
      const how = reason === 'time' ? `${winner.reps.length}:${loser.reps.length}` : 'соперник сдался';
      if (m.mode === 'ranked') this.store.addFeed('🏆', `${winner.player.name} побеждает ${loser.player.name} — ${how}`);
      else if (m.mode === 'tournament' || m.mode === 'spar') { /* турнир и спарринги — без ленты, итоги пишет compete */ }
      else if (m.mode === 'war') this.store.addFeed('⚔️', `Бой войны: ${winner.player.name} (${this.store.clans.get(winner.player.clanId)?.name}) побеждает ${loser.player.name}`);
      else if (m.mode === 'title') { /* о поясе пишет compete */ }
      else this.store.addFeed('🤝', `Дружеская дуэль: ${winner.player.name} обыгрывает ${loser.player.name} — ${how}`);
      if (winner.reps.length >= 50) this.store.addFeed('💥', `${winner.player.name}: ${winner.reps.length} отжиманий за одну дуэль!`);
    }
    this.store.save();

    m.sides.forEach((s, i) => {
      const o = m.sides[1 - i];
      s.conn.match = null;
      s.conn.state = 'idle';
      send(s.conn, {
        t: 'match_over', mode: m.mode, reason, outcome: outcome(scores[i]), durationMs: m.durationMs,
        me: {
          reps: s.reps.length, ratingBefore: ratingsBefore[i], rating: s.player.rating,
          delta: deltas[i], streakBonus: streakBonus[i],
        },
        opp: { ...publicCard(o.player, this.store), reps: o.reps.length, ratingBefore: ratingsBefore[1 - i], delta: deltas[1 - i] + streakBonus[1 - i] },
        progress: s.progress,
        reward: s.reward || null,
        run: s.run || null,
        war: m.warNote?.[s.player.id] || null,
        belt: belts[s.player.id] || null,
        profile: privateProfile(s.player, this.store),
      });
    });
  }
}

function reportError(conn, e, msg) {
  if (e instanceof UserError) send(conn, { t: 'error', msg: e.message, req: msg.t });
  else { console.error('hub:', e); send(conn, { t: 'error', msg: 'Ошибка сервера', req: msg.t }); }
}

function send(conn, msg) {
  if (conn && conn.ws.readyState === 1) conn.ws.send(JSON.stringify(msg));
}

const fmtSec = (s) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
