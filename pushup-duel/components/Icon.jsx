// Плоские иконки в древнегреческом стиле: фигура залита «бумагой» (--ip), контур и детали — «краской» (--ii).
// Светлая тема: белое с синим. Тёмная: терракота с чёрным, как на чернофигурной вазе.
// Никаких градиентов и теней: только заливка, контур, меандр и лавр. Снаружи — кайма цвета «бумаги»,
// как у наклейки: так иконка читается и на светлом, и на чёрном фоне.

// Ряд меандра: n звеньев шириной u, нижняя линия на высоте y.
function meander(x, y, n, u = 5) {
  let d = `M${x} ${y + 1.6} H${x + n * u}`;
  for (let i = 0; i < n; i++) {
    const a = x + i * u;
    d += ` M${a} ${y} V${y - u * 0.9} H${a + u * 0.8} V${y - u * 0.3} H${a + u * 0.38} V${y - u * 0.55}`;
  }
  return <path className="s thin" d={d} />;
}

// Веточка лавра: стебель от (x, y) под углом ang (градусы, 0 — вверх), листья попарно.
function sprig(x, y, len = 14, ang = 0, n = 4, mirror = false) {
  const r = (ang * Math.PI) / 180;
  const dx = Math.sin(r), dy = -Math.cos(r);
  const leaves = [];
  for (let i = 1; i <= n; i++) {
    const t = (i / (n + 0.6)) * len;
    const px = x + dx * t, py = y + dy * t;
    for (const side of mirror ? [1] : [-1, 1]) {
      const la = ang + side * 48;
      leaves.push(<ellipse key={`${i}${side}`} className="k" cx={px + Math.sin((la * Math.PI) / 180) * 2.6} cy={py - Math.cos((la * Math.PI) / 180) * 2.6}
        rx="1.4" ry="3" transform={`rotate(${la} ${px + Math.sin((la * Math.PI) / 180) * 2.6} ${py - Math.cos((la * Math.PI) / 180) * 2.6})`} />);
    }
  }
  return <g><path className="s" d={`M${x} ${y} L${x + dx * len} ${y + dy * len}`} />{leaves}<ellipse className="k" cx={x + dx * len} cy={y + dy * len} rx="1.4" ry="3" transform={`rotate(${ang} ${x + dx * len} ${y + dy * len})`} /></g>;
}

// Венок из листьев по дуге окружности.
function wreath(cx, cy, R, from = 200, to = 340, step = 20) {
  const out = [];
  for (const side of [-1, 1]) {
    for (let a = from; a <= to; a += step) {
      const ang = side === 1 ? a : 540 - a;
      const rad = (ang * Math.PI) / 180;
      const x = cx + Math.cos(rad) * R, y = cy - Math.sin(rad) * R;
      out.push(<ellipse key={`${side}${a}`} className="k" cx={x} cy={y} rx="1.6" ry="3.4" transform={`rotate(${90 - ang + side * 30} ${x} ${y})`} />);
    }
  }
  return <g>{out}</g>;
}

function gearPath(cx, cy, r1, r2, teeth) {
  let d = '';
  for (let i = 0; i < teeth; i++) {
    const a = (i / teeth) * Math.PI * 2, w = Math.PI / teeth / 1.6;
    const pts = [[r1, a - w * 1.4], [r2, a - w], [r2, a + w], [r1, a + w * 1.4]];
    for (const [rr, aa] of pts) d += `${d ? 'L' : 'M'}${(cx + Math.cos(aa) * rr).toFixed(1)} ${(cy + Math.sin(aa) * rr).toFixed(1)} `;
  }
  return `${d}Z`;
}

const ICONS = {
  temple: () => (<>
    <path className="p" d="M5 17 L24 6 L43 17 Z" />{sprig(17, 15, 6, -70, 2)}{sprig(31, 15, 6, 70, 2)}
    <rect className="p" x="5" y="17" width="38" height="4" />
    {[9, 21, 33].map((x) => <g key={x}><rect className="p" x={x} y="21" width="6" height="15" /><path className="s thin" d={`M${x + 3} 23 V34`} /></g>)}
    <rect className="p" x="4" y="36" width="40" height="7" />{meander(7, 41.5, 7, 4.8)}
  </>),
  coin: () => (<>
    <circle className="p" cx="24" cy="24" r="19" /><circle className="s thin" cx="24" cy="24" r="14.5" />
    {wreath(24, 24, 10.5, 200, 320, 24)}
    <path className="k" d="M24 15 L30 29 H18 Z" /><path className="p0" d="M24 20 L27 27 H21 Z" />
  </>),
  gem: () => (<>
    <path className="p" d="M9 18 L16 9 H32 L39 18 L24 41 Z" />
    <path className="s" d="M9 18 H39 M16 9 L20 18 L24 41 M32 9 L28 18 L24 41 M20 18 L24 9 L28 18" />
  </>),
  gear: () => (<>
    <path className="p" d={gearPath(24, 24, 15, 20, 10)} /><circle className="p" cx="24" cy="24" r="10.5" />
    {wreath(24, 24, 6.5, 210, 330, 30)}
  </>),
  mail: () => (<>
    <rect className="p" x="4" y="10" width="40" height="29" rx="4" /><path className="s" d="M5 13 L24 27 L43 13" />
    {sprig(20, 19, 5, -60, 2)}{sprig(28, 19, 5, 60, 2)}
    {meander(9, 35, 6, 5)}
  </>),
  search: () => (<>
    <path className="k" d="M28.5 31.5 L32 28 L43 39 Q44 41 42.5 42.5 Q41 44 39 43 Z" />
    <circle className="p" cx="20" cy="20" r="14" /><circle className="s thin" cx="20" cy="20" r="10" />{sprig(16, 26, 12, 40, 3)}
  </>),
  bell: () => (<>
    <circle className="p" cx="24" cy="6.5" r="3" />
    <path className="p" d="M24 9 C14 9 13 18 12 27 C11 31 8 33 6 36 H42 C40 33 37 31 36 27 C35 18 34 9 24 9 Z" />
    {sprig(20, 26, 8, -40, 2)}{sprig(28, 26, 8, 40, 2)}
    <rect className="p" x="6" y="35" width="36" height="5" />{meander(9, 39, 6, 5)}
    <circle className="p" cx="24" cy="43" r="3" />
  </>),
  calendar: () => (<>
    <rect className="p" x="5" y="9" width="38" height="33" rx="4" />
    <path className="s" d="M5 18 H43" />{meander(9, 16.5, 6, 5)}
    <rect className="p" x="13" y="5" width="4" height="8" rx="2" /><rect className="p" x="31" y="5" width="4" height="8" rx="2" />
    {[0, 1, 2, 3, 4].flatMap((c) => [0, 1, 2].map((r) => (c === 4 && r === 2 ? null : <rect key={`${c}${r}`} className="k" x={9 + c * 6.4} y={22 + r * 6.2} width="4.6" height="4.4" rx="0.8" />)))}
  </>),
  map: () => (<>
    <path className="p" d="M8 10 C14 7 20 12 26 9 C32 6 38 10 42 8 V36 C36 39 30 35 24 38 C18 41 12 37 8 39 Z" />
    <path className="s" d="M13 30 L20 20 L25 26 L30 19 L38 29" />
    <path className="k" d="M30 12 L34 12 L34 17 L30 17 Z" /><path className="k" d="M29 12 L32 10 L35 12 Z" />
    <path className="s thin dash" d="M12 35 C18 33 22 30 26 31" />
    <path className="k" d="M22 25 C22 21 28 21 28 25 C28 28 25 30 25 31 C25 30 22 28 22 25 Z" />
  </>),
  lyre: () => (<>
    <path className="p" d="M10 9 C4 14 7 25 15 29 V34 H33 V29 C41 25 44 14 38 9 C35 7 33 10 35 12 C39 16 36 24 31 25 V27 H17 V25 C12 24 9 16 13 12 C15 10 13 7 10 9 Z" />
    <rect className="p" x="14" y="12" width="20" height="3" />
    <path className="s thin" d="M19 15 V27 M22 15 V27 M26 15 V27 M29 15 V27" />
    <rect className="p" x="12" y="34" width="24" height="7" />{meander(15, 39.5, 4, 4.6)}
  </>),
  banner: () => (<>
    <path className="p" d="M12 5 H36 V43 L24 34 L12 43 Z" /><path className="s" d="M12 12 H36" />{meander(14, 10.5, 4, 5)}
    {sprig(20, 30, 12, 35, 3)}
  </>),
  chat: () => (<>
    <path className="p" d="M22 14 C33 14 44 18 44 27 C44 32 40 35 36 37 L39 43 L31 38 C22 39 16 35 16 29" />
    <path className="p" d="M23 6 C12 6 4 11 4 18 C4 23 8 26 11 27 L9 33 L17 28 C27 29 33 24 33 18 C33 11 30 6 23 6 Z" />
    <circle className="k" cx="12" cy="18" r="2" /><circle className="k" cx="18.5" cy="18" r="2" /><circle className="k" cx="25" cy="18" r="2" />
  </>),
  quests: () => (<>
    <path className="p" d="M9 7 H37 C40 7 41 10 39 12 V40 C39 43 37 44 35 44 H11 C8 44 7 41 9 39 Z" />
    <path className="s" d="M9 7 C6 7 5 11 9 12 H39" />
    {[18, 25, 32].map((y) => <g key={y}><path className="s" d={`M13 ${y} L15.5 ${y + 2.5} L20 ${y - 2.5}`} /><path className="s" d={`M23 ${y} H34`} /></g>)}
  </>),
  scroll: () => (<>
    <path className="p" d="M10 8 H38 V40 H10 Z" /><rect className="p" x="6" y="5" width="36" height="6" rx="3" /><rect className="p" x="6" y="37" width="36" height="6" rx="3" />
    {meander(13, 18, 4, 5.5)}<path className="s" d="M14 24 H34 M14 29 H34 M14 34 H28" />
  </>),
  feed: () => (<>
    <path className="p" d="M6 10 H32 V40 H6 Z" /><rect className="p" x="4" y="7" width="30" height="5" rx="2.5" />
    {meander(9, 19, 4, 5)}<path className="s" d="M10 25 H26 M10 30 H26 M10 35 H20" />
    <path className="p" d="M44 6 C34 10 28 20 26 32 C32 26 40 22 44 6 Z" /><path className="s thin" d="M26 32 L39 13" />
  </>),
  people: () => (<>
    {[[12, 20, 0.8], [36, 20, 0.8], [24, 16, 1]].map(([x, y, s], i) => (
      <g key={i} transform={`translate(${x} ${y}) scale(${s})`}>
        <path className="p" d="M-11 22 C-11 13 -6 10 0 10 C6 10 11 13 11 22 Z" />
        <circle className="p" cx="0" cy="0" r="7.5" />{sprig(-6, 2, 8, 70, 2, true)}
      </g>
    ))}
    <rect className="p" x="4" y="38" width="40" height="6" />{meander(7, 42.5, 7, 4.8)}
  </>),
  share: () => (<>
    <path className="s thick" d="M14 24 L34 12 M14 24 L34 36" />
    {[[12, 24], [36, 11], [36, 37]].map(([x, y]) => <g key={x + y}><circle className="p" cx={x} cy={y} r="8" />{sprig(x - 2, y + 4, 7, 35, 2)}</g>)}
  </>),
  amphora: () => (<>
    <path className="p" d="M18 4 H30 V9 C39 13 41 22 41 28 C41 37 34 43 27 45 H21 C14 43 7 37 7 28 C7 22 9 13 18 9 Z" />
    <path className="s" d="M18 11 C11 11 10 19 15 21 M30 11 C37 11 38 19 33 21" />
    <path className="s" d="M9 24 H39" />{meander(11, 33, 6, 4.4)}<path className="s" d="M9 35 H39" />
  </>),
  swords: () => (<>
    {[1, -1].map((s) => (
      <g key={s} transform={`translate(24 24) scale(${s} 1) rotate(-45)`}>
        <path className="p" d="M-3 -21 L0 -25 L3 -21 L3 6 H-3 Z" /><path className="s thin" d="M0 -20 V4" />
        <rect className="p" x="-8" y="6" width="16" height="3.4" rx="1.5" /><rect className="p" x="-2.2" y="9.4" width="4.4" height="9" /><circle className="p" cx="0" cy="20.5" r="2.6" />
      </g>
    ))}
  </>),
  shield: () => (<>
    <circle className="p" cx="24" cy="24" r="20" /><circle className="s" cx="24" cy="24" r="15" />
    <path className="k" d="M24 12 L33 35 H28.5 L24 22 L19.5 35 H15 Z" />
  </>),
  laurel: () => (<>
    {wreath(24, 22, 15, 190, 350, 16)}<path className="s" d="M11 35 C6 26 8 14 16 8 M37 35 C42 26 40 14 32 8" />
    <path className="p" d="M18 36 C21 38 27 38 30 36 L28 42 H20 Z" />
  </>),
  trophy: () => (<>
    <path className="p" d="M12 8 H36 C36 20 32 27 24 28 C16 27 12 20 12 8 Z" />
    <path className="s" d="M12 11 C5 11 5 20 13 21 M36 11 C43 11 43 20 35 21" />
    {meander(15, 15, 4, 4.6)}
    <rect className="p" x="21" y="28" width="6" height="7" /><rect className="p" x="14" y="35" width="20" height="7" />
  </>),
  medal: () => (<>
    <path className="p" d="M15 4 H22 L26 18 H19 Z" /><path className="p" d="M33 4 H26 L22 18 H29 Z" />
    <circle className="p" cx="24" cy="30" r="14" />{wreath(24, 30, 9.5, 200, 330, 26)}
    <path className="k" d="M24 23 L26 28 H31 L27 31 L28.5 36 L24 33 L19.5 36 L21 31 L17 28 H22 Z" />
  </>),
  flame: () => (<>
    <path className="p" d="M24 4 C30 12 37 17 37 28 C37 37 31 43 24 43 C17 43 11 37 11 28 C11 22 14 18 17 15 C17 20 19 23 22 24 C20 16 22 9 24 4 Z" />
    <path className="k" d="M24 22 C28 27 31 30 31 35 C31 39 28 41 24 41 C20 41 17 39 17 35 C17 31 20 29 21 27 C22 30 23 31 24 31 C23 28 23 25 24 22 Z" />
  </>),
  volcano: () => (<>
    <circle className="p" cx="16" cy="9" r="4.5" /><circle className="p" cx="24" cy="6" r="4.5" /><circle className="p" cx="31" cy="10" r="4" />
    <path className="p" d="M3 43 L17 18 H31 L45 43 Z" />
    <path className="k" d="M17 18 H31 L29 21 C27 25 28 30 26 34 C25 30 24 26 22 23 C21 26 20 28 19 30 C19 25 18 21 17 18 Z" />
    {meander(7, 41, 7, 5)}
  </>),
  star: () => (<>
    <path className="p" d="M24 4 L29.5 17 L43 18 L32.5 27 L36 41 L24 33.5 L12 41 L15.5 27 L5 18 L18.5 17 Z" />
    <circle className="k" cx="24" cy="24" r="4" />
  </>),
  gift: () => (<>
    <rect className="p" x="6" y="18" width="36" height="25" /><rect className="p" x="4" y="12" width="40" height="7" />
    <rect className="k" x="21" y="12" width="6" height="31" />
    <path className="p" d="M24 12 C18 4 10 6 13 10 C15 12 20 12 24 12 Z" /><path className="p" d="M24 12 C30 4 38 6 35 10 C33 12 28 12 24 12 Z" />
    {meander(8, 39, 2, 5)}{meander(30, 39, 2, 5)}
  </>),
  lock: () => (<>
    <path className="s thick" d="M15 21 V15 C15 9 19 6 24 6 C29 6 33 9 33 15 V21" />
    <rect className="p" x="9" y="20" width="30" height="23" rx="3" />
    <circle className="k" cx="24" cy="29" r="3.4" /><rect className="k" x="22.4" y="30" width="3.2" height="7" />
  </>),
  mask: () => (<>
    <path className="p" d="M8 8 C16 12 32 12 40 8 C42 22 38 38 24 43 C10 38 6 22 8 8 Z" />
    <path className="k" d="M13 20 C15 17 20 17 21 20 C19 22 15 22 13 20 Z M27 20 C28 17 33 17 35 20 C33 22 29 22 27 20 Z" />
    <path className="s" d="M16 31 C20 36 28 36 32 31" />{sprig(11, 12, 6, 60, 2, true)}{sprig(37, 12, 6, -60, 2, true)}
  </>),
  bolt: () => (<>
    <path className="p" d="M27 3 L11 26 H21 L17 45 L37 19 H26 L31 3 Z" />
  </>),
  picture: () => (<>
    <rect className="p" x="4" y="7" width="40" height="34" /><rect className="s" x="10" y="13" width="28" height="22" />
    {meander(7, 11, 7, 4.8)}
    <path className="k" d="M11 34 L19 23 L24 29 L29 22 L37 34 Z" /><circle className="k" cx="31" cy="18" r="3" />
  </>),
  belt: () => (<>
    <rect className="p" x="3" y="18" width="42" height="12" rx="2" /><path className="s thin dash" d="M5 21 H43 M5 27 H43" />
    <circle className="p" cx="24" cy="24" r="12" /><circle className="s thin" cx="24" cy="24" r="8.5" />
    <path className="k" d="M24 17.5 L26 22 H30.5 L27 25 L28.4 29.5 L24 26.8 L19.6 29.5 L21 25 L17.5 22 H22 Z" />
  </>),
  helmet: () => (<>
    <path className="p" d="M24 4 C35 4 41 13 41 24 V42 H31 L30 30 H26 V42 H22 V30 H18 L17 42 H7 V24 C7 13 13 4 24 4 Z" />
    <path className="k" d="M14 20 H22 V26 H14 Z M26 20 H34 V26 H26 Z" />
    <path className="s" d="M24 4 C20 1 14 2 12 6" />
  </>),
};

// Эмодзи, которые в интерфейсе заменяются иконками.
export const EMOJI_ICON = {
  '🏛️': 'temple', '🏛': 'temple', '🪙': 'coin', '💎': 'gem', '⚙️': 'gear', '📬': 'mail', '📨': 'mail', '✉️': 'mail',
  '🔍': 'search', '🔎': 'search', '🔔': 'bell', '📯': 'bell', '📣': 'bell', '📅': 'calendar', '🗓️': 'calendar',
  '🗺️': 'map', '🌍': 'map', '🎵': 'lyre', '🎶': 'lyre', '🔖': 'banner', '🃏': 'banner', '💬': 'chat', '🎯': 'quests', '📋': 'quests',
  '👥': 'people', '🤼': 'people', '📜': 'scroll', '📒': 'feed', '📰': 'feed', '📤': 'share', '🔗': 'share',
  '🏺': 'amphora', '🎴': 'amphora', '🛍️': 'amphora', '⚔️': 'swords', '⚔': 'swords', '🛡️': 'shield', '🏆': 'trophy',
  '🏅': 'medal', '🎖️': 'medal', '🔥': 'flame', '🌋': 'volcano', '⭐': 'star', '🌟': 'star', '🎁': 'gift', '🔒': 'lock',
  '🎭': 'mask', '⚡': 'bolt', '🪞': 'picture', '🥊': 'belt', '👑': 'laurel', '🫒': 'laurel',
};

export function Icon({ name, size = 28, className = '' }) {
  const draw = ICONS[name];
  if (!draw) return null;
  return (
    <svg className={`ico ${className}`} width={size} height={size} viewBox="-2 -2 52 52" aria-hidden="true">
      <g className="halo">{draw()}</g>{draw()}
    </svg>
  );
}

// Эмодзи или иконка вместо него, если такая нарисована.
export function Glyph({ e, size = 26, className = '' }) {
  const name = EMOJI_ICON[e];
  if (name) return <Icon name={name} size={size} className={className} />;
  return <span className={`glyph-emoji ${className}`} style={{ fontSize: size * 0.72 }}>{e}</span>;
}

export const ICON_NAMES = Object.keys(ICONS);
