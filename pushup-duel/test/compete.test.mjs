// Пояса арен и турнир недели.
import test from 'node:test';
import assert from 'node:assert/strict';
import { Store, privateProfile } from '../lib/server/store.js';
import { Compete } from '../lib/server/compete.js';
import { ensureEco } from '../lib/server/economy.js';
import { tourneyState, TOURNEY } from '../lib/compete.js';

function setup() {
  const store = new Store(null);
  const sent = [];
  const premium = { sendStarsGift: async (p, stars) => { sent.push({ id: p.id, stars }); return { status: 'sent' }; } };
  const c = new Compete(store, { premium });
  const mk = (id, rating) => { const { player } = store.getOrCreate(id, { name: id }); player.rating = rating; player.tgId = 1; ensureEco(player); return player; };
  return { store, c, mk, sent };
}

test('пояс: свободный забирает первый победитель арены, чемпиона можно победить, защиты считаются', () => {
  const { store, c, mk } = setup();
  const a = mk('tgA', 1200), b = mk('tgB', 1210), d = mk('tgD', 1190);
  let n = c.onRanked(a, b, 3, 3);
  assert.equal(c.beltOf(a.id), 3);
  assert.deepEqual(n[a.id], { won: 3 });
  n = c.onRanked(a, d, 3, 3);
  assert.deepEqual(n[a.id], { defended: 3 });
  assert.equal(store.belts[3].defenses, 1);
  n = c.onRanked(b, a, 3, 3);
  assert.equal(c.beltOf(b.id), 3);
  assert.deepEqual(n[a.id], { lost: 3 });
  assert.ok(store.feed.some((f) => f.text.includes('отбирает пояс')));
  assert.equal(privateProfile(b, store).belt, 3);
  assert.ok(privateProfile(a, store).earnedBadges.includes('belt'));
});

test('пояс освобождается, если чемпион упал ниже арены; вызов — только чемпиону своей арены', () => {
  const { store, c, mk } = setup();
  const a = mk('tgA', 1200), b = mk('tgB', 1180), low = mk('tgL', 1000);
  c.onRanked(a, b, 3, 3);
  assert.throws(() => c.canChallengeTitle(low, a), /своей арены/);
  assert.equal(c.canChallengeTitle(b, a), 3);
  const n = c.onTitle(3, b, a);
  assert.deepEqual(n[b.id], { won: 3 });
  b.rating = 1100;
  c.validateBelts();
  assert.equal(store.belts[3], undefined);
});

test('награда чемпиону на смене недели', async () => {
  const { store, c, mk } = setup();
  const a = mk('tgA', 1500);
  c.onRanked(a, mk('tgB', 1490), 5, 5);
  store.beltWeek = '2000-01-03';
  await c.settle();
  assert.equal(a.inbox.length, 1);
  assert.equal(a.inbox[0].pack, 'legend');
  assert.equal(a.beltWeeks, 1);
});

test('турнир: регистрация за билет, очки, лимит матчей, призы и подарки после окончания', async () => {
  const { store, c, mk, sent } = setup();
  const st = tourneyState();
  const during = st.start + 3600000;
  const a = mk('tgA', 1200), b = mk('tgB', 1200), d = mk('tgD', 1200);
  assert.throws(() => c.register(a, during), /билет/);
  for (const p of [a, b, d]) { p.tickets = 1; c.register(p, during); }
  assert.equal(a.tickets, 0);
  assert.throws(() => c.register(a, during), /уже участвуете/);
  // В будни турнир ещё не идёт.
  assert.throws(() => c.canPlay(a, st.start - 1000), /пятницу/);
  assert.equal(c.canPlay(a, during), true);
  for (let i = 0; i < 4; i++) { c.record(a, 'win', 30); c.record(b, 'loss', 20); c.record(d, 'draw', 25); }
  const e = c.entry(a, st.week);
  assert.equal(e.points, 4 * TOURNEY.winPoints);
  e.matches = TOURNEY.matches;
  assert.throws(() => c.canPlay(a, during), /сыграны/);
  const view = c.tourneyView(b, during);
  assert.equal(view.top[0].name, 'tgA');
  assert.equal(view.place, 3);
  await c.settle(st.end + 1000);
  assert.ok(store.tourneys[st.week].settled);
  assert.equal(a.inbox.find((x) => x.title.includes('1 место')).pack, 'legend');
  assert.deepEqual(sent[0], { id: 'tgA', stars: 100 });
  assert.equal(sent.length, 3); // 1-е место — 100 ★, 2-е и 3-е — по 50 ★
  // Повторное подведение итогов ничего не дублирует.
  await c.settle(st.end + 5000);
  assert.equal(sent.length, 3);
});
