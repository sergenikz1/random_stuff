// Один сервер на всё: Next.js (страницы и API) + WebSocket для живых дуэлей на том же порту.
import http from 'node:http';
import path from 'node:path';
import next from 'next';
import { WebSocketServer } from 'ws';
import { Store } from './lib/server/store.js';
import { Hub } from './lib/server/hub.js';
import { Api } from './lib/server/api.js';
import { startBot } from './lib/server/telegram.js';
import { Premium } from './lib/server/premium.js';
import { Compete } from './lib/server/compete.js';

const dev = process.env.NODE_ENV !== 'production';
const port = Number(process.env.PORT) || 3000;
const botToken = process.env.BOT_TOKEN || '';
const config = {
  botToken,
  botUsername: (process.env.BOT_USERNAME || '').replace(/^@/, ''),
  appShortName: process.env.APP_SHORT_NAME || '',
  webAppUrl: process.env.WEBAPP_URL || '',
  // Без токена бота работает только гостевой вход (для разработки в браузере).
  allowGuests: process.env.ALLOW_GUESTS ? process.env.ALLOW_GUESTS === '1' : !botToken,
  initDataMaxAge: Number(process.env.INIT_DATA_MAX_AGE) || 24 * 3600,
};

const dataFile = process.env.DATA_FILE || path.resolve('data/pushduel.json');
const store = new Store(dataFile, {
  communityGoal: Number(process.env.COMMUNITY_GOAL) || 20000,
  premiumPrice: Number(process.env.PASS_PRICE_STARS) || undefined,
});

// Бот нужен для вызовов на дуэль, оплаты звёздами и подарков. Без токена всё остальное работает.
let premium = null;
let bot = null;
if (botToken && process.env.BOT_POLLING !== '0') {
  bot = startBot({
    token: botToken,
    webAppUrl: config.webAppUrl,
    onCheckout: (q) => premium.checkout(q),
    onPaid: (payment, fromId) => premium.paid(payment, fromId),
  });
  console.log('Бот запущен (long polling)');
}

const hub = new Hub(store, config, {
  rankedMs: (Number(process.env.RANKED_SECONDS) || 60) * 1000,
  notify: bot ? bot.notify : null,
});
const api = new Api(store, hub, config);
premium = new Premium(store, { bot, hub, price: store.premiumPrice, devPayments: !botToken && config.allowGuests });
api.premium = premium;
// Пояса и турнир: итоги недели подводятся раз в минуту (и при запросах турнира).
const compete = new Compete(store, { premium });
hub.compete = compete;
api.compete = compete;
setInterval(() => compete.settle().catch((e) => console.error('settle:', e)), 60000);
compete.settle().catch((e) => console.error('settle:', e));

// Route handlers Next.js собираются отдельным бандлом, поэтому общее состояние передаём через globalThis.
globalThis.__pushduel = { api, hub, store, config };

const app = next({ dev, hostname: '0.0.0.0', port });
const handle = app.getRequestHandler();
await app.prepare();
const nextUpgrade = app.getUpgradeHandler();

const server = http.createServer((req, res) => handle(req, res));
const wss = new WebSocketServer({ noServer: true, maxPayload: 16 * 1024 });
wss.on('connection', (ws) => hub.attach(ws));

server.on('upgrade', (req, socket, head) => {
  if (req.url && req.url.split('?')[0] === '/ws') {
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
  } else {
    nextUpgrade(req, socket, head); // HMR в режиме разработки
  }
});

// Пинги: мёртвые соединения (телефон уснул) закрываем, чтобы не держать соперника.
setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 15000);
wss.on('connection', (ws) => { ws.isAlive = true; ws.on('pong', () => { ws.isAlive = true; }); });

server.listen(port, () => {
  console.log(`PushDuel: http://localhost:${port} (${dev ? 'dev' : 'production'}), данные: ${dataFile}`);
  if (config.allowGuests) console.log('Гостевой вход включён (ALLOW_GUESTS)');
});

const shutdown = () => {
  store.flush();
  hub.close();
  if (bot) bot.stop();
  process.exit(0);
};
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
