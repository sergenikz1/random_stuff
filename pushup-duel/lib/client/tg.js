// Обёртка над Telegram.WebApp: вход, вибрация, кнопка «Назад», ссылки и цвета.
// Вне Telegram (в обычном браузере) всё тихо деградирует до гостевого режима.

export const tg = () => (typeof window !== 'undefined' ? window.Telegram?.WebApp : null);
export const inTelegram = () => !!tg()?.initData;

export function initTelegram(theme) {
  const t = tg();
  if (!t) return;
  try {
    t.ready();
    t.expand();
    t.disableVerticalSwipes?.(); // свайп вниз во время отжиманий не должен закрывать приложение
    applyTelegramColors(theme);
  } catch { /* старые клиенты Telegram */ }
}

export function applyTelegramColors(theme) {
  const t = tg();
  if (!t) return;
  const color = theme === 'midnight' ? '#0d0806' : '#f3f6fb';
  try {
    t.setHeaderColor?.(color);
    t.setBackgroundColor?.(color);
    t.setBottomBarColor?.(theme === 'midnight' ? '#0f0a07' : '#ffffff');
  } catch { /* версия API ниже 6.1 */ }
}

export function telegramScheme() {
  return tg()?.colorScheme || null;
}

// Гость получает случайный токен, сохранённый в браузере.
function guestToken() {
  let tok = null;
  try { tok = localStorage.getItem('pd.guest'); } catch { /* приватный режим */ }
  if (!tok) {
    tok = Array.from(crypto.getRandomValues(new Uint8Array(18)), (b) => b.toString(16).padStart(2, '0')).join('');
    try { localStorage.setItem('pd.guest', tok); } catch { /* без сохранения */ }
  }
  return tok;
}

export function authString() {
  const t = tg();
  return t?.initData ? `tma ${t.initData}` : `guest ${guestToken()}`;
}

// Параметр запуска: из ссылки t.me/bot/app?startapp=… или кнопки бота ?sp=….
export function startParam() {
  const fromTg = tg()?.initDataUnsafe?.start_param;
  if (fromTg) return fromTg;
  if (typeof location === 'undefined') return '';
  return new URLSearchParams(location.search).get('sp') || '';
}

export function haptic(kind = 'light') {
  const t = tg();
  try {
    if (t?.HapticFeedback && t.initData) {
      if (['success', 'error', 'warning'].includes(kind)) t.HapticFeedback.notificationOccurred(kind);
      else if (kind === 'select') t.HapticFeedback.selectionChanged();
      else t.HapticFeedback.impactOccurred(kind);
      return;
    }
    navigator.vibrate?.(kind === 'heavy' || kind === 'error' ? 40 : 15);
  } catch { /* нет вибрации */ }
}

export function setClosingConfirmation(on) {
  const t = tg();
  try { if (on) t?.enableClosingConfirmation?.(); else t?.disableClosingConfirmation?.(); } catch { /* */ }
}

// Кнопка «Назад» в шапке Telegram. Обработчики складываются в стек: срабатывает верхний
// (открытая шторка закрывается раньше, чем уходит вкладка). Возвращает функцию отписки.
const backStack = [];
let backInstalled = false;
const onBack = () => { const h = backStack[backStack.length - 1]; if (h) h(); };

export function bindBackButton(handler) {
  const t = tg();
  if (!t?.BackButton || !t.initData) return () => {};
  if (!backInstalled) { t.BackButton.onClick(onBack); backInstalled = true; }
  backStack.push(handler);
  t.BackButton.show();
  return () => {
    const i = backStack.lastIndexOf(handler);
    if (i >= 0) backStack.splice(i, 1);
    if (!backStack.length) t.BackButton.hide();
  };
}

// Ссылка, открывающая приложение с параметром (ref_…, duel_…, clan_…).
export function appLink(config, sp) {
  if (config?.botUsername) {
    return config.appShortName
      ? `https://t.me/${config.botUsername}/${config.appShortName}?startapp=${sp}`
      : `https://t.me/${config.botUsername}?start=${sp}`;
  }
  const u = new URL(location.href);
  u.search = `?sp=${sp}`;
  return u.toString();
}

// Оплата звёздами: открывает счёт Telegram и ждёт статуса (paid · cancelled · failed · pending).
export function openInvoice(link) {
  const t = tg();
  return new Promise((resolve) => {
    if (!t?.openInvoice) { window.open(link, '_blank'); resolve('pending'); return; }
    t.openInvoice(link, (status) => resolve(status));
  });
}

export async function share(url, text) {
  const t = tg();
  const link = `https://t.me/share/url?url=${encodeURIComponent(url)}&text=${encodeURIComponent(text)}`;
  if (t?.initData && t.openTelegramLink) { t.openTelegramLink(link); return 'telegram'; }
  if (navigator.share) {
    try { await navigator.share({ url, text }); return 'shared'; } catch { /* отмена */ }
  }
  try { await navigator.clipboard.writeText(`${text} ${url}`); return 'copied'; } catch { return 'failed'; }
}
