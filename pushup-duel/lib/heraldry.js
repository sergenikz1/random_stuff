// Конструкторы аватара и герба клана: из каких деталей они собираются и как их проверить.
// Общий модуль: им пользуются и сервер (проверка), и клиент (редактор и отрисовка).

// Цвета росписи. Одна палитра для обеих тем, чтобы аватар выглядел одинаково у всех.
export const PAINT = {
  blue:  { name: 'Синий',    hex: '#1f4fa8' },
  navy:  { name: 'Ночной',   hex: '#10213f' },
  sky:   { name: 'Небесный', hex: '#9cc0ee' },
  white: { name: 'Белый',    hex: '#ffffff' },
  sand:  { name: 'Песок',    hex: '#f3e6cf' },
  ochre: { name: 'Охра',     hex: '#d9a63a' },
  terra: { name: 'Терракота', hex: '#d4772f' },
  red:   { name: 'Красный',  hex: '#c23b22' },
  olive: { name: 'Олива',    hex: '#5f7d32' },
  black: { name: 'Чёрный',   hex: '#140b07' },
};
export const PAINT_IDS = Object.keys(PAINT);
export const paint = (id, fallback = 'blue') => (PAINT[id] || PAINT[fallback]).hex;

// ---- Аватар ----
export const AVATAR_SHAPES = { hex: 'Шестигранник', circle: 'Медальон', shield: 'Щит', square: 'Плита' };
export const AVATAR_RIMS = { plain: 'Простая', double: 'Двойная', meander: 'Меандр', dots: 'Жемчуг', laurel: 'Лавр', rope: 'Канат' };

// src: 'tg' — фото из Telegram (по умолчанию), 'fig' — фигура из набора.
export const DEFAULT_LOOK = { src: 'tg', shape: 'hex', rim: 'plain', rimColor: 'blue', bg: 'sand', figColor: 'blue' };

// Стартовый облик выводим из id игрока, чтобы новички не были одинаковыми.
export function lookFor(id = '') {
  let h = 0;
  for (const ch of String(id)) h = (h * 31 + ch.charCodeAt(0)) | 0;
  h = Math.abs(h);
  const pairs = [['blue', 'sand', 'blue'], ['red', 'black', 'ochre'], ['ochre', 'navy', 'ochre'], ['blue', 'white', 'navy'], ['olive', 'sand', 'olive'], ['terra', 'black', 'terra']];
  const [rimColor, bg, figColor] = pairs[h % pairs.length];
  return { src: 'tg', shape: Object.keys(AVATAR_SHAPES)[h % 4], rim: ['plain', 'double', 'meander', 'dots'][(h >> 3) % 4], rimColor, bg, figColor };
}

export function cleanLook(look, base = DEFAULT_LOOK) {
  const l = { ...base, ...(look && typeof look === 'object' ? look : {}) };
  return {
    src: l.src === 'fig' ? 'fig' : 'tg',
    shape: AVATAR_SHAPES[l.shape] ? l.shape : base.shape,
    rim: AVATAR_RIMS[l.rim] ? l.rim : base.rim,
    rimColor: PAINT[l.rimColor] ? l.rimColor : base.rimColor,
    bg: PAINT[l.bg] ? l.bg : base.bg,
    figColor: PAINT[l.figColor] ? l.figColor : base.figColor,
  };
}

// ---- Герб клана ----
export const CREST_SHAPES = { heater: 'Щит', round: 'Аспис', banner: 'Знамя', tablet: 'Табличка' };
export const CREST_DIVISIONS = { none: 'Без деления', pale: 'Рассечён', fess: 'Пересечён', bend: 'Скошен', chevron: 'Стропило', quarter: 'Четверти', cross: 'Крест' };
export const CREST_RIMS = { plain: 'Простая', double: 'Двойная', meander: 'Меандр' };
export const CREST_CHARGES = ['🛡️', '⚔️', '🔥', '⚡', '🐺', '🦅', '🐉', '💪', '🏆', '🌊', '❄️', '🚀', '👑', '💀', '🦁', '🌪️', '🦉', '🐂', '🐎', '🐍', '🌿', '☀️', '⭐', '🌙'];

export const DEFAULT_CREST = { shape: 'heater', field: 'blue', division: 'none', field2: 'white', charge: '🛡️', chargeColor: 'ochre', rim: 'plain', rimColor: 'ochre' };

export function cleanCrest(crest, base = DEFAULT_CREST) {
  const c = { ...base, ...(crest && typeof crest === 'object' ? crest : {}) };
  return {
    shape: CREST_SHAPES[c.shape] ? c.shape : base.shape,
    field: PAINT[c.field] ? c.field : base.field,
    division: CREST_DIVISIONS[c.division] ? c.division : base.division,
    field2: PAINT[c.field2] ? c.field2 : base.field2,
    charge: CREST_CHARGES.includes(c.charge) ? c.charge : base.charge,
    chargeColor: PAINT[c.chargeColor] ? c.chargeColor : base.chargeColor,
    rim: CREST_RIMS[c.rim] ? c.rim : base.rim,
    rimColor: PAINT[c.rimColor] ? c.rimColor : base.rimColor,
  };
}

// Герб по умолчанию для старых кланов: знак из их эмодзи.
export const crestOf = (clan) => cleanCrest(clan?.crest || { ...DEFAULT_CREST, charge: CREST_CHARGES.includes(clan?.emoji) ? clan.emoji : '🛡️' });
