// Премиум за звёзды, подарки Telegram, жетоны и музыка за приглашения — на моке Bot API.
import test from 'node:test';
import assert from 'node:assert/strict';
import { Store, applyActivity, privateProfile, publicCard, updateProfile } from '../lib/server/store.js';
import { Premium } from '../lib/server/premium.js';
import { SEASON, xpForLevel } from '../lib/pass.js';

const xpToLevel = (n) => { let x = 0; for (let l = 1; l < n; l++) x += xpForLevel(l); return x; };

function mockBot({ balance = 1000 } = {}) {
  const sent = [];
  return {
    sent,
    createInvoiceLink: async (body) => { mockBot.lastInvoice = body; return 'https://t.me/$invoice'; },
    getAvailableGifts: async () => ({ gifts: [
      { id: 'bear', star_count: 15 }, { id: 'heart', star_count: 15 }, { id: 'rose', star_count: 25 },
      { id: 'cake', star_count: 50 }, { id: 'ltd', star_count: 50, total_count: 10, remaining_count: 0 },
      { id: 'trophy', star_count: 100 },
    ] }),
    sendGift: async (body) => {
      const g = { 'bear': 15, 'heart': 15, 'rose': 25, 'cake': 50, 'trophy': 100 }[body.gift_id];
      if (balance < g) throw new Error('Bad Request: BALANCE_TOO_LOW not enough stars');
      balance -= g;
      sent.push(body);
      return true;
    },
  };
}

function setup(botOpts) {
  const store = new Store(null);
  const bot = mockBot(botOpts);
  const premium = new Premium(store, { bot, price: 299 });
  const { player } = store.getOrCreate('tg42', { name: 'Ника', tgId: 42 });
  player.tgId = 42;
  return { store, bot, premium, p: player };
}

const payload = (p) => `pass:${SEASON.id}:${p.id}`;

test('счёт в звёздах и проверка перед оплатой', async () => {
  const { premium, p } = setup();
  const r = await premium.invoice(p);
  assert.equal(r.link, 'https://t.me/$invoice');
  assert.equal(mockBot.lastInvoice.currency, 'XTR');
  assert.deepEqual(mockBot.lastInvoice.prices, [{ label: 'Премиум-пропуск', amount: 299 }]);
  const q = { invoice_payload: payload(p), currency: 'XTR', total_amount: 299, from: { id: 42 } };
  assert.equal(premium.checkout(q).ok, true);
  assert.equal(premium.checkout({ ...q, total_amount: 1 }).ok, false);
  assert.equal(premium.checkout({ ...q, from: { id: 7 } }).ok, false);
  assert.equal(premium.checkout({ ...q, invoice_payload: 'pass:999:tg42' }).ok, false);
});

test('оплата выдаёт премиум один раз, открывает премиум-трек и +20% опыта', () => {
  const { premium, store, p } = setup();
  assert.equal(privateProfile(p, store).premium, false);
  const pay = { invoice_payload: payload(p), total_amount: 299, telegram_payment_charge_id: 'ch1' };
  premium.paid(pay, 42);
  premium.paid(pay, 42); // повторное уведомление
  assert.equal(store.feed.filter((f) => f.text.includes('премиум')).length, 1);
  const prof = privateProfile(p, store);
  assert.equal(prof.premium, true);
  assert.ok(prof.unlocked.includes('title:patron'));
  assert.ok(prof.earnedBadges.includes('star'));
  assert.equal(premium.checkout({ invoice_payload: payload(p), currency: 'XTR', total_amount: 299 }).ok, false);
  const res = applyActivity(p, { type: 'training', reps: 10, repTimes: [] }, store);
  assert.ok(res.items.some((i) => i.label.startsWith('Премиум')));
});

test('подарки: только с премиумом и уровнем, без двойной выдачи, с повтором при нехватке звёзд', async () => {
  const { premium, store, bot, p } = setup({ balance: 20 });
  await assert.rejects(premium.claimGift(p, 8), /премиум/);
  premium.paid({ invoice_payload: payload(p), total_amount: 299, telegram_payment_charge_id: 'ch2' });
  await assert.rejects(premium.claimGift(p, 8), /8 уровне/);
  p.xp = xpToLevel(17);
  const gifts = privateProfile(p, store).gifts;
  assert.deepEqual(gifts.filter((g) => g.status === 'ready').map((g) => g.level), [8, 16]);
  const r1 = await premium.claimGift(p, 8);
  assert.equal(r1.status, 'sent');
  assert.equal(bot.sent[0].user_id, 42);
  assert.ok(['bear', 'heart'].includes(bot.sent[0].gift_id));
  await assert.rejects(premium.claimGift(p, 8), /уже отправлен/);
  const r2 = await premium.claimGift(p, 16); // осталось 5 звёзд — не хватает на 25
  assert.equal(r2.status, 'failed');
  assert.match(r2.error, /звёзды/);
  bot.sendGift = async (body) => { bot.sent.push(body); return true; };
  assert.equal((await premium.claimGift(p, 16)).status, 'sent');
  await assert.rejects(premium.claimGift(p, 9), /нет подарка/);
});

test('жетоны: витрина из полученных, чужие выбрать нельзя', () => {
  const { store, p } = setup();
  Object.assign(p, { wins: 12, bestWinStreak: 5, totalReps: 1200 });
  const card = publicCard(p, store);
  assert.equal(card.badges.length, 3);
  assert.equal(card.badges[0], 'streak5'); // самый редкий — первым
  updateProfile(p, { badges: ['reps1k'] }, store);
  assert.deepEqual(publicCard(p, store).badges, ['reps1k']);
  assert.throws(() => updateProfile(p, { badges: ['wins200'] }, store), /полученных/);
});

test('приглашения открывают музыку для боя', () => {
  const store = new Store(null);
  const { player: host } = store.getOrCreate('tg1', { name: 'Хост' });
  for (let i = 0; i < 5; i++) store.getOrCreate(`tg${100 + i}`, { name: `Друг ${i}` }, 'tg1');
  const prof = privateProfile(host, store);
  assert.equal(prof.invites, 5);
  for (const m of ['music:synthwave', 'music:chiptune', 'music:dnb']) assert.ok(prof.unlocked.includes(m), m);
  assert.ok(!prof.unlocked.includes('music:epic'));
  updateProfile(host, { equip: { cat: 'music', id: 'dnb' } }, store);
  assert.equal(host.equipped.music, 'dnb');
});
