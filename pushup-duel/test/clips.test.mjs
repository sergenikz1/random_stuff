// Ролики боёв: загрузка, отправка боту, сообщение для пересылки с приглашением.
import test from 'node:test';
import assert from 'node:assert/strict';
import { Store } from '../lib/server/store.js';
import { Hub } from '../lib/server/hub.js';
import { Api } from '../lib/server/api.js';
import { Clips, inviteLink, clipCaption } from '../lib/server/clips.js';

const config = { botUsername: 'pushduel_bot', appShortName: 'app', allowGuests: true };
const mp4 = Buffer.from([0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70]);

test('ссылка-приглашение и подпись', () => {
  assert.equal(inviteLink(config, 'tg42'), 'https://t.me/pushduel_bot/app?startapp=ref_tg42');
  assert.equal(inviteLink({ botUsername: 'b' }, 'g1'), 'https://t.me/b?start=ref_g1');
  assert.match(clipCaption({ reps: 31, opp: 'Борис', oppReps: 28 }), /против Борис: 31:28/);
  assert.match(clipCaption({ reps: 40 }), /40 отжиманий/);
});

test('без бота: ролик сохраняется и отдаётся по ссылке', async () => {
  const clips = new Clips({ config });
  const p = { id: 'g1', tgId: null };
  const r = await clips.create(p, mp4, 'video/mp4', { reps: 12 });
  assert.match(r.url, /^\/api\/clips\/[0-9a-f]{24}\.mp4$/);
  assert.equal(r.prepared, null);
  assert.equal(r.link, 'https://t.me/pushduel_bot/app?startapp=ref_g1');
  const f = clips.get(r.url.split('/').pop());
  assert.equal(f.type, 'video/mp4');
  assert.deepEqual([...f.buf], [...mp4]);
  assert.equal(clips.get('../../etc/passwd'), null);
  await assert.rejects(clips.create(p, mp4, 'image/png'), /MP4 или WebM/);
  await assert.rejects(clips.create(p, Buffer.alloc(0), 'video/mp4'), /Пустой/);
});

test('с ботом: видео уходит в чат, готовится сообщение для пересылки с кнопкой-приглашением', async () => {
  const calls = [];
  const fetchImpl = async (url, opts) => {
    const method = url.split('/').pop();
    calls.push(method);
    if (method === 'sendVideo') {
      assert.ok(opts.body instanceof FormData);
      assert.equal(opts.body.get('chat_id'), '42');
      assert.match(opts.body.get('reply_markup'), /startapp=ref_tg42/);
      return { json: async () => ({ ok: true, result: { video: { file_id: 'VID' } } }) };
    }
    if (method === 'savePreparedInlineMessage') {
      const b = JSON.parse(opts.body);
      assert.equal(b.user_id, 42);
      assert.equal(b.result.type, 'video');
      assert.equal(b.result.video_file_id, 'VID');
      assert.equal(b.result.reply_markup.inline_keyboard[0][0].url, 'https://t.me/pushduel_bot/app?startapp=ref_tg42');
      return { json: async () => ({ ok: true, result: { id: 'PREP1' } }) };
    }
    throw new Error(`unexpected ${method}`);
  };
  const clips = new Clips({ botToken: 'T', config, fetchImpl });
  const r = await clips.create({ id: 'tg42', tgId: 42 }, mp4, 'video/mp4;codecs=avc1', { reps: 30 });
  assert.deepEqual(calls, ['sendVideo', 'savePreparedInlineMessage']);
  assert.equal(r.prepared, 'PREP1');
  assert.equal(r.sentToChat, true);
});

test('WebM уходит файлом, а сбой бота не ломает загрузку', async () => {
  const seen = [];
  const clips = new Clips({ botToken: 'T', config, fetchImpl: async (url, opts) => {
    seen.push(url.split('/').pop());
    if (url.endsWith('sendDocument')) { assert.ok(opts.body.get('document')); return { json: async () => ({ ok: true, result: { document: { file_id: 'DOC' } } }) }; }
    return { json: async () => ({ ok: false, description: 'Bad Request' }) };
  } });
  const r = await clips.create({ id: 'tg7', tgId: 7 }, mp4, 'video/webm');
  assert.deepEqual(seen, ['sendDocument', 'savePreparedInlineMessage']);
  assert.equal(r.sentToChat, true);
  assert.equal(r.prepared, null);
  assert.match(r.url, /\.webm$/);
});

test('Api.uploadClip: нужен вход, ролик привязан к игроку', async () => {
  const store = new Store(null);
  const hub = new Hub(store, config, {});
  const api = new Api(store, hub, config);
  api.clips = new Clips({ config });
  assert.equal((await api.uploadClip({ auth: '', buf: mp4, type: 'video/mp4' })).status, 401);
  const r = await api.uploadClip({ auth: `guest ${'z'.repeat(20)}`, buf: mp4, type: 'video/mp4', meta: { reps: 5 } });
  assert.equal(r.status, 200);
  assert.match(r.body.link, /ref_g[0-9a-f]{14}$/);
  assert.equal((await api.uploadClip({ auth: `guest ${'z'.repeat(20)}`, buf: mp4, type: 'text/plain' })).status, 400);
  hub.close();
});
