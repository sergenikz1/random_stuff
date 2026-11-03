// Иллюстрации арен: SVG-строки в стиле «Aero × Греция» (глянцевые колонны, море, Везувий).
// Это заглушки до сгенерированных картинок: когда появятся файлы arenas/<n>-<id>-<light|dark>.webp,
// компонент арены просто покажет <img> вместо этой SVG.

const PAL = {
  light: { sky: ['#5cb4ff', '#d8f2ff'], far: '#7c8fb0', ground: '#f6ead2', stone: '#1f5fd0', fill: '#ffffff', smoke: 'rgba(90,100,120,0.35)', lava: '#ff6a1a', sun: '#ffffff', sea: ['#3aa0ef', '#9fe6c8'], colA: '#e8f0ff', colB: '#c9d8f2', volc: '#5d6b85', leaf: '#6f9a5a' },
  dark: { sky: ['#1a0a05', '#6a2a10'], far: '#3a170a', ground: '#2a160c', stone: '#ffb15c', fill: '#2a160c', smoke: 'rgba(255,190,140,0.22)', lava: '#ff6a1a', sun: '#ffcf7a', sea: ['#5a240d', '#2a1209'], colA: '#3a1d0e', colB: '#1a0d06', volc: '#1a0a05', leaf: '#7a5a2a' },
};

function columns(a, x, y, n, gap, h, w = 10) {
  let s = '';
  for (let i = 0; i < n; i++) {
    const cx = x + i * gap;
    s += `<rect x='${cx}' y='${y}' width='${w}' height='${h}' rx='2' fill='url(#col)' stroke='${a.stone}' stroke-width='1.4'/>`;
    s += `<rect x='${cx + 2}' y='${y + 3}' width='2.4' height='${h - 6}' rx='1' fill='rgba(255,255,255,0.7)'/>`;
    s += `<rect x='${cx - 2.5}' y='${y - 5}' width='${w + 5}' height='5' rx='1.5' fill='${a.stone}'/><rect x='${cx - 2.5}' y='${y + h}' width='${w + 5}' height='4' rx='1.5' fill='${a.stone}'/>`;
  }
  return s;
}
const pediment = (a, x, y, w, h) => `<path d='M${x} ${y} L${x + w / 2} ${y - h} L${x + w} ${y} Z' fill='url(#col)' stroke='${a.stone}' stroke-width='1.8' stroke-linejoin='round'/><rect x='${x - 3}' y='${y}' width='${w + 6}' height='7' rx='2' fill='${a.stone}'/>`;
const steps = (a, x, y, w, n = 2) => Array.from({ length: n }, (_, i) => `<rect x='${x - i * 6}' y='${y + i * 6}' width='${w + i * 12}' height='6' rx='2' fill='url(#col)' stroke='${a.stone}' stroke-width='1.2'/>`).join('');
const olive = (a, x, y, s = 1) => `<g transform='translate(${x} ${y}) scale(${s})'><path d='M0 0 C2 -14 -2 -22 2 -34' stroke='${a.stone}' stroke-width='3' fill='none'/><g fill='${a.leaf}'><ellipse cx='-8' cy='-36' rx='12' ry='7'/><ellipse cx='10' cy='-40' rx='12' ry='8'/><ellipse cx='0' cy='-48' rx='11' ry='7'/></g></g>`;
const flame = (a, x, y) => `<g transform='translate(${x} ${y})'><rect x='-3' y='0' width='6' height='26' rx='2' fill='${a.stone}'/><path d='M-9 0 h18 l-4 -6 h-10 z' fill='${a.stone}'/><path d='M0 -26 C8 -16 6 -10 0 -6 C-6 -10 -8 -16 0 -26 Z' fill='${a.lava}'/><path d='M0 -18 C3 -13 3 -10 0 -8 C-3 -10 -3 -13 0 -18 Z' fill='#ffd06a'/></g>`;

function scene(n, a) {
  switch (n) {
    case 1: return `${olive(a, 70, 168, 1.1)}${columns(a, 150, 118, 2, 32, 46)}<rect x='206' y='150' width='12' height='16' rx='2' fill='url(#col)' stroke='${a.stone}' stroke-width='1.4'/>${olive(a, 300, 170, 0.8)}`;
    case 2: return `${steps(a, 70, 160, 220, 1)}${columns(a, 80, 108, 8, 27, 50)}<rect x='70' y='97' width='222' height='10' rx='2' fill='${a.stone}'/>${olive(a, 330, 170, 0.7)}`;
    case 3: return `${pediment(a, 90, 92, 180, 36)}${columns(a, 100, 102, 6, 31, 54)}${steps(a, 90, 160, 180, 2)}${olive(a, 40, 170, 0.9)}`;
    case 4: return `<ellipse cx='180' cy='184' rx='160' ry='20' fill='none' stroke='${a.stone}' stroke-width='2'/><ellipse cx='180' cy='184' rx='120' ry='12' fill='none' stroke='${a.stone}' stroke-width='1.2' stroke-dasharray='6 5'/>
      ${Array.from({ length: 4 }, (_, i) => `<rect x='${12 + i * 8}' y='${142 - i * 9}' width='${336 - i * 16}' height='6' rx='3' fill='url(#col)' stroke='${a.stone}' stroke-width='1'/>`).join('')}
      <rect x='174' y='150' width='12' height='32' rx='2' fill='${a.stone}'/>${flame(a, 40, 150)}${flame(a, 320, 150)}`;
    case 5: return `${Array.from({ length: 6 }, (_, i) => `<path d='M${40 + i * 12} ${168 - i * 10} Q180 ${120 - i * 14} ${320 - i * 12} ${168 - i * 10}' stroke='${a.stone}' stroke-width='4' fill='none' opacity='${1 - i * 0.12}'/>`).join('')}
      <rect x='130' y='160' width='100' height='8' rx='2' fill='${a.stone}'/>${columns(a, 140, 140, 4, 24, 20, 8)}`;
    case 6: return `${pediment(a, 70, 88, 220, 42)}${columns(a, 80, 98, 8, 28, 58)}${steps(a, 70, 160, 220, 2)}${flame(a, 30, 152)}${flame(a, 330, 152)}<circle cx='180' cy='70' r='9' fill='none' stroke='${a.stone}' stroke-width='2'/>`;
    default: return `<path d='M20 168 L110 60 L150 100 L200 28 L260 95 L300 70 L350 168 Z' fill='url(#volc)' stroke='${a.stone}' stroke-width='1.5'/>
      <path d='M190 40 L200 28 L212 44' stroke='#ffffff' stroke-width='5' fill='none' opacity='.85'/>
      <g transform='translate(172 18) scale(0.33)'>${pediment(a, 0, 22, 90, 22)}${columns(a, 6, 28, 4, 24, 30)}</g>
      ${Array.from({ length: 4 }, (_, i) => `<ellipse cx='${60 + i * 80}' cy='${120 + (i % 2) * 18}' rx='34' ry='9' fill='rgba(255,255,255,0.75)'/>`).join('')}`;
  }
}

// n — номер арены 1..7, dark — тёмная тема.
export function arenaSvg(n, dark) {
  const a = dark ? PAL.dark : PAL.light;
  const vx = n === 7 ? 70 : 282;
  const volcano = `<g transform='translate(${vx} 150) scale(1.15)'>
    ${Array.from({ length: 3 + Math.min(4, n) }, (_, i) => `<circle cx='${(i % 2 ? 8 : -6) + i * 3}' cy='${-58 - i * 13}' r='${8 + i * 3}' fill='${a.smoke}'/>`).join('')}
    <ellipse cx='0' cy='-50' rx='24' ry='10' fill='${a.lava}' opacity='.4'/>
    <path d='M-80 2 L-20 -50 Q0 -56 20 -50 L80 2 Z' fill='url(#volc)' stroke='${a.stone}' stroke-width='1.4'/>
    <path d='M-15 -51 Q0 -43 15 -51' stroke='${a.lava}' stroke-width='3' fill='none'/>
    <path d='M2 -46 C-4 -34 6 -26 0 -14' stroke='${a.lava}' stroke-width='2.4' fill='none'/></g>`;
  const sky = dark
    ? Array.from({ length: 22 }, (_, i) => `<circle cx='${(i * 53) % 360}' cy='${-100 + ((i * 29) % 170)}' r='1.2' fill='#ffd2a0' opacity='.8'/>`).join('')
    : `<circle cx='60' cy='-30' r='20' fill='${a.sun}' opacity='.95'/><circle cx='60' cy='-30' r='34' fill='${a.sun}' opacity='.25'/>`;
  const bubbles = Array.from({ length: 6 }, (_, i) => `<circle cx='${30 + i * 58}' cy='${30 + ((i * 37) % 90)}' r='${4 + (i % 3) * 3}' fill='url(#bub)'/>`).join('');
  return `<svg viewBox='0 0 360 330' width='100%' height='100%' preserveAspectRatio='xMidYMax slice' xmlns='http://www.w3.org/2000/svg'>
    <defs>
      <linearGradient id='sk' x1='0' y1='0' x2='0' y2='1'><stop offset='0' stop-color='${a.sky[0]}'/><stop offset='1' stop-color='${a.sky[1]}'/></linearGradient>
      <linearGradient id='col' x1='0' y1='0' x2='1' y2='0'><stop offset='0' stop-color='${a.colA}'/><stop offset='0.45' stop-color='${a.fill}'/><stop offset='1' stop-color='${a.colB}'/></linearGradient>
      <linearGradient id='volc' x1='0' y1='0' x2='0' y2='1'><stop offset='0' stop-color='${a.far}'/><stop offset='1' stop-color='${a.volc}'/></linearGradient>
      <radialGradient id='bub' cx='0.35' cy='0.3' r='0.7'><stop offset='0' stop-color='#fff' stop-opacity='.95'/><stop offset='0.3' stop-color='#fff' stop-opacity='0'/><stop offset='0.85' stop-color='#fff' stop-opacity='.15'/><stop offset='1' stop-color='#fff' stop-opacity='.7'/></radialGradient>
      <linearGradient id='sea' x1='0' y1='0' x2='0' y2='1'><stop offset='0' stop-color='${a.sea[0]}'/><stop offset='1' stop-color='${a.sea[1]}'/></linearGradient>
    </defs>
    <rect width='360' height='330' fill='url(#sk)'/>
    <g transform='translate(0 120)'>
      ${sky}${volcano}
      <path d='M0 150 Q60 130 120 146 T240 140 T360 148 V210 H0 Z' fill='url(#sea)' opacity='.85'/>
      <path d='M0 158 Q30 150 60 158 T120 158 T180 158 T240 158 T300 158 T360 158' stroke='rgba(255,255,255,0.6)' stroke-width='2' fill='none'/>
      <rect x='0' y='168' width='360' height='52' fill='${a.ground}'/>
      ${scene(n, a)}
    </g>
    ${bubbles}
  </svg>`;
}
