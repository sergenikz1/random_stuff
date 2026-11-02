'use client';
// Базовые элементы интерфейса в стиле Aqua.
import { useEffect, useId, useMemo } from 'react';
import { bindBackButton } from '../lib/client/tg.js';

export function Sky() {
  const bubbles = useMemo(() => Array.from({ length: 9 }, (_, i) => ({
    size: 14 + ((i * 37) % 60),
    left: (i * 23 + 7) % 96,
    top: (i * 41 + 11) % 88,
    delay: -(i * 1.7),
    dur: 11 + (i % 5) * 2,
  })), []);
  const stars = useMemo(() => Array.from({ length: 26 }, (_, i) => ({
    left: (i * 53) % 100, top: (i * 29) % 70, delay: -(i * 0.37),
  })), []);
  return (
    <div className="sky" aria-hidden="true">
      {stars.map((s, i) => <span key={`s${i}`} className="star" style={{ left: `${s.left}%`, top: `${s.top}%`, animationDelay: `${s.delay}s` }} />)}
      {bubbles.map((b, i) => (
        <span key={i} className="bubble" style={{ width: b.size, height: b.size, left: `${b.left}%`, top: `${b.top}%`, animationDelay: `${b.delay}s`, animationDuration: `${b.dur}s` }} />
      ))}
      <svg className="sky-wave" viewBox="0 0 400 100" preserveAspectRatio="none">
        <path d="M0 60 C 80 20, 160 90, 240 55 S 360 30, 400 50 L400 100 L0 100 Z" fill="rgba(255,255,255,0.25)" />
        <path d="M0 75 C 90 50, 170 100, 260 70 S 360 60, 400 72 L400 100 L0 100 Z" fill="rgba(255,255,255,0.3)" />
      </svg>
    </div>
  );
}

// Волна внизу карточки: два цвета модуля, как в «стеклянных» интерфейсах 2000-х.
export function Wave({ a = 'var(--wave-a)', b = 'var(--wave-b)' }) {
  const id = useId().replace(/:/g, '');
  return (
    <svg className="wave" viewBox="0 0 400 70" preserveAspectRatio="none" aria-hidden="true">
      <defs>
        <linearGradient id={`w1${id}`} x1="0" x2="1"><stop offset="0" stopColor={a} stopOpacity="0.45" /><stop offset="1" stopColor={b} stopOpacity="0.55" /></linearGradient>
        <linearGradient id={`w2${id}`} x1="0" x2="1"><stop offset="0" stopColor={b} stopOpacity="0.35" /><stop offset="1" stopColor={a} stopOpacity="0.3" /></linearGradient>
      </defs>
      <path d="M0 40 C 90 10, 180 60, 270 30 S 380 20, 400 32 L400 70 L0 70 Z" fill={`url(#w1${id})`} />
      <path d="M0 52 C 100 34, 200 70, 300 46 S 380 44, 400 50 L400 70 L0 70 Z" fill={`url(#w2${id})`} />
    </svg>
  );
}

export function Card({ title, icon, tone = 'blue', link, onLink, wave, className = '', children, style }) {
  return (
    <section className={`card ${className}`} style={style}>
      {wave && <Wave a={wave[0]} b={wave[1]} />}
      {title && (
        <div className="card-head">
          {icon && <span className={`tile ${tone}`}>{icon}</span>}
          <h2>{title}</h2>
          {link && <button className="card-link" onClick={onLink}>{link} ›</button>}
        </div>
      )}
      {children}
    </section>
  );
}

export function Avatar({ emoji, frame = 'none', size = '', side = '' }) {
  const f = frame && frame !== 'none' ? `f-${frame}` : side;
  return (
    <span className={`ava ${size} ${f}`}>
      <i><b>{emoji || '🙂'}</b></i>
    </span>
  );
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
  return <div className="toast" role="status">{text}</div>;
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
  if (place === 1) return <span className="place">🥇</span>;
  if (place === 2) return <span className="place">🥈</span>;
  if (place === 3) return <span className="place">🥉</span>;
  return <span className="place">{place}</span>;
}
