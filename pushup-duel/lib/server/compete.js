// Пояса чемпионов арен и турнир недели.
import { ARENAS, arenaOf } from '../economy.js';
import { BELT_WEEKLY, TOURNEY, tourneyState, prizeFor } from '../compete.js';
import { weekKey } from '../pass.js';
import { publicCard, UserError } from './store.js';
import { addInbox, ensureEco } from './economy.js';

export class Compete {
  constructor(store, { premium = null } = {}) {
    this.store = store;
    this.premium = premium;
    if (!store.belts) store.belts = {};
    if (!store.tourneys) store.tourneys = {};
    if (!store.beltWeek) store.beltWeek = weekKey();
  }

  // ---- Пояса ----
  beltOf(playerId) {
    for (const [arena, b] of Object.entries(this.store.belts)) if (b?.holderId === playerId) return Number(arena);
    return null;
  }

  // Чемпион, выпавший ниже своей арены, освобождает пояс.
  validateBelts() {
    for (const a of ARENAS) {
      const b = this.store.belts[a.id];
      if (!b) continue;
      const h = this.store.get(b.holderId);
      if (!h || h.rating < a.min) {
        delete this.store.belts[a.id];
        if (h) this.store.addFeed('🎗️', `Пояс арены «${a.name}» снова свободен: ${h.name} опустился ниже`);
      }
    }
  }

  give(arenaId, p, from = null) {
    const prev = this.beltOf(p.id);
    if (prev && prev !== arenaId) delete this.store.belts[prev]; // один чемпион — один пояс
    this.store.belts[arenaId] = { holderId: p.id, since: Date.now(), defenses: 0 };
    p.everHeldBelt = true;
    const a = ARENAS.find((x) => x.id === arenaId);
    this.store.addFeed('🥊', from
      ? `${p.name} отбирает пояс арены «${a.name}» у ${from.name}!`
      : `${p.name} завоёвывает свободный пояс арены «${a.name}»`);
  }

  // Итог рейтингового боя. arenaW/arenaL — арены до боя. Возвращает заметки для экрана итога.
  onRanked(winner, loser, arenaW, arenaL) {
    this.validateBelts();
    const notes = {};
    const loserBelt = this.beltOf(loser.id);
    const winnerBelt = this.beltOf(winner.id);
    if (loserBelt && loserBelt === arenaW) {
      this.give(loserBelt, winner, loser);
      notes[winner.id] = { won: loserBelt };
      notes[loser.id] = { lost: loserBelt };
    } else if (winnerBelt && winnerBelt === arenaL) {
      this.store.belts[winnerBelt].defenses += 1;
      notes[winner.id] = { defended: winnerBelt };
    } else if (!winnerBelt && !this.store.belts[arenaW]) {
      this.give(arenaW, winner);
      notes[winner.id] = { won: arenaW };
    }
    return notes;
  }

  canChallengeTitle(challenger, target) {
    this.validateBelts();
    const arena = this.beltOf(target.id);
    if (!arena) throw new UserError('У этого игрока нет пояса');
    if (arenaOf(challenger.rating).id !== arena) throw new UserError('Бросить вызов за пояс можно только чемпиону своей арены');
    return arena;
  }

  // Титульный бой: победитель уносит пояс (или защищает его).
  onTitle(arenaId, winner, loser) {
    const holder = this.store.belts[arenaId]?.holderId;
    if (holder === loser.id) { this.give(arenaId, winner, loser); return { [winner.id]: { won: arenaId }, [loser.id]: { lost: arenaId } }; }
    if (holder === winner.id) { this.store.belts[arenaId].defenses += 1; this.store.addFeed('🛡️', `${winner.name} защищает пояс в титульном бою против ${loser.name}`); return { [winner.id]: { defended: arenaId } }; }
    return {};
  }

  beltsView(me) {
    this.validateBelts();
    const myArena = arenaOf(me.rating).id;
    return ARENAS.map((a) => {
      const b = this.store.belts[a.id];
      const h = b ? this.store.get(b.holderId) : null;
      return {
        arena: a,
        holder: h ? publicCard(h, this.store) : null,
        since: b?.since || null,
        defenses: b?.defenses || 0,
        mine: h?.id === me.id,
        canChallenge: !!h && h.id !== me.id && myArena === a.id,
        weekly: BELT_WEEKLY(a.id),
      };
    });
  }

  // ---- Турнир ----
  entry(p, week = tourneyState().week) {
    return this.store.tourneys[week]?.entries?.[p.id] || null;
  }

  register(p, now = Date.now()) {
    const st = tourneyState(now);
    if (now >= st.end) throw new UserError('Турнир этой недели закончился');
    const t = (this.store.tourneys[st.week] ||= { entries: {}, settled: false });
    if (t.entries[p.id]) throw new UserError('Вы уже участвуете');
    ensureEco(p);
    if (p.tickets < 1) throw new UserError('Нужен билет 🎟 — их дают за каждые 3 победы в рейтинге');
    p.tickets -= 1;
    t.entries[p.id] = { points: 0, wins: 0, draws: 0, losses: 0, matches: 0, reps: 0, at: now };
    return t.entries[p.id];
  }

  canPlay(p, now = Date.now()) {
    const st = tourneyState(now);
    const e = this.entry(p, st.week);
    if (!st.active) throw new UserError(st.upcoming ? 'Турнир начнётся в пятницу' : 'Турнир закончился');
    if (!e) throw new UserError('Сначала зарегистрируйтесь в турнире');
    if (e.matches >= TOURNEY.matches) throw new UserError('Все турнирные матчи сыграны');
    return true;
  }

  record(p, outcome, reps) {
    const e = this.entry(p);
    if (!e) return null;
    e.matches += 1;
    e.reps += reps;
    if (outcome === 'win') { e.wins += 1; e.points += TOURNEY.winPoints; } else if (outcome === 'draw') { e.draws += 1; e.points += TOURNEY.drawPoints; } else e.losses += 1;
    return { ...e, place: this.standings(tourneyState().week).findIndex((r) => r.id === p.id) + 1 };
  }

  standings(week) {
    const t = this.store.tourneys[week];
    if (!t) return [];
    return Object.entries(t.entries)
      .map(([id, e]) => ({ id, ...e }))
      .sort((a, b) => b.points - a.points || b.wins - a.wins || b.reps - a.reps || a.at - b.at);
  }

  tourneyView(me, now = Date.now()) {
    const st = tourneyState(now);
    const rows = this.standings(st.week);
    const card = (r, i) => {
      const p = this.store.get(r.id);
      return p ? { place: i + 1, ...publicCard(p, this.store), points: r.points, wins: r.wins, matches: r.matches, me: r.id === me.id } : null;
    };
    const myIdx = rows.findIndex((r) => r.id === me.id);
    return {
      ...st,
      matchesPerEntry: TOURNEY.matches,
      prizes: TOURNEY.prizes,
      minMatches: TOURNEY.minMatchesForPrize,
      entry: myIdx >= 0 ? rows[myIdx] : null,
      place: myIdx >= 0 ? myIdx + 1 : null,
      players: rows.length,
      top: rows.slice(0, 20).map(card).filter(Boolean),
      tickets: me.tickets || 0,
    };
  }

  // ---- Подведение итогов (раз в минуту и при запросах) ----
  async settle(now = Date.now()) {
    const s = this.store;
    // Пояса: награда тем, кто держит пояс на смене недели.
    const week = weekKey(new Date(now));
    if (s.beltWeek !== week) {
      this.validateBelts();
      for (const a of ARENAS) {
        const b = s.belts[a.id];
        const h = b && s.get(b.holderId);
        if (!h) continue;
        const r = BELT_WEEKLY(a.id);
        addInbox(h, { title: `Чемпион арены «${a.name}» на неделю`, gems: r.gems, pack: r.pack });
        h.beltWeeks = (h.beltWeeks || 0) + 1;
        s.addFeed('👑', `${h.name} удержал пояс арены «${a.name}» всю неделю`);
      }
      s.beltWeek = week;
      s.save();
    }
    // Турниры, чьё время вышло.
    for (const [w, t] of Object.entries(s.tourneys)) {
      if (t.settled || now < tourneyState(Date.parse(`${w}T12:00:00Z`)).end) continue;
      t.settled = true;
      const rows = this.standings(w).filter((r) => r.matches >= TOURNEY.minMatchesForPrize);
      for (const [i, r] of rows.entries()) {
        const prize = prizeFor(i + 1);
        const p = s.get(r.id);
        if (!prize || !p) continue;
        addInbox(p, { title: `Турнир недели: ${i + 1} место`, gems: prize.gems, pack: prize.pack, sticker: prize.sticker });
        if (prize.gift) {
          const res = this.premium ? await this.premium.sendStarsGift(p, prize.gift, `PushDuel · ${i + 1} место в турнире недели 🏆`) : { status: 'failed' };
          if (res.status !== 'sent') addInbox(p, { title: `Подарок Telegram за ${i + 1} место`, gift: prize.gift });
        }
      }
      if (rows[0]) {
        const champ = s.get(rows[0].id);
        if (champ) s.addFeed('🏆', `Турнир недели выиграл ${champ.name} — ${rows[0].points} очков`);
      }
      s.save();
    }
  }
}
