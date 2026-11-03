// Пояса арен, турниры «до трёх поражений» и клановые войны.
import test from 'node:test';
import assert from 'node:assert/strict';
import { Store, privateProfile, applyActivity } from '../lib/server/store.js';
import { Compete } from '../lib/server/compete.js';
import { ensureEco, claimInbox } from '../lib/server/economy.js';
import { TOURNEYS, WAR } from '../lib/compete.js';

function setup() {
  const store = new Store(null);
  const sent = [];
  const notes = [];
  const premium = { sendStarsGift: async (p, stars) => { sent.push({ id: p.id, stars }); return { status: 'sent' }; } };
  const c = new Compete(store, { premium, notify: async (...a) => { notes.push(a); } });
  const mk = (id, rating = 1200) => { const { player } = store.getOrCreate(id, { name: id }); player.rating = rating; player.tgId = 1; ensureEco(player); return player; };
  return { store, c, mk, sent, notes };
}

test('пояс: свободный забирает первый победитель арены, чемпиона можно победить, защиты считаются', () => {
  const { store, c, mk } = setup();
  const a = mk('tgA', 1200), b = mk('tgB', 1210), d = mk('tgD', 1190);
  let n = c.onRanked(a, b, 3, 3);
  assert.equal(c.beltOf(a.id), 3);
  assert.deepEqual(n[a.id], { won: 3 });
  n = c.onRanked(a, d, 3, 3);
  assert.deepEqual(n[a.id], { defended: 3 });
  n = c.onRanked(b, a, 3, 3);
  assert.equal(c.beltOf(b.id), 3);
  assert.deepEqual(n[a.id], { lost: 3 });
  assert.equal(privateProfile(b, store).belt, 3);
  assert.ok(privateProfile(a, store).earnedBadges.includes('belt'));
});

test('пояс освобождается, если чемпион упал ниже арены; вызов — только чемпиону своей арены; недельная награда', async () => {
  const { store, c, mk } = setup();
  const a = mk('tgA', 1200), b = mk('tgB', 1180), low = mk('tgL', 1000);
  c.onRanked(a, b, 3, 3);
  assert.throws(() => c.canChallengeTitle(low, a), /своей арены/);
  assert.equal(c.canChallengeTitle(b, a), 3);
  store.beltWeek = '2000-01-03';
  await c.settle();
  assert.equal(a.inbox[0].pack, 'epic');
  a.rating = 1100;
  c.validateBelts();
  assert.equal(store.belts[3], undefined);
});

test('турнир: вход за кристаллы, ничья не считается, 3 поражения — конец и награда по победам', () => {
  const { c, mk } = setup();
  const p = mk('tgP');
  p.gems = 5;
  assert.throws(() => c.startRun(p, 'small'), /10 💎/);
  p.gems = 50;
  c.startRun(p, 'small');
  assert.equal(p.gems, 40);
  assert.throws(() => c.startRun(p, 'small'), /уже идёт/);
  assert.equal(c.canPlayRun(p, 'small'), true);
  assert.throws(() => c.canPlayRun(p, 'big'), /оплатите/);
  c.recordRun(p, 'small', 'win'); c.recordRun(p, 'small', 'draw'); c.recordRun(p, 'small', 'win');
  c.recordRun(p, 'small', 'loss'); c.recordRun(p, 'small', 'loss');
  const r = c.recordRun(p, 'small', 'loss');
  assert.equal(r.done, true);
  assert.equal(r.wins, 2);
  assert.deepEqual(r.reward, TOURNEYS.small.rewards[2]);
  assert.equal(p.runs.small, null);
  assert.equal(p.bestRuns.small, 2);
  assert.match(p.inbox[0].title, /2 победы/);
  const loot = claimInbox(p, p.inbox[0].id, () => 0.5);
  assert.ok(loot.loot.stickers.length >= 4);
  // Можно сразу начать новый забег.
  c.startRun(p, 'small');
  assert.equal(c.runsView(p).find((x) => x.kind === 'small').last.wins, 2);
});

test('большой турнир: максимум побед завершает забег, подарок Telegram — в почту', () => {
  const { store, c, mk } = setup();
  const p = mk('tgP');
  p.gems = 100;
  c.startRun(p, 'big');
  let r;
  for (let i = 0; i < TOURNEYS.big.maxWins; i++) r = c.recordRun(p, 'big', i % 4 === 3 ? 'loss' : 'win');
  // 12 итераций: 9 побед и 3 поражения — конец на третьем поражении.
  assert.equal(r.done, true);
  assert.equal(r.wins, 9);
  assert.ok(p.inbox.some((x) => x.gift === 15));
  p.gems = 100;
  c.startRun(p, 'big');
  for (let i = 0; i < TOURNEYS.big.maxWins; i++) r = c.recordRun(p, 'big', 'win');
  assert.equal(r.wins, 12);
  assert.ok(p.inbox.some((x) => x.gift === 100));
  assert.ok(store.feed.some((f) => f.text.includes('прошёл «Большой турнир»')));
});

test('клановая война: вызов капитаном, принятие, очки за отжимания и бои, итог с наградами', async () => {
  const { store, c, mk, notes } = setup();
  const capA = mk('tgCA'), capB = mk('tgCB'), a2 = mk('tgA2'), b2 = mk('tgB2');
  const A = store.createClan(capA, { name: 'Альфа' });
  const B = store.createClan(capB, { name: 'Бета' });
  store.joinClan(a2, A.id); store.joinClan(b2, B.id);
  await assert.rejects(c.declareWar(a2, B.id), /капитан/);
  const w = await c.declareWar(capA, B.id);
  assert.equal(notes.length, 1);
  await assert.rejects(c.declareWar(capA, B.id), /уже есть/);
  assert.throws(() => c.respondWar(capA, w.id, true), /капитан/);
  c.respondWar(capB, w.id, true);
  assert.equal(c.warOf(A.id, ['active']).id, w.id);
  // Отжимания приносят очки, бой войны — бонус.
  applyActivity(a2, { type: 'training', reps: 40, repTimes: [] }, store);
  applyActivity(b2, { type: 'training', reps: 30, repTimes: [] }, store);
  assert.equal(c.warOpponents(a2, b2), true);
  assert.equal(c.warOpponents(a2, capA), false);
  c.warDuel(b2, a2);
  const view = c.warView(B.id, b2);
  assert.equal(view.score.mine, 30 + WAR.duelWin);
  assert.equal(view.score.enemy, 40);
  // Итог после конца войны: награды тем, кто внёс хотя бы WAR.minReps.
  await c.settle(w.end + 1000);
  assert.equal(w.status, 'done');
  assert.equal(w.result, 'b');
  assert.ok(b2.inbox.some((x) => x.title.includes('победа') && x.pack === 'epic'));
  assert.ok(a2.inbox.some((x) => x.title.includes('поражение')));
  assert.ok(!capB.inbox.some((x) => x.title.includes('Война')), 'не внёс очков — без награды');
});

test('вызов на войну истекает, если капитан не ответил', async () => {
  const { store, c, mk } = setup();
  const capA = mk('tgCA'), capB = mk('tgCB');
  const A = store.createClan(capA, { name: 'Альфа' });
  const B = store.createClan(capB, { name: 'Бета' });
  const w = await c.declareWar(capA, B.id, 0);
  c.expireProposals(WAR.proposalHours * 3600e3 + 1);
  assert.equal(w.status, 'expired');
  assert.throws(() => c.respondWar(capB, w.id, true), /устарел/);
});
