'use client';
// Наклейки: отрисовка с голограммой, наборы, открытие, редактор карточки, альбом.
import { useEffect, useRef, useState } from 'react';
import { STICKERS, STICKER_RARITY, STICKER_RARITY_ORDER, PACKS, CARD_STICKERS_MAX, stickerById } from '../lib/stickers.js';
import { sfx } from '../lib/client/sound.js';
import { haptic } from '../lib/client/tg.js';
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

// Набор наклеек — глянцевая амфора, цвет по редкости.
const AMPHORA = { basic: ['#ffd9a0', '#d98a3a', '★'], rare: ['#9fe3ff', '#2a8fd6', '★★'], epic: ['#d9a6ff', '#7a3ad8', '★★★'], legend: ['#ffe27a', '#d9900b', '♛'] };
export function PackIcon({ type = 'basic', size = 52, className = '' }) {
  const [a, b, mark] = AMPHORA[type] || AMPHORA.basic;
  const id = `amph-${type}`;
  return (
    <span className={`pack-ico amphora p-${type} ${className}`} style={{ '--sz': `${size}px` }}>
      <svg viewBox="0 0 40 48" width={size * 0.8} height={size} aria-hidden="true">
        <defs><linearGradient id={id} x1="0" y1="0" x2="1" y2="0"><stop offset="0" stopColor={b} /><stop offset="0.42" stopColor={a} /><stop offset="1" stopColor={b} /></linearGradient></defs>
        <path d="M15 3 h10 v5 c8 3 11 11 11 19 c0 9 -6 16 -13 19 h-6 c-7 -3 -13 -10 -13 -19 c0 -8 3 -16 11 -19 z" fill={`url(#${id})`} stroke="rgba(0,0,0,0.35)" strokeWidth="1.2" />
        <path d="M15 9 c-6 0 -7 7 -2 9 M25 9 c6 0 7 7 2 9" fill="none" stroke={b} strokeWidth="2.4" />
        <path d="M7 24 h26" stroke="#1a0f08" strokeWidth="3" opacity=".5" /><path d="M8 29 h24" stroke="#1a0f08" strokeWidth="1.5" opacity=".45" />
        <ellipse cx="14" cy="18" rx="3" ry="7" fill="rgba(255,255,255,0.55)" />
        <text x="20" y="41" textAnchor="middle" fontSize="7.5" fontWeight="900" fill="#1a0f08" opacity=".7">{mark}</text>
      </svg>
      {(type === 'epic' || type === 'legend') && <span className="stk-holo" />}
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
            <span className="chest-state">{ready ? 'Открыть!' : unlocking ? fmtLeft(left) : anyUnlocking ? `💎 ${gems}` : 'Начать'}</span>
          </button>
        );
      })}
    </div>
  );
}

// Открытие: пакетик трясётся и рвётся, наклейки вылетают по одной — редкие в конце со вспышкой.
export function PackOpenModal({ loot, onClose }) {
  const [shown, setShown] = useState(-1); // -1 — пакетик ещё целый
  const total = loot.stickers.length;
  useEffect(() => { askTiltPermission(); sfx.whoosh(); }, []);
  const next = () => {
    if (shown > total) return;
    const i = shown + 1;
    setShown(i);
    if (i === 0) { sfx.impact(); haptic('heavy'); return; }
    const s = loot.stickers[i - 1];
    if (s && (s.rarity === 'epic' || s.rarity === 'legend')) { sfx.level(); haptic('success'); } else { sfx.rep(i); haptic('light'); }
  };
  const done = shown > total; // после последней наклейки — итог
  const cur = shown >= 1 && shown <= total ? loot.stickers[shown - 1] : null;
  return (
    <Sheet onClose={done ? onClose : next} center>
      <div className="pack-open" onClick={next} role="button" tabIndex={0}>
        {shown < 0 && <><PackIcon type={loot.type} size={150} className="shake" /><p className="center muted" style={{ fontWeight: 800, marginTop: 10 }}>Нажмите, чтобы открыть</p></>}
        {shown === 0 && <><PackIcon type={loot.type} size={150} className="tear" /><p className="center muted" style={{ fontWeight: 800 }}>Ещё раз!</p></>}
        {cur && (
          <div key={shown} className={`reveal r-${cur.rarity}`}>
            <div className="reveal-glow" />
            <Sticker id={cur.id} size={140} className="fly" />
            <div className="reveal-name">{stickerById(cur.id).name}</div>
            <div className="reveal-meta" style={{ color: STICKER_RARITY[cur.rarity].color }}>
              {STICKER_RARITY[cur.rarity].name}{cur.isNew ? ' · новая!' : cur.converted ? ` · лишняя → +${cur.converted} 🪙` : ' · ещё копия'}
            </div>
            <div className="small muted">{shown} из {total} · нажмите дальше</div>
          </div>
        )}
        {done && (
          <div className="pack-summary">
            <h2 className="center">{PACKS[loot.type].name}</h2>
            <div className="sum-grid">{loot.stickers.map((s, i) => <Sticker key={i} id={s.id} size={58} />)}</div>
            <div className="rows" style={{ marginTop: 10 }}>
              <div className="row"><span style={{ fontSize: 22 }}>🪙</span><div className="grow"><div className="title">{loot.coins} монет</div></div></div>
              {loot.gems > 0 && <div className="row"><span style={{ fontSize: 22 }}>💎</span><div className="grow"><div className="title">{loot.gems} кристаллов</div></div></div>}
              {loot.xp > 0 && <div className="row"><span style={{ fontSize: 22 }}>⭐</span><div className="grow"><div className="title">{loot.xp} опыта пропуска</div></div></div>}
              {loot.levelAfter > loot.levelBefore && <div className="new-reward">🎉 Новый уровень пропуска: {loot.levelAfter}</div>}
            </div>
          </div>
        )}
      </div>
      {done ? <button className="gel xl" onClick={onClose}>Забрать</button> : <button className="glass-btn" onClick={(e) => { e.stopPropagation(); setShown(total + 1); }}>Показать все</button>}
    </Sheet>
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
          <button className="glass-btn danger" onClick={() => { setItems((arr) => arr.filter((x) => x.k !== cur.k)); setSel(null); }}>🗑</button>
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
    <Card title={`Альбом наклеек · ${eco?.collected || 0}/${eco?.totalStickers || STICKERS.length}`} icon="📒" tone="rose">
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
