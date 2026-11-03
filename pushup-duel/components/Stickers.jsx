'use client';
import { Glyph, rich } from './Icon.jsx';
// Наклейки: отрисовка с голограммой, наборы, открытие, редактор карточки, альбом.
import { useEffect, useRef, useState } from 'react';
import { STICKERS, STICKER_RARITY, STICKER_RARITY_ORDER, PACKS, CARD_STICKERS_MAX, stickerById } from '../lib/stickers.js';
import { sfx } from '../lib/client/sound.js';
import { haptic, bindBackButton } from '../lib/client/tg.js';
import { askTiltPermission } from '../lib/client/tilt.js';
import { Card, Sheet } from './ui.jsx';
import { PlayerCard } from './PlayerCard.jsx';

// Наклейка с белой «вырубкой». У эпических и легендарных — голограмма от наклона телефона.
export function Sticker({ id, size = 56, className = '', style }) {
  const s = stickerById(id);
  if (!s) return null;
  return (
    <span className={`stk r-${s.rarity} ${s.text ? 'txt' : ''} ${className}`} style={{ '--sz': `${size}px`, '--bg': s.bg || '#fff', ...style }} title={s.name}>
      <span className="stk-face">{s.art}</span>
      {(s.rarity === 'epic' || s.rarity === 'legend') && <span className="stk-holo" />}
      {s.rarity === 'legend' && <span className="stk-spark" />}
    </span>
  );
}

// Набор наклеек — амфора в плоском стиле: заливка по редкости, контур цветом текста, меандр на пояске.
const AMPHORA = { basic: ['#d98a3a', '★'], rare: ['#2a7fd0', '★★'], epic: ['#7a3ad8', '★★★'], legend: ['#d9a521', '♛'] };
export function PackIcon({ type = 'basic', size = 52, className = '' }) {
  const [fill, mark] = AMPHORA[type] || AMPHORA.basic;
  return (
    <span className={`pack-ico amphora p-${type} ${className}`} style={{ '--sz': `${size}px` }}>
      <svg viewBox="-2 -2 44 52" width={size * 0.8} height={size} aria-hidden="true">
        <g stroke="var(--ink)" strokeWidth="2" strokeLinejoin="round">
          <path d="M15 3 h10 v5 c8 3 11 11 11 19 c0 9 -6 16 -13 19 h-6 c-7 -3 -13 -10 -13 -19 c0 -8 3 -16 11 -19 z" fill={fill} />
          <path d="M15 9 c-6 0 -7 7 -2 9 M25 9 c6 0 7 7 2 9" fill="none" />
          <path d="M6 22 h28 M5.5 30 h29" fill="none" />
          <path d="M8 28 v-4 h3 v3 h-1.5 M13 28 v-4 h3 v3 h-1.5 M18 28 v-4 h3 v3 h-1.5 M23 28 v-4 h3 v3 h-1.5 M28 28 v-4 h3 v3 h-1.5" fill="none" strokeWidth="1.2" />
        </g>
        <text x="20" y="41" textAnchor="middle" fontSize="7.5" fontWeight="900" fill="var(--ink)">{mark}</text>
      </svg>
    </span>
  );
}

export function fmtLeft(ms) {
  const s = Math.max(0, Math.ceil(ms / 1000));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  if (h) return `${h} ч ${m} мин`;
  if (m) return `${m} мин ${String(sec).padStart(2, '0')} с`;
  return `${sec} с`;
}

// Четыре слота наборов с живыми таймерами.
export function PackSlots({ eco, profileAt, onUnlock, onOpen }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);
  if (!eco) return null;
  const leftOf = (c) => (c.state === 'unlocking' ? c.msLeft - (now - profileAt) : c.msLeft);
  const anyUnlocking = eco.packs.some((c) => c.state === 'unlocking' && leftOf(c) > 0);
  return (
    <div className="chest-slots">
      {eco.packs.map((c) => {
        if (c.state === 'empty') return <div key={c.slot} className="chest-slot empty"><span>Слот для набора</span></div>;
        const left = leftOf(c);
        const ready = c.state === 'ready' || (c.state === 'unlocking' && left <= 0);
        const unlocking = c.state === 'unlocking' && left > 0;
        const gems = Math.max(1, Math.ceil(left / 360000));
        return (
          <button key={c.slot} className={`chest-slot ${ready ? 'ready' : ''} ${unlocking ? 'unlocking' : ''}`}
            onClick={() => (ready ? onOpen(c.slot, false) : unlocking || anyUnlocking ? onOpen(c.slot, true, gems) : onUnlock(c.slot))}>
            <PackIcon type={c.type} size={46} className={ready ? 'bounce' : ''} />
            <span className="chest-name">{PACKS[c.type].name}</span>
            <span className="chest-state">{rich(ready ? 'Открыть!' : unlocking ? fmtLeft(left) : anyUnlocking ? `💎 ${gems}` : 'Начать')}</span>
          </button>
        );
      })}
    </div>
  );
}

// Открытие: пакетик трясётся и рвётся, наклейки вылетают по одной — редкие в конце со вспышкой.
// ---- Открытие набора: ваза трескается от шести ударов, разбивается, осколки разлетаются ----
const HITS = 6;
const AMPHORA_D = 'M15 3 h10 v5 c8 3 11 11 11 19 c0 9 -6 16 -13 19 h-6 c-7 -3 -13 -10 -13 -19 c0 -8 3 -16 11 -19 z';
// Трещины по ударам: каждая следующая продолжает предыдущие.
const CRACKS = [
  'M21 17 L23 21 L20 24 L22 28',
  'M23 21 L27 23 L29 27 L33 26',
  'M20 24 L15 26 L13 30 L9 29',
  'M22 28 L21 33 L24 37 L23 43',
  'M27 23 L28 17 L31 14 M15 26 L14 20 L10 18 M29 27 L31 33 L28 38',
];
function VaseArt({ type, cracks = 0 }) {
  const [fill, mark] = AMPHORA[type] || AMPHORA.basic;
  return (
    <svg viewBox="-2 -2 44 52" width="100%" height="100%" aria-hidden="true">
      <g stroke="var(--ink)" strokeWidth="1.6" strokeLinejoin="round">
        <path d={AMPHORA_D} fill={fill} />
        <path d="M15 9 c-6 0 -7 7 -2 9 M25 9 c6 0 7 7 2 9" fill="none" />
        <path d="M6 22 h28 M5.5 30 h29" fill="none" />
        <path d="M8 28 v-4 h3 v3 h-1.5 M13 28 v-4 h3 v3 h-1.5 M18 28 v-4 h3 v3 h-1.5 M23 28 v-4 h3 v3 h-1.5 M28 28 v-4 h3 v3 h-1.5" fill="none" strokeWidth="1" />
      </g>
      <text x="20" y="41" textAnchor="middle" fontSize="7.5" fontWeight="900" fill="var(--ink)">{mark}</text>
      <g className="cracks" fill="none" stroke="#140b07" strokeWidth="1.1" strokeLinecap="round" strokeLinejoin="round">
        {CRACKS.slice(0, cracks).map((d, i) => <path key={i} d={d} className="crack" />)}
      </g>
    </svg>
  );
}

// Осколки: клинья из точки удара, каждый — та же ваза, обрезанная clip-path.
const SHARDS = Array.from({ length: 11 }, (_, i) => {
  const a0 = (i / 11) * Math.PI * 2 + (i % 3) * 0.12, a1 = ((i + 1) / 11) * Math.PI * 2 + ((i + 1) % 3) * 0.12;
  const am = (a0 + a1) / 2;
  const pt = (a, r) => `${(50 + Math.cos(a) * r).toFixed(1)}% ${(52 + Math.sin(a) * r).toFixed(1)}%`;
  return {
    clip: `polygon(50% 52%, ${pt(a0, 140)}, ${pt(am, 60 + (i % 4) * 18)}, ${pt(a1, 140)})`,
    dx: `${Math.round(Math.cos(am) * (150 + (i % 3) * 40))}px`,
    dy: `${Math.round(Math.sin(am) * (130 + (i % 4) * 30) + 90)}px`,
    rot: `${(i % 2 ? 1 : -1) * (40 + i * 23)}deg`,
  };
});

export function PackOpenModal({ loot, onClose }) {
  const [hits, setHits] = useState(0);
  const [shown, setShown] = useState(-1); // индекс показанной наклейки; -1 — ещё ваза
  const total = loot.stickers.length;
  useEffect(() => { askTiltPermission(); sfx.whoosh(); }, []);
  const broken = hits >= HITS;
  const done = shown >= total; // после последней наклейки — итог

  const reveal = (i) => {
    setShown(i);
    const s = loot.stickers[i];
    if (!s) return;
    if (s.rarity === 'epic' || s.rarity === 'legend') { sfx.level(); haptic('success'); } else { sfx.rep(i + 1); haptic('light'); }
  };
  const tap = () => {
    if (!broken) {
      const h = hits + 1;
      setHits(h);
      if (h < HITS) { sfx.crack(h); haptic(h < 4 ? 'light' : 'medium'); return; }
      sfx.shatter(); haptic('heavy');
      setTimeout(() => reveal(0), 950); // сначала разлетаются осколки, потом первая наклейка
      return;
    }
    if (shown < 0 || done) return;
    reveal(shown + 1);
  };
  const cur = shown >= 0 && shown < total ? loot.stickers[shown] : null;
  // «Назад» Telegram: до итога — сразу к итогу (награда уже начислена), на итоге — закрыть.
  useEffect(() => bindBackButton(done ? onClose : () => { setHits(HITS); setShown(total); }), [done, onClose, total]);

  return (
    <div className={`pack-screen r-${loot.type === 'basic' ? 'common' : loot.type} ${done ? 'summary' : ''}`} onClick={done ? undefined : tap} role="button" tabIndex={0}>
      <div className="pack-open">
        {shown < 0 && (
          <div className={`vase-stage ${broken ? 'broken' : ''}`} style={{ '--hits': hits }}>
            <div className="vase-rays" />
            {!broken && (
              <div key={hits} className={`vase ${hits ? 'hit' : 'idle'}`}>
                <VaseArt type={loot.type} cracks={Math.min(hits, CRACKS.length)} />
                {hits > 0 && Array.from({ length: 3 + hits }, (_, i) => (
                  <i key={i} className="chip" style={{ '--dx': `${(i % 2 ? 1 : -1) * (14 + i * 9)}px`, '--dy': `${40 + i * 12}px`, '--r': `${i * 70}deg` }} />
                ))}
              </div>
            )}
            {broken && SHARDS.map((s, i) => (
              <div key={i} className="shard" style={{ clipPath: s.clip, '--dx': s.dx, '--dy': s.dy, '--rot': s.rot }}>
                <VaseArt type={loot.type} cracks={CRACKS.length} />
              </div>
            ))}
            {broken && <div className="vase-burst" />}
          </div>
        )}
        {shown < 0 && (
          <div className={`hit-dots ${broken ? 'gone' : ''}`}>{Array.from({ length: HITS }, (_, i) => <i key={i} className={i < hits ? 'on' : ''} />)}</div>
        )}
        {cur && (
          <div key={shown} className={`reveal r-${cur.rarity}`}>
            <div className="reveal-glow" />
            <Sticker id={cur.id} size={170} className="fly" />
            <div className="reveal-name">{stickerById(cur.id).name}</div>
            <div className="reveal-meta" style={{ color: STICKER_RARITY[cur.rarity].color }}>
              {STICKER_RARITY[cur.rarity].name}{cur.isNew ? ' · новая!' : cur.converted ? ` · лишняя → +${cur.converted} 🪙` : ' · ещё копия'}
            </div>
            <div className="hit-dots small">{loot.stickers.map((_, i) => <i key={i} className={i <= shown ? 'on' : ''} />)}</div>
          </div>
        )}
        {done && (
          <div className="pack-summary">
            <h2 className="center">{PACKS[loot.type].name}</h2>
            <div className="sum-grid">{loot.stickers.map((s, i) => <Sticker key={i} id={s.id} size={58} />)}</div>
            <div className="rows" style={{ marginTop: 10 }}>
              <div className="row"><Glyph e="🪙" size={28} /><div className="grow"><div className="title">{loot.coins} монет</div></div></div>
              {loot.gems > 0 && <div className="row"><Glyph e="💎" size={28} /><div className="grow"><div className="title">{loot.gems} кристаллов</div></div></div>}
              {loot.xp > 0 && <div className="row"><Glyph e="⭐" size={28} /><div className="grow"><div className="title">{loot.xp} опыта пропуска</div></div></div>}
              {loot.levelAfter > loot.levelBefore && <div className="new-reward"><Glyph e="🎉" size="1.25em" /> Новый уровень пропуска: {loot.levelAfter}</div>}
            </div>
          </div>
        )}
      </div>
      {done && <button className="gel xl pack-take" onClick={onClose}>Забрать</button>}
    </div>
  );
}

// Редактор карточки: тап по наклейке из коллекции — она появляется на карточке;
// перетаскивайте пальцем, кнопками крутите и меняйте размер.
export function CardEditor({ profile, onSave, onClose }) {
  const owned = profile.eco?.stickers || {};
  const [items, setItems] = useState(() => (profile.card || []).map((x, i) => ({ ...x, k: i })));
  const [sel, setSel] = useState(null);
  const areaRef = useRef(null);
  const drag = useRef(null);
  useEffect(() => { askTiltPermission(); }, []);

  const usedOf = (id) => items.filter((x) => x.id === id).length;
  const add = (id) => {
    if (items.length >= CARD_STICKERS_MAX) return;
    if (usedOf(id) >= (owned[id] || 0)) return;
    const k = Date.now();
    setItems((arr) => [...arr, { id, x: 0.5, y: 0.45, r: Math.round(Math.random() * 30 - 15), s: 1, k }]);
    setSel(k);
    haptic('select');
  };
  const patch = (k, d) => setItems((arr) => arr.map((x) => (x.k === k ? { ...x, ...d } : x)));
  const onDown = (e, k) => {
    e.stopPropagation();
    setSel(k);
    const rect = areaRef.current.getBoundingClientRect();
    drag.current = { k, rect };
    e.currentTarget.setPointerCapture?.(e.pointerId);
  };
  const onMove = (e) => {
    const d = drag.current;
    if (!d) return;
    const x = Math.min(1, Math.max(0, (e.clientX - d.rect.left) / d.rect.width));
    const y = Math.min(1, Math.max(0, (e.clientY - d.rect.top) / d.rect.height));
    patch(d.k, { x, y });
  };
  const onUp = () => { drag.current = null; };
  const cur = items.find((x) => x.k === sel);
  const collection = STICKERS.filter((s) => owned[s.id]);

  return (
    <Sheet onClose={onClose}>
      <h2>Наклейки на карточке</h2>
      <p className="small muted" style={{ fontWeight: 700, marginTop: -8 }}>Тапните наклейку ниже, перетащите её на карточке. До {CARD_STICKERS_MAX} штук. Наклоните телефон — голограммы переливаются.</p>
      <div className="editor-stage" onPointerMove={onMove} onPointerUp={onUp} onPointerCancel={onUp} onClick={() => setSel(null)}>
        <div className="editor-card" ref={areaRef}>
          <PlayerCard player={{ ...profile, card: [] }} />
          {items.map((it) => (
            <span key={it.k} className={`placed ${sel === it.k ? 'sel' : ''}`} onPointerDown={(e) => onDown(e, it.k)} onClick={(e) => e.stopPropagation()}
              style={{ left: `${it.x * 100}%`, top: `${it.y * 100}%`, transform: `translate(-50%, -50%) rotate(${it.r}deg) scale(${it.s})` }}>
              <Sticker id={it.id} size={46} />
            </span>
          ))}
        </div>
      </div>
      {cur && (
        <div className="editor-tools">
          <button className="glass-btn" onClick={() => patch(cur.k, { r: Math.max(-180, cur.r - 15) })}>⟲</button>
          <button className="glass-btn" onClick={() => patch(cur.k, { r: Math.min(180, cur.r + 15) })}>⟳</button>
          <button className="glass-btn" onClick={() => patch(cur.k, { s: Math.max(0.5, +(cur.s - 0.15).toFixed(2)) })}>−</button>
          <button className="glass-btn" onClick={() => patch(cur.k, { s: Math.min(2, +(cur.s + 0.15).toFixed(2)) })}>＋</button>
          <button className="glass-btn danger" onClick={() => { setItems((arr) => arr.filter((x) => x.k !== cur.k)); setSel(null); }}><Glyph e="🗑" size="1.25em" /></button>
        </div>
      )}
      <div className="sticker-tray">
        {!collection.length && <div className="empty">Наклеек пока нет — они в наборах за победы и в магазине</div>}
        {collection.map((s) => {
          const left = (owned[s.id] || 0) - usedOf(s.id);
          return (
            <button key={s.id} className="tray-item" disabled={left <= 0 || items.length >= CARD_STICKERS_MAX} onClick={() => add(s.id)}>
              <Sticker id={s.id} size={44} />
              <span className="tray-count">×{left}</span>
            </button>
          );
        })}
      </div>
      <button className="gel xl" onClick={() => onSave(items.map(({ id, x, y, r, s }) => ({ id, x: +x.toFixed(3), y: +y.toFixed(3), r, s })))}>Сохранить карточку</button>
    </Sheet>
  );
}

// Альбом коллекции по редкостям.
export function Album({ eco }) {
  const owned = eco?.stickers || {};
  return (
    <Card title={`Альбом наклеек · ${eco?.collected || 0}/${eco?.totalStickers || STICKERS.length}`} icon="📒">
      {STICKER_RARITY_ORDER.slice().reverse().map((r) => (
        <div key={r} style={{ marginBottom: 12 }}>
          <div className="small" style={{ fontWeight: 900, color: STICKER_RARITY[r].color, marginBottom: 6 }}>{STICKER_RARITY[r].name.toUpperCase()}</div>
          <div className="album-grid">
            {STICKERS.filter((s) => s.rarity === r).map((s) => (
              <div key={s.id} className={`album-cell ${owned[s.id] ? '' : 'missing'}`}>
                {owned[s.id] ? <Sticker id={s.id} size={50} /> : <span className="album-q">?</span>}
                <span className="album-name">{owned[s.id] ? s.name : '???'}</span>
                {owned[s.id] > 1 && <span className="tray-count">×{owned[s.id]}</span>}
              </div>
            ))}
          </div>
        </div>
      ))}
    </Card>
  );
}
