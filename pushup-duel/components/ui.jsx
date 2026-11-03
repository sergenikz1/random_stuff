'use client';
// Базовые элементы интерфейса: плоский греческий стиль.
import { useEffect } from 'react';
import { Glyph, rich } from './Icon.jsx';
import { AvatarArt } from './Emblem.jsx';
import { bindBackButton } from '../lib/client/tg.js';

// Фон: сплошной цвет темы, без пузырей и бликов.
export function Sky() {
  return <div className="sky" aria-hidden="true" />;
}

export function Card({ title, icon, link, onLink, className = '', children, style }) {
  return (
    <section className={`card ${className}`} style={style}>
      {title && (
        <div className="card-head">
          {icon && <span className="tile"><Glyph e={icon} size={30} /></span>}
          <h2>{title}</h2>
          {link && <button className="card-link" onClick={onLink}>{link} ›</button>}
        </div>
      )}
      {children}
    </section>
  );
}

// Аватар-конструктор: фигура (emoji = id фигуры) и облик look (форма, окантовка, цвета).
export function Avatar({ emoji, look, frame = 'none', size = '', side = '' }) {
  return <AvatarArt fig={emoji} look={look} frame={frame} size={size} side={side} />;
}

export function Bar({ value, max = 1, tone = '' }) {
  const pct = max > 0 ? Math.max(0, Math.min(100, (value / max) * 100)) : 0;
  return <div className={`bar ${tone}`}><i style={{ width: `${pct}%` }} /></div>;
}

export function Seg({ value, options, onChange }) {
  return (
    <div className="seg" role="tablist">
      {options.map(([v, label]) => (
        <button key={v} role="tab" aria-selected={value === v} className={value === v ? 'on' : ''} onClick={() => onChange(v)}>{label}</button>
      ))}
    </div>
  );
}

export function Sheet({ onClose, children, center = false }) {
  useEffect(() => bindBackButton(onClose), [onClose]);
  return (
    <div className={`scrim ${center ? 'modal-center' : ''}`} onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="sheet" role="dialog">
        {!center && <div className="grip" />}
        {children}
      </div>
    </div>
  );
}

export function Toast({ text }) {
  if (!text) return null;
  return <div className="toast" role="status">{rich(text)}</div>;
}

export const fmtTime = (ms) => {
  const s = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};

export function ago(at) {
  const s = Math.max(1, Math.round((Date.now() - at) / 1000));
  if (s < 60) return `${s} с`;
  if (s < 3600) return `${Math.round(s / 60)} мин`;
  if (s < 86400) return `${Math.round(s / 3600)} ч`;
  return `${Math.round(s / 86400)} д`;
}

export const plural = (n, one, few, many) => {
  const a = Math.abs(n) % 100, b = a % 10;
  if (a > 10 && a < 20) return many;
  if (b > 1 && b < 5) return few;
  if (b === 1) return one;
  return many;
};

export function Medal({ place }) {
  if (place === 1) return <span className="place"><Glyph e="🥇" size="1.25em" /></span>;
  if (place === 2) return <span className="place"><Glyph e="🥈" size="1.25em" /></span>;
  if (place === 3) return <span className="place"><Glyph e="🥉" size="1.25em" /></span>;
  return <span className="place">{place}</span>;
}
