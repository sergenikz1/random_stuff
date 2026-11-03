// Сервер целиком, без Next.js: хаб по настоящему WebSocket, REST API, подпись Telegram.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import { Store } from '../lib/server/store.js';
import { Hub } from '../lib/server/hub.js';
import { Api } from '../lib/server/api.js';
import { validateInitData, signInitData } from '../lib/server/telegram.js';
import { levelInfo, questsForDay, combosFromTimes } from '../lib/pass.js';

const BOT = '123456:TEST-TOKEN';
const config = { botToken: BOT, allowGuests: true, initDataMaxAge: 3600 };

async function setup(opts = {}) {
  const store = new Store(null);
  const hub = new Hub(store, config, { countdownMs: 50, rankedMs: 400, readyTimeoutMs: 2000, mmIntervalMs: 50, minRepGapMs: 0, ...opts });
  const api = new Api(store, hub, config);
  const server = http.createServer();
  const wss = new WebSocketServer({ server });
  wss.on('connection', (ws) => hub.attach(ws));
  await new Promise((r) => server.listen(0, r));
  const url = `ws://127.0.0.1:${server.address().port}`;
  const close = async () => { hub.close(); for (const c of wss.clients) c.terminate(); await new Promise((r) => server.close(r)); };
  return { store, hub, api, url, close };
}

function client(url, auth, startParam = '') {
  const ws = new WebSocket(url);
  const inbox = [];
  const waiters = [];
  ws.on('message', (raw) => {
    const m = JSON.parse(raw);
    const i = waiters.findIndex((w) => w.t === m.t);
    if (i >= 0) waiters.splice(i, 1)[0].resolve(m); else inbox.push(m);
  });
  const next = (t, ms = 3000) => {
    const i = inbox.findIndex((m) => m.t === t);
    if (i >= 0) return Promise.resolve(inbox.splice(i, 1)[0]);
    return new Promise((resolve, reject) => {
      const w = { t, resolve };
      waiters.push(w);
      setTimeout(() => reject(new Error(`нет сообщения ${t}`)), ms);
    });
  };
  const send = (m) => ws.send(JSON.stringify(m));
  const opened = new Promise((r) => ws.on('open', r)).then(() => send({ t: 'hello', auth, startParam }));
  return { ws, next, send, opened };
}

const tgAuth = (id, name) => `tma ${signInitData({
  auth_date: String(Math.floor(Date.now() / 1000)),
  query_id: 'AAA',
  user: JSON.stringify({ id, first_name: name, username: name.toLowerCase() }),
}, BOT)}`;

test('подпись initData: верная принимается, подделка — нет', () => {
  const good = signInitData({ auth_date: String(Math.floor(Date.now() / 1000)), user: JSON.stringify({ id: 7, first_name: 'Аня' }) }, BOT);
  assert.equal(validateInitData(good, BOT).user.id, 7);
  assert.equal(validateInitData(good.replace('7', '8'), BOT), null);
  assert.equal(validateInitData(good, '999:OTHER'), null);
  const old = signInitData({ auth_date: '1000', user: JSON.stringify({ id: 7 }) }, BOT);
  assert.equal(validateInitData(old, BOT, 3600), null);
});

test('рейтинговая дуэль: подбор, повторы, итог и Эло', async () => {
  const s = await setup();
  try {
    const a = client(s.url, tgAuth(1, 'Аня'));
    const b = client(s.url, tgAuth(2, 'Боря'));
    await Promise.all([a.opened, b.opened]);
    const wa = await a.next('welcome');
    await b.next('welcome');
    assert.equal(wa.profile.name, 'Аня');
    a.send({ t: 'queue' });
    b.send({ t: 'queue' });
    const ma = await a.next('matched');
    await b.next('matched');
    assert.equal(ma.opponent.name, 'Боря');
    a.send({ t: 'ready' });
    b.send({ t: 'ready' });
    await a.next('start');
    await b.next('start');
    await new Promise((r) => setTimeout(r, 80));
    for (let i = 0; i < 5; i++) a.send({ t: 'rep' });
    for (let i = 0; i < 3; i++) b.send({ t: 'rep' });
    const opp = await b.next('opp_rep');
    assert.equal(opp.count, 1);
    const ra = await a.next('match_over');
    const rb = await b.next('match_over');
    assert.equal(ra.outcome, 'win');
    assert.equal(rb.outcome, 'loss');
    assert.equal(ra.me.reps, 5);
    assert.equal(rb.me.reps, 3);
    assert.ok(ra.me.delta > 0 && rb.me.delta < 0);
    assert.equal(ra.profile.rating, 1000 + ra.me.delta);
    assert.ok(ra.progress.total > rb.progress.total);
    assert.ok(s.store.feed.some((f) => f.text.includes('Аня побеждает Боря')));
    a.ws.close(); b.ws.close();
  } finally { await s.close(); }
});

test('сдача в бою — поражение, отказ до старта — отмена без штрафа', async () => {
  const s = await setup({ rankedMs: 5000 });
  try {
    const a = client(s.url, 'guest aaaaaaaaaaaaaaaaaaaa');
    const b = client(s.url, 'guest bbbbbbbbbbbbbbbbbbbb');
    await Promise.all([a.opened, b.opened, a.next('welcome'), b.next('welcome')]);
    a.send({ t: 'queue' }); b.send({ t: 'queue' });
    await a.next('matched'); await b.next('matched');
    a.send({ t: 'forfeit' });
    assert.match((await b.next('match_cancelled')).msg, /отказался/);
    a.send({ t: 'queue' }); b.send({ t: 'queue' });
    await a.next('matched'); await b.next('matched');
    a.send({ t: 'ready' }); b.send({ t: 'ready' });
    await a.next('start'); await b.next('start');
    b.send({ t: 'forfeit' });
    const ra = await a.next('match_over');
    assert.equal(ra.outcome, 'win');
    assert.equal(ra.reason, 'forfeit');
    a.ws.close(); b.ws.close();
  } finally { await s.close(); }
});

test('комната с другом по коду: без Эло, игроки становятся друзьями', async () => {
  const s = await setup();
  try {
    const a = client(s.url, tgAuth(10, 'Вера'));
    const b = client(s.url, tgAuth(11, 'Гоша'));
    await Promise.all([a.opened, b.opened, a.next('welcome'), b.next('welcome')]);
    a.send({ t: 'room_create', durationSec: 30 });
    const room = await a.next('room_created');
    assert.match(room.code, /^\d{4}$/);
    b.send({ t: 'room_join', code: room.code });
    const m = await b.next('matched');
    assert.equal(m.mode, 'friend');
    assert.equal(m.durationMs, 30000);
    await a.next('matched');
    b.send({ t: 'room_join', code: '0000' });
    assert.match((await b.next('error')).msg, /матче|не найдена/);
    a.send({ t: 'ready' }); b.send({ t: 'ready' });
    await a.next('start');
    a.send({ t: 'forfeit' });
    const rb = await b.next('match_over');
    assert.equal(rb.me.delta, 0);
    assert.ok(s.store.get('tg10').friends.includes('tg11'));
    a.ws.close(); b.ws.close();
  } finally { await s.close(); }
});

test('REST: реферал, кланы, рейтинг недели, тренировка', async () => {
  const s = await setup();
  try {
    const call = (auth, method, path, body, query = {}, startParam = '') =>
      s.api.handle({ method, path: path.split('/').filter(Boolean), query, body, auth, startParam });
    const anna = tgAuth(21, 'Анна');
    assert.equal((await call('tma garbage', 'GET', 'me')).status, 401);
    const me = await call(anna, 'GET', 'me');
    assert.equal(me.status, 200);
    // Приглашённый по ссылке ref_tg21.
    const bob = tgAuth(22, 'Боб');
    const r = await call(bob, 'GET', 'me', null, {}, 'ref_tg21');
    assert.equal(r.body.referral.inviter, 'Анна');
    assert.equal(s.store.get('tg21').invites, 1);
    assert.ok(s.store.get('tg21').extraUnlocks.includes('avatar:🤝'));

    const created = await call(anna, 'POST', 'clans', { name: 'Железо', emoji: '⚡' });
    assert.equal(created.status, 200, JSON.stringify(created.body));
    const code = created.body.clan.id;
    assert.equal((await call(anna, 'POST', 'clans', { name: 'Железо' })).status, 400);
    const joined = await call(bob, 'POST', `clans/${code}/join`);
    assert.equal(joined.body.clan.members, 2);
    assert.equal(joined.body.clan.roster.length, 2);

    const times = Array.from({ length: 20 }, (_, i) => 1000 + i * 900);
    const solo = await call(bob, 'POST', 'solo', { type: 'training', durationMs: 30000, repTimes: times });
    assert.equal(solo.status, 200, JSON.stringify(solo.body));
    assert.equal(solo.body.profile.weekReps, 20);
    assert.ok(solo.body.progress.items.some((i) => i.label.startsWith('Комбо')));
    // Подряд — отказ (антиспам), а нечеловеческий темп отсекается.
    assert.equal((await call(bob, 'POST', 'solo', { type: 'training', durationMs: 1000, repTimes: [1, 2, 3] })).status, 400);

    const week = await call(anna, 'GET', 'leaderboard', null, { type: 'week' });
    assert.equal(week.body.top[0].name, 'Боб');
    const clan = await call(anna, 'GET', `clans/${code}`);
    assert.equal(clan.body.clan.week, 20);
    const leave = await call(anna, 'POST', 'clans/leave');
    assert.equal(leave.status, 200);
    assert.equal(s.store.clans.get(code).ownerId, 'tg22');
    // Нельзя надеть то, что не открыто.
    assert.equal((await call(anna, 'POST', 'profile', { equip: { cat: 'frame', id: 'galaxy' } })).status, 400);
    assert.equal((await call(anna, 'POST', 'profile', { avatar: '🤝' })).status, 200);
  } finally { await s.close(); }
});

test('пропуск: уровни, задания дня, комбо', () => {
  assert.equal(levelInfo(0).level, 1);
  assert.equal(levelInfo(100).level, 2);
  assert.equal(levelInfo(1e9).level, 30);
  const q = questsForDay('2026-11-02');
  assert.equal(q.length, 3);
  assert.equal(new Set(q.map((x) => x.kind)).size, 3);
  assert.deepEqual(questsForDay('2026-11-02'), q);
  const c = combosFromTimes([0, 500, 1000, 1500, 2000, 9000]);
  assert.equal(c.best, 5);
  assert.equal(c.xp, 5);
});
