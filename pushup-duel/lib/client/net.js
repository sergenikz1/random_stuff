// REST и WebSocket до своего же сервера (Next.js + ws на одном порту).
import { authString, startParam } from './tg.js';

export async function api(path, { method = 'GET', body, query } = {}) {
  const qs = query ? `?${new URLSearchParams(query)}` : '';
  const r = await fetch(`/api/${path}${qs}`, {
    method,
    headers: {
      authorization: authString(),
      'x-start-param': startParam(),
      ...(body ? { 'content-type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    cache: 'no-store',
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || `Ошибка ${r.status}`);
  return j;
}

// Соединение с автопереподключением. on(type, fn) — подписка, возвращает отписку.
export class Socket {
  constructor() {
    this.handlers = new Map();
    this.status = 'connecting';
    this.retry = 0;
    this.closedForGood = false;
    this.connect();
  }

  connect() {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(`${proto}://${location.host}/ws`);
    this.ws = ws;
    this.setStatus('connecting');
    ws.onopen = () => {
      this.retry = 0;
      ws.send(JSON.stringify({ t: 'hello', auth: authString(), startParam: startParam() }));
    };
    ws.onmessage = (e) => {
      let m;
      try { m = JSON.parse(e.data); } catch { return; }
      if (m.t === 'welcome') this.setStatus('online');
      if (m.t === 'kicked' || m.t === 'auth_failed') this.closedForGood = true;
      this.emit(m.t, m);
      this.emit('*', m);
    };
    ws.onclose = () => {
      this.setStatus('offline');
      if (this.closedForGood) return;
      const delay = Math.min(10000, 800 * 2 ** this.retry++);
      this.timer = setTimeout(() => this.connect(), delay);
    };
    ws.onerror = () => {};
  }

  setStatus(s) {
    this.status = s;
    this.emit('status', s);
  }

  send(msg) {
    if (this.ws?.readyState === 1) { this.ws.send(JSON.stringify(msg)); return true; }
    return false;
  }

  on(type, fn) {
    if (!this.handlers.has(type)) this.handlers.set(type, new Set());
    this.handlers.get(type).add(fn);
    return () => this.handlers.get(type)?.delete(fn);
  }

  emit(type, m) {
    for (const fn of this.handlers.get(type) || []) {
      try { fn(m); } catch (e) { console.error(e); }
    }
  }

  close() {
    this.closedForGood = true;
    clearTimeout(this.timer);
    this.ws?.close();
  }
}
