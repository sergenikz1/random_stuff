// Отправка записанного ролика: в Telegram — окно выбора чата с готовым сообщением,
// в браузере — системное «Поделиться» с файлом, иначе — скачивание.
import { authString, startParam, tg } from './tg.js';

export async function uploadClip(clip, meta) {
  const r = await fetch('/api/clips', {
    method: 'POST',
    headers: {
      authorization: authString(), 'x-start-param': startParam(),
      'content-type': clip.type, 'x-clip-meta': encodeURIComponent(JSON.stringify(meta || {})),
    },
    body: clip.blob,
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || `Ошибка ${r.status}`);
  return j;
}

const ext = (type) => (type.includes('mp4') ? 'mp4' : 'webm');

// Возвращает: 'shared' | 'sent' (ушло в чат с ботом) | 'cancelled' | 'downloaded'.
export async function shareClip(clip, meta, { link, text } = {}) {
  const t = tg();
  // 1) Telegram: сервер загрузил ролик боту и подготовил сообщение — открываем выбор чата.
  if (t?.initData) {
    clip.uploaded ||= await uploadClip(clip, meta);
    const up = clip.uploaded;
    if (up.prepared && t.shareMessage && t.isVersionAtLeast?.('8.0')) {
      return new Promise((resolve) => t.shareMessage(up.prepared, (ok) => resolve(ok ? 'shared' : 'cancelled')));
    }
    if (up.sentToChat) return 'sent';
  }
  // 2) Системное «Поделиться» с файлом.
  const file = new File([clip.blob], `pushduel.${ext(clip.type)}`, { type: clip.type });
  if (navigator.canShare?.({ files: [file] })) {
    try { await navigator.share({ files: [file], text: [text, link].filter(Boolean).join('\n') }); return 'shared'; }
    catch (e) { if (e?.name === 'AbortError') return 'cancelled'; }
  }
  // 3) Скачивание.
  saveClip(clip);
  return 'downloaded';
}

export function saveClip(clip) {
  const t = tg();
  if (clip.uploaded?.url && t?.downloadFile && t.isVersionAtLeast?.('8.0')) {
    t.downloadFile({ url: new URL(clip.uploaded.url, location.origin).toString(), file_name: `pushduel.${ext(clip.type)}` });
    return;
  }
  const a = document.createElement('a');
  a.href = clip.url;
  a.download = `pushduel.${ext(clip.type)}`;
  document.body.appendChild(a);
  a.click();
  a.remove();
}
