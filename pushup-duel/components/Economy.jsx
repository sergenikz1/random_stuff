'use client';
// Сундуки, валюта, арена и дорога трофеев.
import { useEffect, useState } from 'react';
import { CHESTS, ROAD, ARENAS } from '../lib/economy.js';
import { cosmeticName } from '../lib/pass.js';
import { sfx } from '../lib/client/sound.js';
import { haptic } from '../lib/client/tg.js';
import { Bar, Card, Sheet } from './ui.jsx';
import { RewardIcon } from './Pass.jsx';

export function fmtLeft(ms) {
  const s = Math.max(0, Math.ceil(ms / 1000));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  if (h) return `${h} ч ${m} мин`;
  if (m) return `${m} мин ${String(sec).padStart(2, '0')} с`;
  return `${sec} с`;
}

// Глянцевый сундук: цвет по типу, у волшебного — искры.
export function ChestIcon({ type = 'wood', size = 56, open = false, className = '' }) {
  const [a, b] = CHESTS[type]?.colors || CHESTS.wood.colors;
  const id = `ch-${type}`;
  return (
    <svg className={`chest-svg ${className}`} width={size} height={size * 0.9} viewBox="0 0 64 58" aria-hidden="true">
      <defs>
        <linearGradient id={`${id}-b`} x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor={a} /><stop offset="1" stopColor={b} /></linearGradient>
        <linearGradient id={`${id}-g`} x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="#fff" stopOpacity="0.7" /><stop offset="0.5" stopColor="#fff" stopOpacity="0.1" /><stop offset="0.52" stopColor="#fff" stopOpacity="0" /></linearGradient>
      </defs>
      <ellipse cx="32" cy="54" rx="24" ry="3.5" fill="rgba(0,0,0,0.22)" />
      <rect x="6" y="26" width="52" height="27" rx="5" fill={`url(#${id}-b)`} stroke="rgba(0,0,0,0.35)" strokeWidth="1.5" />
      <rect x="6" y="26" width="52" height="27" rx="5" fill={`url(#${id}-g)`} />
      <g className="chest-lid" style={{ transformOrigin: '32px 26px', transform: open ? 'translateY(-14px) rotate(-12deg)' : 'none' }}>
        <path d="M6 27 Q6 8 32 8 Q58 8 58 27 Z" fill={`url(#${id}-b)`} stroke="rgba(0,0,0,0.35)" strokeWidth="1.5" />
        <path d="M9 24 Q10 11 32 11 Q54 11 55 24 Z" fill={`url(#${id}-g)`} />
        <rect x="14" y="9" width="6" height="18" fill="rgba(0,0,0,0.18)" />
        <rect x="44" y="9" width="6" height="18" fill="rgba(0,0,0,0.18)" />
      </g>
      <rect x="14" y="26" width="6" height="27" fill="rgba(0,0,0,0.18)" />
      <rect x="44" y="26" width="6" height="27" fill="rgba(0,0,0,0.18)" />
      <rect x="26" y="22" width="12" height="13" rx="3" fill="#ffd23f" stroke="#8a5a00" strokeWidth="1.5" />
      <circle cx="32" cy="28" r="2" fill="#8a5a00" />
      {type === 'magic' && (
        <g fill="#fff">
          <path d="M8 6 l1.5 3.5 3.5 1.5 -3.5 1.5 -1.5 3.5 -1.5 -3.5 -3.5 -1.5 3.5 -1.5z" className="sparkle" />
          <path d="M57 14 l1 2.5 2.5 1 -2.5 1 -1 2.5 -1 -2.5 -2.5 -1 2.5 -1z" className="sparkle s2" />
        </g>
      )}
    </svg>
  );
}

export function Currency({ eco }) {
  if (!eco) return null;
  return (
    <div className="currency">
      <span className="cur-pill">🪙 <b>{eco.coins}</b></span>
      <span className="cur-pill">💎 <b>{eco.gems}</b></span>
    </div>
  );
}

// Четыре слота сундуков с живыми таймерами.
export function ChestSlots({ eco, profileAt, onUnlock, onOpen }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);
  if (!eco) return null;
  const anyUnlocking = eco.chests.some((c) => c.state === 'unlocking' && c.msLeft - (now - profileAt) > 0);
  return (
    <div className="chest-slots">
      {eco.chests.map((c) => {
        if (c.state === 'empty') return <div key={c.slot} className="chest-slot empty"><span>Слот для сундука</span></div>;
        const left = c.state === 'unlocking' ? c.msLeft - (now - profileAt) : c.msLeft;
        const ready = c.state === 'ready' || (c.state === 'unlocking' && left <= 0);
        const unlocking = c.state === 'unlocking' && left > 0;
        const gems = Math.max(1, Math.ceil(left / 360000));
        return (
          <button key={c.slot} className={`chest-slot ${ready ? 'ready' : ''} ${unlocking ? 'unlocking' : ''}`}
            onClick={() => (ready ? onOpen(c.slot, false) : unlocking || anyUnlocking ? onOpen(c.slot, true, gems) : onUnlock(c.slot))}>
            <ChestIcon type={c.type} size={50} className={ready ? 'bounce' : ''} />
            <span className="chest-name">{CHESTS[c.type].name}</span>
            <span className="chest-state">
              {ready ? 'Открыть!' : unlocking ? fmtLeft(left) : anyUnlocking ? `💎 ${gems}` : 'Начать'}
            </span>
          </button>
        );
      })}
    </div>
  );
}

// Анимация открытия: сундук трясётся, крышка отлетает, награды выезжают по одной.
export function ChestOpenModal({ loot, onClose }) {
  const [stage, setStage] = useState(0);
  useEffect(() => {
    sfx.whoosh();
    const t1 = setTimeout(() => { setStage(1); sfx.impact(); haptic('heavy'); }, 900);
    const t2 = setTimeout(() => { setStage(2); sfx.level(); }, 1300);
    return () => { clearTimeout(t1); clearTimeout(t2); };
  }, []);
  const rows = [
    { icon: '🪙', text: `${loot.coins} монет` },
    loot.gems ? { icon: '💎', text: `${loot.gems} кристаллов` } : null,
    { icon: '⭐', text: `${loot.xp} опыта пропуска` },
    ...loot.items.map((it) => ({ key: it.key, text: it.duplicate ? `${it.name} — повтор, +60 🪙` : `Новое: ${it.name}` })),
  ].filter(Boolean);
  return (
    <Sheet onClose={onClose} center>
      <div className={`chest-open stage-${stage}`}>
        <div className="chest-rays" />
        <ChestIcon type={loot.type} size={130} open={stage >= 1} className={stage === 0 ? 'shake' : 'pop'} />
      </div>
      <h2 className="center">{CHESTS[loot.type].name} сундук</h2>
      {stage >= 2 && (
        <div className="rows">
          {rows.map((r, i) => (
            <div key={i} className="row loot-row" style={{ animationDelay: `${i * 160}ms` }}>
              {r.key ? <RewardIcon k={r.key} /> : <span style={{ fontSize: 24 }}>{r.icon}</span>}
              <div className="grow"><div className="title">{r.text}</div></div>
            </div>
          ))}
          {loot.levelAfter > loot.levelBefore && <div className="new-reward">🎉 Новый уровень пропуска: {loot.levelAfter}</div>}
        </div>
      )}
      <button className="gel xl" onClick={onClose} disabled={stage < 2}>Забрать</button>
    </Sheet>
  );
}

export function ArenaCard({ eco, onRoad }) {
  if (!eco) return null;
  const { arena, nextArena, trophies } = eco;
  const ready = eco.road.filter((r) => r.status === 'ready').length;
  return (
    <button className="arena-card" onClick={onRoad} style={{ '--a1': arena.colors[0], '--a2': arena.colors[1] }}>
      <span className="arena-emoji">{arena.emoji}</span>
      <div className="grow" style={{ textAlign: 'left' }}>
        <div className="arena-name">Арена {arena.id} · {arena.name}</div>
        <div className="arena-sub">🏆 {trophies}{nextArena ? ` / ${nextArena.min} до «${nextArena.name}»` : ' · высшая арена'}</div>
        {nextArena && <div style={{ marginTop: 6 }}><Bar value={trophies - arena.min} max={nextArena.min - arena.min} tone="gold" /></div>}
      </div>
      {ready > 0 && <span className="badge-dot">{ready}</span>}
      <span className="arena-go">›</span>
    </button>
  );
}

function rewardText(r) {
  if (r.coins) return { icon: '🪙', text: `${r.coins} монет` };
  if (r.gems) return { icon: '💎', text: `${r.gems} кристаллов` };
  if (r.chest) return { chest: r.chest, text: `${CHESTS[r.chest].name} сундук` };
  if (r.item) return { item: r.item, text: cosmeticName(r.item) };
  return { icon: '🎁', text: 'Награда' };
}

export function TrophyRoad({ eco, onClose, onClaim }) {
  if (!eco) return null;
  // Дорога снизу вверх: арены-заголовки и награды между ними.
  const items = [];
  for (const a of ARENAS) {
    items.push({ kind: 'arena', a });
    for (const r of eco.road.filter((x) => x.at >= a.min && (ARENAS.find((n) => n.min > a.min)?.min ?? Infinity) > x.at)) items.push({ kind: 'step', r });
  }
  return (
    <Sheet onClose={onClose}>
      <h2>Дорога трофеев</h2>
      <p className="small muted" style={{ fontWeight: 700 }}>🏆 Кубки — это ваш рейтинг. Награды открываются по лучшему результату и остаются навсегда.</p>
      <div className="road">
        {items.map((it, i) => it.kind === 'arena' ? (
          <div key={`a${it.a.id}`} className={`road-arena ${eco.bestRating >= it.a.min ? 'reached' : ''}`} style={{ '--a1': it.a.colors[0], '--a2': it.a.colors[1] }}>
            <span className="arena-emoji">{it.a.emoji}</span>
            <div><div className="arena-name">Арена {it.a.id} · {it.a.name}</div><div className="arena-sub">от {it.a.min} 🏆</div></div>
          </div>
        ) : (() => {
          const t = rewardText(it.r.reward);
          return (
            <div key={`s${it.r.at}`} className={`road-step ${it.r.status}`}>
              <span className="road-at">{it.r.at} 🏆</span>
              <div className="row" style={{ flex: 1 }}>
                {t.chest ? <ChestIcon type={t.chest} size={34} /> : t.item ? <RewardIcon k={t.item} /> : <span style={{ fontSize: 22 }}>{t.icon}</span>}
                <div className="grow"><div className="title">{t.text}</div></div>
                {it.r.status === 'claimed' ? <span>✅</span> : it.r.status === 'ready'
                  ? <button className="gel green" style={{ padding: '6px 14px', fontSize: 13 }} onClick={() => onClaim(it.r.at)}>Забрать</button>
                  : <span>🔒</span>}
              </div>
            </div>
          );
        })())}
      </div>
    </Sheet>
  );
}

export function ChestInfo() {
  return (
    <Card title="Сундуки" icon="🧰" tone="orange">
      <div className="rows">
        {Object.entries(CHESTS).map(([id, c]) => (
          <div key={id} className="row">
            <ChestIcon type={id} size={36} />
            <div className="grow"><div className="title">{c.name}</div><div className="sub">{c.unlockMin >= 60 ? `${c.unlockMin / 60} ч` : `${c.unlockMin} мин`} · {c.coins[0]}–{c.coins[1]} 🪙{c.gemChance ? ` · 💎` : ''} · {c.xp} опыта</div></div>
          </div>
        ))}
      </div>
    </Card>
  );
}
