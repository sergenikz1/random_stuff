// Аватар по умолчанию — фото из Telegram.
import test from 'node:test';
import assert from 'node:assert/strict';
import { Store, publicCard, updateProfile } from '../lib/server/store.js';
import { Photos, isPhotoUrl } from '../lib/server/photos.js';
import { authenticate } from '../lib/server/auth.js';
import { signInitData } from '../lib/server/telegram.js';

test('фото из initData попадает в профиль и становится аватаром по умолчанию', () => {
  const token = '123:abc';
  const user = JSON.stringify({ id: 42, first_name: 'Ира', photo_url: 'https://t.me/i/userpic/320/ira.jpg' });
  const initData = signInitData({ user, auth_date: String(Math.floor(Date.now() / 1000)) }, token);
  const who = authenticate(`tma ${initData}`, { botToken: token, initDataMaxAge: 3600 });
  assert.equal(who.photo, 'https://t.me/i/userpic/320/ira.jpg');

  const store = new Store(null);
  const { player } = store.getOrCreate(who.id, who);
  assert.equal(player.look.src, 'tg');
  assert.equal(publicCard(player, store).look.photo, `/api/photo/${player.id}`);

  // Выбрал фигуру — фото больше не показываем; вернулся к фото — снова показываем.
  updateProfile(player, { look: { ...player.look, src: 'fig' } }, store);
  assert.equal(publicCard(player, store).look.photo, undefined);
  updateProfile(player, { look: { ...player.look, src: 'tg' } }, store);
  assert.ok(publicCard(player, store).look.photo);
});

test('гость без Telegram и без бота — без фото, показывается фигура', () => {
  const store = new Store(null);
  const { player } = store.getOrCreate('gguest', { name: 'Гость' });
  assert.equal(publicCard(player, store).look.photo, undefined);
});

test('ссылки на фото принимаются только с доменов Telegram', () => {
  assert.ok(isPhotoUrl('https://t.me/i/userpic/320/x.jpg'));
  assert.ok(isPhotoUrl('https://cdn4.telegram.org/file/x.jpg'));
  assert.ok(!isPhotoUrl('https://evil.example/x.jpg'));
  assert.ok(!isPhotoUrl('javascript:alert(1)'));
});

test('без photo_url фото берётся через Bot API и кэшируется; токен не уходит клиенту', async () => {
  const store = new Store(null);
  const { player } = store.getOrCreate('tg7', { name: 'Бот', tgId: 7 });
  const calls = [];
  const fetchImpl = async (url, opts) => {
    calls.push(url);
    if (url.endsWith('/getUserProfilePhotos')) return { json: async () => ({ ok: true, result: { photos: [[{ file_id: 's', width: 160 }, { file_id: 'm', width: 320 }]] } }) };
    if (url.endsWith('/getFile')) { assert.equal(JSON.parse(opts.body).file_id, 'm'); return { json: async () => ({ ok: true, result: { file_path: 'photos/a.jpg' } }) }; }
    return { ok: true, headers: new Map([['content-type', 'image/jpeg']]), arrayBuffer: async () => new Uint8Array([1, 2, 3]).buffer };
  };
  const photos = new Photos(store, { botToken: 'T', fetchImpl });
  const r = await photos.get(player.id);
  assert.deepEqual([...r.buf], [1, 2, 3]);
  assert.equal(r.redirect, undefined);
  await photos.get(player.id);
  assert.equal(calls.length, 3, 'второй запрос — из кэша');
  assert.equal(await photos.get('nobody'), null);
});
