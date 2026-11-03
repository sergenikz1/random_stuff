// Пояса чемпионов арен, турниры «до трёх поражений» и клановые войны.
import { ARENAS, arenaOf } from '../economy.js';
import { BELT_WEEKLY, TOURNEYS, WAR } from '../compete.js';
import { weekKey } from '../pass.js';
import { publicCard, UserError } from './store.js';
import { addInbox, ensureEco } from './economy.js';

export class Compete {
  constructor(store, { premium = null, notify = null } = {}) {
    this.store = store;
    this.premium = premium;
    this.notify = notify;
    if (!store.belts) store.belts = {};
    if (!store.wars) store.wars = [];
    if (!store.beltWeek) store.beltWeek = weekKey();
    // Очки клановой войны начисляются из applyActivity.
    store.onActivity = (p, reps) => this.onActivity(p, reps);
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

  // ---- Турниры «до трёх поражений» ----
  // p.runs[kind] — текущий забег { wins, losses, at }, p.lastRuns[kind] — итог прошлого, p.bestRuns[kind] — рекорд.
  startRun(p, kind) {
    const def = TOURNEYS[kind];
    if (!def) throw new UserError('Нет такого турнира');
    ensureEco(p);
    p.runs ||= {};
    if (p.runs[kind]) throw new UserError('Забег уже идёт — доиграйте его');
    if (p.gems < def.entry) throw new UserError(`Вход — ${def.entry} 💎`);
    p.gems -= def.entry;
    p.runs[kind] = { wins: 0, losses: 0, at: Date.now() };
    return p.runs[kind];
  }

  canPlayRun(p, kind) {
    if (!TOURNEYS[kind]) throw new UserError('Нет такого турнира');
    if (!p.runs?.[kind]) throw new UserError(`Сначала оплатите вход в «${TOURNEYS[kind].name}»`);
    return true;
  }

  // Итог турнирного боя. Ничья не меняет счёт. Забег заканчивается на 3 поражениях или максимуме побед.
  recordRun(p, kind, outcome) {
    const def = TOURNEYS[kind];
    const run = p.runs?.[kind];
    if (!def || !run) return null;
    if (outcome === 'win') run.wins += 1;
    else if (outcome === 'loss') run.losses += 1;
    const done = run.losses >= def.losses || run.wins >= def.maxWins;
    let reward = null;
    if (done) {
      reward = def.rewards[Math.min(run.wins, def.rewards.length - 1)];
      const title = `${def.name}: ${run.wins} ${run.wins === 1 ? 'победа' : run.wins < 5 && run.wins > 0 ? 'победы' : 'побед'}`;
      addInbox(p, { title, coins: reward.coins, gems: reward.gems, pack: reward.pack, sticker: reward.sticker });
      if (reward.gift) addInbox(p, { title: `Подарок Telegram — ${title}`, gift: reward.gift });
      p.lastRuns ||= {};
      p.bestRuns ||= {};
      p.lastRuns[kind] = { wins: run.wins, losses: run.losses, at: Date.now() };
      p.bestRuns[kind] = Math.max(p.bestRuns[kind] || 0, run.wins);
      p.runs[kind] = null;
      if (run.wins >= def.maxWins) this.store.addFeed('🏆', `${p.name} прошёл «${def.name}» — ${run.wins} побед!`);
      else if (run.wins >= Math.ceil(def.maxWins * 0.75)) this.store.addFeed('🏅', `${p.name}: ${run.wins} побед в «${def.name}»`);
    }
    return { kind, wins: run.wins, losses: run.losses, maxWins: def.maxWins, maxLosses: def.losses, done, reward };
  }

  runsView(p) {
    ensureEco(p);
    return Object.entries(TOURNEYS).map(([kind, def]) => ({
      kind, name: def.name, entry: def.entry, maxWins: def.maxWins, maxLosses: def.losses, rewards: def.rewards,
      run: p.runs?.[kind] || null, last: p.lastRuns?.[kind] || null, best: p.bestRuns?.[kind] || 0,
    }));
  }

  // ---- Клановые войны ----
  wars() { return (this.store.wars ||= []); }

  warOf(clanId, statuses = ['pending', 'active']) {
    return this.wars().find((w) => statuses.includes(w.status) && (w.a === clanId || w.b === clanId)) || null;
  }

  expireProposals(now = Date.now()) {
    for (const w of this.wars()) {
      if (w.status === 'pending' && now - w.proposedAt > WAR.proposalHours * 3600e3) w.status = 'expired';
    }
  }

  async declareWar(p, targetId, now = Date.now()) {
    this.expireProposals(now);
    const mine = this.store.clans.get(p.clanId);
    if (!mine) throw new UserError('Вы не в клане');
    if (mine.ownerId !== p.id) throw new UserError('Объявить войну может только капитан');
    const target = this.store.clans.get(String(targetId || '').toUpperCase());
    if (!target || target.id === mine.id) throw new UserError('Клан не найден');
    if (this.warOf(mine.id)) throw new UserError('У вашего клана уже есть война или вызов');
    if (this.warOf(target.id)) throw new UserError('Этот клан уже воюет — выберите другой');
    const w = { id: Math.random().toString(36).slice(2, 9), a: mine.id, b: target.id, status: 'pending', proposedAt: now, score: { a: 0, b: 0 }, contrib: {}, duels: { a: 0, b: 0 } };
    this.wars().push(w);
    this.store.addFeed('📯', `Клан «${mine.name}» вызывает на войну клан «${target.name}»`);
    const cap = this.store.get(target.ownerId);
    if (this.notify && cap?.tgId) {
      this.notify(cap.tgId, `Клан «${mine.name}» объявляет войну вашему клану «${target.name}»! Примите вызов — война длится ${WAR.hours} часа.`, '⚔️ К войне', `clan_${target.id}`).catch(() => {});
    }
    return w;
  }

  respondWar(p, warId, accept, now = Date.now()) {
    this.expireProposals(now);
    const w = this.wars().find((x) => x.id === warId);
    if (!w || w.status !== 'pending') throw new UserError('Вызов не найден или устарел');
    const target = this.store.clans.get(w.b);
    if (!target || target.ownerId !== p.id) throw new UserError('Ответить на вызов может только капитан');
    const attacker = this.store.clans.get(w.a);
    if (!accept) {
      w.status = 'declined';
      this.store.addFeed('🏳️', `Клан «${target.name}» уклонился от войны с «${attacker?.name}»`);
      return w;
    }
    w.status = 'active';
    w.start = now;
    w.end = now + WAR.hours * 3600e3;
    this.store.addFeed('⚔️', `Началась война кланов: «${attacker?.name}» против «${target.name}»!`);
    return w;
  }

  sideOf(w, clanId) { return w.a === clanId ? 'a' : w.b === clanId ? 'b' : null; }

  // Любая активность игрока приносит очки его клану, если тот воюет.
  onActivity(p, reps, now = Date.now()) {
    if (!p.clanId || !reps) return;
    const w = this.warOf(p.clanId, ['active']);
    if (!w || now > w.end) return;
    const side = this.sideOf(w, p.clanId);
    w.score[side] += reps;
    w.contrib[p.id] = (w.contrib[p.id] || 0) + reps;
  }

  // Бой войны между игроками враждующих кланов: победа даёт клану бонус.
  warDuel(winner, loser) {
    const w = winner.clanId && this.warOf(winner.clanId, ['active']);
    if (!w || this.sideOf(w, loser.clanId) === null || winner.clanId === loser.clanId) return null;
    const side = this.sideOf(w, winner.clanId);
    w.score[side] += WAR.duelWin;
    w.duels[side] += 1;
    w.contrib[winner.id] = (w.contrib[winner.id] || 0) + WAR.duelWin;
    return { points: WAR.duelWin };
  }

  // Подходят ли двое для боя войны (игроки двух кланов одной активной войны).
  warOpponents(p1, p2) {
    if (!p1.clanId || !p2.clanId || p1.clanId === p2.clanId) return false;
    const w = this.warOf(p1.clanId, ['active']);
    return !!w && this.sideOf(w, p2.clanId) !== null;
  }

  warView(clanId, me, now = Date.now()) {
    this.expireProposals(now);
    const w = this.warOf(clanId) || this.wars().filter((x) => (x.a === clanId || x.b === clanId) && x.status === 'done').slice(-1)[0] || null;
    if (!w) return null;
    const side = this.sideOf(w, clanId);
    const enemyId = side === 'a' ? w.b : w.a;
    const enemy = this.store.clans.get(enemyId);
    const mine = this.store.clans.get(clanId);
    return {
      id: w.id, status: w.status, attacker: side === 'a',
      enemy: enemy ? this.store.clanCard(enemy) : { name: 'Распущенный клан', emoji: '🛡️' },
      score: { mine: w.score[side], enemy: w.score[side === 'a' ? 'b' : 'a'] },
      duels: { mine: w.duels[side], enemy: w.duels[side === 'a' ? 'b' : 'a'] },
      end: w.end || null, proposedAt: w.proposedAt, myContrib: w.contrib[me.id] || 0,
      canRespond: w.status === 'pending' && side === 'b' && mine?.ownerId === me.id,
      result: w.result || null, duelWin: WAR.duelWin, hours: WAR.hours,
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
    // Клановые войны, чьё время вышло.
    this.expireProposals(now);
    for (const w of this.wars()) {
      if (w.status !== 'active' || now < w.end) continue;
      w.status = 'done';
      const A = s.clans.get(w.a), B = s.clans.get(w.b);
      const res = w.score.a > w.score.b ? 'a' : w.score.b > w.score.a ? 'b' : 'draw';
      w.result = res;
      for (const [side, clan] of [['a', A], ['b', B]]) {
        if (!clan) continue;
        const outcome = res === 'draw' ? 'draw' : res === side ? 'win' : 'lose';
        const r = WAR.reward[outcome];
        for (const id of clan.members) {
          const pl = s.get(id);
          if (!pl || (w.contrib[id] || 0) < WAR.minReps) continue;
          addInbox(pl, { title: `Война кланов: ${{ win: 'победа', lose: 'поражение', draw: 'ничья' }[outcome]} против «${(side === 'a' ? B : A)?.name}»`, ...r });
        }
      }
      if (res === 'draw') s.addFeed('🤝', `Война «${A?.name}» и «${B?.name}» закончилась вничью`);
      else s.addFeed('🏆', `Клан «${(res === 'a' ? A : B)?.name}» побеждает в войне с «${(res === 'a' ? B : A)?.name}» — ${Math.max(w.score.a, w.score.b)}:${Math.min(w.score.a, w.score.b)}`);
      s.save();
    }
  }
}
