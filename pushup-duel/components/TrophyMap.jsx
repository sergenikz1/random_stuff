'use client';
// Дорога трофеев — полноэкранная карта. Первая арена внизу, листаем вверх к Олимпу.
// Сзади сплошное полотно: картинки арен перетекают одна в другую. По центру — извилистая дорога
// с вехами, справа от неё награды. Внизу кнопка «Назад».
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { ARENAS, PACKS } from '../lib/economy.js';
import { cosmeticName } from '../lib/pass.js';
import { arenaSvg } from '../lib/client/arenaArt.js';
import { bindBackButton } from '../lib/client/tg.js';
import { Avatar } from './ui.jsx';
import { RewardIcon } from './Pass.jsx';
import { PackIcon } from './Stickers.jsx';
import { roman } from './Greek.jsx';
import { Glyph, Icon } from './Icon.jsx';

const W = 390;          // ширина карты в её собственных координатах (дальше масштабируем под экран)
const H = 920;         // высота участка одной арены
const TOP = 330;        // небо над Олимпом
const BOTTOM = 150;     // запас снизу под кнопку «Назад»
const GATE = 150;       // полоса ворот у основания участка
const ART_H = 480;      // высота картинки арены
const SCENE = 300;      // над воротами — сама арена, без наград
const START = 900;      // откуда начинается дорога на первой арене
const MAX_T = 2100;     // вершина Олимпа

const total = ARENAS.length * H + TOP + BOTTOM;
const bounds = ARENAS.map((a, i) => ({ ...a, max: ARENAS[i + 1]?.min ?? MAX_T }));
const baseOf = (i) => total - BOTTOM - i * H; // нижний край участка арены i

// Кубки → высота на карте: внутри участка от верха ворот до верха участка.
function yOf(t) {
  let i = bounds.findIndex((b) => t < b.max);
  if (i === -1) i = bounds.length - 1;
  const b = bounds[i];
  const lo = i === 0 ? START : b.min;
  const frac = Math.min(1, Math.max(0, (t - lo) / (b.max - lo)));
  return baseOf(i) - GATE - SCENE - frac * (H - GATE - SCENE - 60);
}
const xOf = (y) => W * 0.3 + Math.sin(y / 170) * W * 0.11;

// Каждая SVG арены получает свои id градиентов, иначе в одном документе они перепутаются.
const uniq = (svg, p) => svg.replace(/id='(\w+)'/g, `id='${p}$1'`).replace(/url\(#(\w+)\)/g, `url(#${p}$1)`);

// Облака: по три на участок, слева и справа по очереди.
const CLOUDS = bounds.flatMap((b, i) => [0, 1, 2].map((j) => {
  const y = baseOf(i) - GATE - SCENE - 30 - j * 150 - (i % 2) * 30;
  const left = (i + j) % 2 === 0;
  return { x: left ? -30 + ((i * 37) % 40) : W * 0.5 + ((i * 53) % 60), y, w: 120 + ((i * 7 + j * 31) % 70) };
}));

function rewardInfo(r) {
  if (r.coins) return { icon: 'coin', text: `${r.coins} монет` };
  if (r.gems) return { icon: 'gem', text: `${r.gems} кристаллов` };
  if (r.pack) return { pack: r.pack, text: PACKS[r.pack].name };
  if (r.item) return { item: r.item, text: cosmeticName(r.item) };
  return { icon: 'gift', text: 'Награда' };
}

export default function TrophyMap({ eco, profile, theme, onClose, onClaim }) {
  const ref = useRef(null);
  const [k, setK] = useState(1);
  const dark = theme === 'midnight';
  const trophies = eco?.trophies ?? profile?.rating ?? 1000;
  const best = eco?.bestRating ?? trophies;

  const road = useMemo(() => {
    const pts = [];
    for (let y = total - 30; y >= TOP - 60; y -= 10) pts.push(`${xOf(y).toFixed(1)},${y}`);
    return `M${pts.join(' L')}`;
  }, []);
  const arts = useMemo(() => bounds.map((b) => uniq(arenaSvg(b.id, dark), `tm${b.id}`)), [dark]);
  const meY = yOf(trophies);

  useEffect(() => bindBackButton(onClose), [onClose]);
  // Масштаб: карта нарисована в ширину 390, растягиваем под ширину экрана.
  // При первом показе прокручиваем туда, где стоит игрок.
  const meRef = useRef(meY);
  meRef.current = meY;
  useLayoutEffect(() => {
    const el = ref.current;
    const fit = () => setK(el.clientWidth / W);
    const k0 = el.clientWidth / W;
    setK(k0);
    el.scrollTop = Math.max(0, meRef.current * k0 - el.clientHeight * 0.55);
    window.addEventListener('resize', fit);
    return () => window.removeEventListener('resize', fit);
  }, []);

  return (
    <div className={`tmap ${dark ? 'dark' : ''}`} ref={ref}>
      <div className="tmap-canvas" style={{ height: total * k }}>
        <div className="tmap-inner" style={{ width: W, height: total, transform: `scale(${k})` }}>
          {/* Полотно: арены снизу вверх на общем небе; каждая стоит на своей земле, под ней фриз-меандр */}
          {bounds.map((b, i) => (
            <div key={b.id}>
              <div className="tmap-art" style={{ top: baseOf(i) - 90 - ART_H, height: ART_H }} dangerouslySetInnerHTML={{ __html: arts[i] }} />
              <div className="tmap-ground" style={{ top: baseOf(i) - 91, height: i === 0 ? BOTTOM + 91 : 91 }} />
              <div className="tmap-frieze" style={{ top: baseOf(i) - 9 }} />
            </div>
          ))}
          <div className="tmap-olymp" style={{ height: TOP + 260 }} />
          {/* Облака (в «Амфоре» — дым Везувия) заполняют небо между аренами */}
          {CLOUDS.map((c, j) => (
            <svg key={`c${j}`} className="tmap-cloud" viewBox="0 0 200 90" style={{ left: c.x, top: c.y, width: c.w, animationDelay: `${-j * 1.7}s` }}>
              <path d="M30 80 C5 80 0 52 22 46 C18 22 52 12 66 30 C74 6 116 2 126 28 C140 12 176 20 172 46 C198 48 198 80 172 80 Z" />
              <path className="shine" d="M40 44 C44 30 60 26 70 36 M100 28 C110 18 126 20 128 30" />
            </svg>
          ))}

          {/* Дорога; пройденная часть подсвечена */}
          <svg className="tmap-road" width={W} height={total} viewBox={`0 0 ${W} ${total}`}>
            <defs><clipPath id="tmap-done"><rect x="0" y={meY} width={W} height={total - meY} /></clipPath></defs>
            <path d={road} className="road-edge" />
            <path d={road} className="road-body" />
            <path d={road} className="road-done" clipPath="url(#tmap-done)" />
            <path d={road} className="road-dash" />
          </svg>

          {/* Ворота арен */}
          {bounds.map((b, i) => {
            const reached = best >= b.min;
            return (
              <div key={`g${b.id}`} className={`tmap-gate ${reached ? 'on' : ''}`} style={{ top: baseOf(i) - GATE + 14 }}>
                <div className="gate-plate">
                  <span className="tile"><Glyph e={b.emoji} size={50} /></span>
                  <div className="grow">
                    <div className="gate-num">Арена {roman(b.id)}</div>
                    <div className="greek-title">{b.name}</div>
                    <div className="small">{b.id === 1 ? 'старт' : `от ${b.min} 🏆`} · пояс чемпиона</div>
                  </div>
                  <span className="gate-lock">{reached ? '✓' : '🔒'}</span>
                </div>
              </div>
            );
          })}

          {/* Вехи: узел на дороге, награда справа */}
          {(eco?.road || []).map((r) => {
            const y = yOf(r.at);
            const x = xOf(y);
            const t = rewardInfo(r.reward);
            return (
              <div key={r.at}>
                <div className={`tmap-link ${r.status}`} style={{ top: y - 1.5, left: x + 14, width: W * 0.51 - x - 14 }} />
                <div className={`tmap-node ${r.status}`} style={{ left: x - 19, top: y - 19 }}>{r.status === 'claimed' ? '✓' : r.at}</div>
                <div className={`tmap-reward ${r.status}`} style={{ top: y - 32 }}>
                  {t.pack ? <PackIcon type={t.pack} size={36} /> : t.item ? <RewardIcon k={t.item} /> : <span className="rw-ico"><Icon name={t.icon} size={34} /></span>}
                  <div className="grow">
                    <div className="title">{t.text}</div><div className="sub">{r.at} 🏆</div>
                    {r.status === 'ready' && <button className="gel green" onClick={() => onClaim(r.at)}>Забрать</button>}
                  </div>
                  {r.status === 'claimed' && <span className="rw-st">✅</span>}
                  {r.status === 'locked' && <span className="rw-st">🔒</span>}
                </div>
              </div>
            );
          })}

          {/* Вершина */}
          <div className={`tmap-summit ${best >= MAX_T ? 'on' : ''}`} style={{ left: xOf(TOP - 50) - 60, top: TOP - 150 }}>
            <Icon name="temple" size={44} />
            <span className="greek-title">Вершина Олимпа</span>
            <span className="small">{MAX_T} 🏆 · статуя в Пантеоне</span>
          </div>

          {/* Игрок на дороге */}
          <div className="tmap-me" style={{ left: xOf(meY) - 28, top: meY - 64 }}>
            <Avatar emoji={profile?.avatar} frame={profile?.equipped?.frame} size="md" />
            <span className="me-tag">Вы · {trophies} 🏆</span>
          </div>
        </div>
      </div>
      <div className="tmap-head"><span className="greek-title">Дорога славы</span><span className="small">лучший результат {best} 🏆</span></div>
      <button className="gel tmap-back" onClick={onClose}>‹ Назад</button>
    </div>
  );
}
