// Сундуки, дорога трофеев, магазин и бусты.
import test from 'node:test';
import assert from 'node:assert/strict';
import { Store, privateProfile, applyActivity } from '../lib/server/store.js';
import { awardWinChest, unlockChest, openChest, claimRoad, buy, ensureEco } from '../lib/server/economy.js';
import { CHEST_CYCLE, CHESTS, gemsToOpen, shopOffersForDay, arenaOf, CHEST_POOL } from '../lib/economy.js';
import { dayKey, questsForDay } from '../lib/pass.js';

// Детерминированный генератор для проверки содержимого сундуков.
const seq = (...vals) => { let i = 0; return () => vals[i++ % vals.length]; };

function player() {
  const store = new Store(null);
  const { player: p } = store.getOrCreate('tg5', { name: 'Тест' });
  ensureEco(p);
  return { store, p };
}

test('сундуки за победы идут по циклу и не больше четырёх', () => {
  const { p } = player();
  const got = [];
  for (let i = 0; i < 5; i++) got.push(awardWinChest(p));
  assert.deepEqual(got.slice(0, 4).map((g) => g.type), CHEST_CYCLE.slice(0, 4));
  assert.equal(got[4].full, true);
  assert.equal(p.chestCycle, 4);
});

test('открытие: по таймеру бесплатно, раньше — за кристаллы, одновременно только один', () => {
  const { p } = player();
  awardWinChest(p); awardWinChest(p);
  const t0 = 1_000_000;
  unlockChest(p, 0, t0);
  assert.throws(() => unlockChest(p, 1, t0), /только один/);
  assert.throws(() => openChest(p, 0, {}, Math.random, t0 + 1000), /не открылся/);
  const left = CHESTS.wood.unlockMin * 60000 - 1000;
  p.gems = 0;
  assert.throws(() => openChest(p, 0, { gems: true }, Math.random, t0 + 1000), /Нужно/);
  p.gems = 50;
  const loot = openChest(p, 0, { gems: true }, seq(0.5), t0 + 1000);
  assert.equal(loot.spent, gemsToOpen(left));
  assert.equal(p.gems, 50 - loot.spent);
  assert.equal(p.chests[0], null);
  // Второй сундук открываем честно по таймеру.
  unlockChest(p, 1, t0);
  const free = openChest(p, 1, {}, seq(0.5), t0 + CHESTS.wood.unlockMin * 60000 + 1);
  assert.equal(free.spent, 0);
});

test('содержимое: монеты, опыт, предмет, повтор превращается в монеты', () => {
  const { p } = player();
  p.chests[0] = { type: 'magic', at: 0, unlockAt: 1 };
  const coins0 = p.coins, xp0 = p.xp;
  // rnd = 0 → минимум монет, кристаллы гарантированы, первый предмет пула оба раза (второй — повтор).
  const loot = openChest(p, 0, {}, () => 0, 10);
  assert.equal(loot.items.length, 2);
  assert.equal(loot.items[0].key, CHEST_POOL[0]);
  assert.equal(loot.items[1].duplicate, true);
  assert.ok(p.extraUnlocks.includes(CHEST_POOL[0]));
  assert.equal(p.coins - coins0, CHESTS.magic.coins[0] + 60);
  assert.equal(p.xp - xp0, CHESTS.magic.xp);
  assert.ok(loot.gems >= CHESTS.magic.gems[0]);
});

test('дорога трофеев: только после достижения порога и один раз', () => {
  const { p } = player();
  assert.throws(() => claimRoad(p, 1050), /Откроется/);
  p.rating = 1060; ensureEco(p);
  claimRoad(p, 1025);
  assert.equal(p.coins, 50);
  claimRoad(p, 1050);
  assert.equal(p.chests.filter(Boolean)[0].type, 'silver');
  assert.throws(() => claimRoad(p, 1050), /уже получена/);
  // Лучший рейтинг сохраняется, даже если текущий упал.
  p.rating = 900; ensureEco(p);
  assert.equal(p.bestRating, 1060);
  assert.equal(arenaOf(900).id, 1);
  assert.equal(arenaOf(1460).name, 'Боксёрский ринг');
});

test('магазин: предложения дня одинаковые, покупка за монеты, буст удваивает опыт', () => {
  const { p, store } = player();
  const offers = shopOffersForDay(dayKey());
  assert.equal(offers.length, 3);
  assert.deepEqual(shopOffersForDay(dayKey()), offers);
  assert.throws(() => buy(p, offers[0].id), /Нужно/);
  p.coins = 5000;
  buy(p, offers[0].id);
  assert.ok(privateProfile(p, store).unlocked.includes(offers[0].item));
  assert.throws(() => buy(p, offers[0].id), /Уже есть/);
  p.gems = 100;
  const r = buy(p, 'chest_silver', seq(0.5));
  assert.ok(r.loot.coins > 0);
  assert.equal(p.gems, 85);
  buy(p, 'boost_xp');
  const res = applyActivity(p, { type: 'training', reps: 10, repTimes: [] }, store);
  assert.ok(res.items.some((i) => i.label.startsWith('Буст')));
  assert.throws(() => buy(p, 'nope'), /не найдено/);
});

test('задания дня дают кристаллы', () => {
  const { p, store } = player();
  // Задание дня выбирается по дате — доводим первое из них до цели, какое бы ни выпало.
  const q = questsForDay(dayKey())[0];
  p.quests = { day: dayKey(), prog: { [q.kind]: q.goal }, done: [] };
  const gems0 = p.gems;
  const res = applyActivity(p, { type: 'training', reps: 0, repTimes: [] }, store);
  assert.ok(res.questsDone.some((d) => d.text === q.text));
  assert.equal(p.gems - gems0, res.questsDone.length * 5);
});
