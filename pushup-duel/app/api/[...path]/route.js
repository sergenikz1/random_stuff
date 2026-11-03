// Все REST-запросы уходят в lib/server/api.js. Экземпляр создаёт server.mjs и кладёт в globalThis:
// так Next.js и WebSocket-хаб работают с одним хранилищем в одном процессе.
export const dynamic = 'force-dynamic';

async function handle(req, { params }) {
  const ctx = globalThis.__pushduel;
  if (!ctx) {
    return Response.json({ error: 'Запустите сервер через `npm run dev` или `npm start` (server.mjs)' }, { status: 503 });
  }
  const { path } = await params;
  const url = new URL(req.url);
  // Фото профиля Telegram: открытая ссылка для <img>, без авторизации.
  if (req.method === 'GET' && path?.[0] === 'photo' && path[1] && ctx.photos) {
    const ph = await ctx.photos.get(path[1]);
    if (!ph) return new Response(null, { status: 404 });
    if (ph.redirect) return Response.redirect(ph.redirect, 302);
    return new Response(ph.buf, { headers: { 'content-type': ph.type, 'cache-control': 'public, max-age=3600' } });
  }
  const body = req.method === 'POST' ? await req.json().catch(() => ({})) : null;
  const r = await ctx.api.handle({
    method: req.method,
    path: path || [],
    query: Object.fromEntries(url.searchParams),
    body,
    auth: req.headers.get('authorization') || '',
    startParam: req.headers.get('x-start-param') || '',
  });
  return Response.json(r.body, { status: r.status, headers: { 'cache-control': 'no-store' } });
}

export const GET = handle;
export const POST = handle;
