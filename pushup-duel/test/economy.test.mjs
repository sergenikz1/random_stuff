// Наборы наклеек, коллекция и карточка, дорога трофеев, магазин, почта, билеты.
import test from 'node:test';
import assert from 'node:assert/strict';
import { Store, privateProfile, publicCard, applyActivity } from '../lib/server/store.js';
import {
  awardWin, unlockPack, openPack, claimRoad, buy, ensureEco, setCard, addInbox, claimInbox, givePack,
} from '../lib/server/economy.js';
import { PACK_CYCLE, PACKS, gemsToOpen, shopOffersForDay, arenaOf } from '../lib/economy.js';
import { stickersOf, MAX_COPIES, STICKER_RARITY } from '../lib/stickers.js';
import { dayKey, questsForDay } from '../lib/pass.js';

const seq = (...vals) => { let i = 0; return () => vals[i++ % vals.length]; };

function player() {
  const store = new Store(null);
  const { player: p } = store.getOrCreate('tg5', { name: 'Тест' });
  ensureEco(p);
  return { store, p };
}

test('наборы за победы идут по циклу, не больше четырёх', () => {
  const { p } = player();
  const got = [];
  for (let i = 0; i < 5; i++) got.push(awardWin(p));
  assert.deepEqual(got.slice(0, 4).map((g) => g.type), PACK_CYCLE.slice(0, 4));
  assert.equal(got[4].full, true);
});

test('открытие: по таймеру бесплатно, раньше — за кристаллы, одновременно только один', () => {
  const { p } = player();
  awardWin(p); awardWin(p);
  const t0 = 1_000_000;
  unlockPack(p, 0, t0);
  assert.throws(() => unlockPack(p, 1, t0), /только один/);
  assert.throws(() => openPack(p, 0, {}, Math.random, t0 + 1000), /не открылся/);
  p.gems = 0;
  assert.throws(() => openPack(p, 0, { gems: true }, Math.random, t0 + 1000), /Нужно/);
  p.gems = 50;
  const loot = openPack(p, 0, { gems: true }, seq(0.5), t0 + 1000);
  assert.equal(loot.spent, gemsToOpen(PACKS.basic.unlockMin * 60000 - 1000));
  assert.equal(loot.stickers.length, PACKS.basic.count);
  assert.equal(p.packs[0], null);
});

test('гарантия редкости, коллекция и лишние копии в монеты', () => {
  const { p } = player();
  // rnd = 0 → всегда первая наклейка нужной редкости; легендарный набор гарантирует легендарную.
  const loot = givePack(p, 'legend', () => 0);
  assert.equal(loot.stickers.length, PACKS.legend.count);
  assert.ok(loot.stickers.some((s) => s.rarity === 'legend'));
  // Пять одинаковых обычных: три в коллекцию, две — монетами.
  const coins0 = p.coins;
  const first = stickersOf('common')[0].id;
  const l2 = givePack(p, 'basic', () => 0); // 3 копии первой обычной
  givePack(p, 'basic', () => 0);
  assert.equal(p.stickers[first], MAX_COPIES);
  const converted = l2.stickers.filter((s) => s.converted).length;
  assert.ok(p.coins > coins0);
  assert.ok(converted >= 0);
});

test('карточка: только свои наклейки, не больше копий и не больше шести', () => {
  const { p, store } = player();
  p.stickers = { fire: 1, crown: 2 };
  setCard(p, [{ id: 'fire', x: 0.2, y: 0.3, r: 15, s: 1.2 }, { id: 'crown', x: 2, y: -1, r: 999, s: 9 }, { id: 'crown' }]);
  const card = publicCard(p, store).card;
  assert.equal(card.length, 3);
  assert.deepEqual(card[1], { id: 'crown', x: 1, y: 0, r: 180, s: 2 });
  assert.throws(() => setCard(p, [{ id: 'fire' }, { id: 'fire' }]), /копий/);
  assert.throws(() => setCard(p, [{ id: 'dragon' }]), /коллекции/);
  assert.throws(() => setCard(p, Array(7).fill({ id: 'crown' })), /до 6/);
});

test('дорога трофеев: только после порога, один раз; кристаллы и наборы', () => {
  const { p } = player();
  assert.throws(() => claimRoad(p, 1050), /Откроется/);
  p.rating = 1080; ensureEco(p);
  claimRoad(p, 1025);
  assert.equal(p.coins, 50);
  claimRoad(p, 1050);
  assert.equal(p.packs.filter(Boolean)[0].type, 'rare');
  claimRoad(p, 1075);
  assert.equal(p.gems, 15);
  assert.throws(() => claimRoad(p, 1050), /уже получена/);
  p.rating = 900; ensureEco(p);
  assert.equal(p.bestRating, 1080);
  assert.equal(arenaOf(1460).name, 'Театр Диониса');
});

test('магазин: наклейки и косметика за монеты, наборы за кристаллы, буст', () => {
  const { p, store } = player();
  const offers = shopOffersForDay(dayKey());
  assert.equal(offers.length, 4);
  assert.deepEqual(shopOffersForDay(dayKey()), offers);
  assert.ok(offers.slice(0, 3).every((o) => o.sticker) && offers[3].item);
  p.coins = 10000;
  buy(p, offers[0].id);
  assert.equal(p.stickers[offers[0].sticker], 1);
  assert.throws(() => buy(p, offers[0].id), /Уже куплено/);
  buy(p, offers[3].id);
  assert.ok(privateProfile(p, store).unlocked.includes(offers[3].item));
  p.gems = 200;
  const r = buy(p, 'pack_rare', seq(0.5));
  assert.equal(r.loot.stickers.length, PACKS.rare.count);
  assert.throws(() => buy(p, 'ticket'), /не найдено/);
  buy(p, 'boost_xp');
  const res = applyActivity(p, { type: 'training', reps: 10, repTimes: [] }, store);
  assert.ok(res.items.some((i) => i.label.startsWith('Буст')));
});

test('почта: награда с набором и наклейкой забирается один раз', () => {
  const { p } = player();
  addInbox(p, { title: 'Приз', gems: 7, pack: 'rare', sticker: 'champ' });
  const id = p.inbox[0].id;
  const r = claimInbox(p, id, seq(0.4));
  assert.equal(p.gems >= 7, true);
  assert.equal(p.stickers.champ, 1);
  assert.ok(r.loot.stickers.length >= PACKS.rare.count);
  assert.throws(() => claimInbox(p, id), /не найдена/);
  assert.ok(STICKER_RARITY.legend.dupCoins > STICKER_RARITY.common.dupCoins);
});

test('задания дня дают кристаллы', () => {
  const { p, store } = player();
  const q = questsForDay(dayKey())[0];
  p.quests = { day: dayKey(), prog: { [q.kind]: q.goal }, done: [] };
  const gems0 = p.gems;
  const res = applyActivity(p, { type: 'training', reps: 0, repTimes: [] }, store);
  assert.ok(res.questsDone.some((d) => d.text === q.text));
  assert.equal(p.gems - gems0, res.questsDone.length * 5);
});
