'use client';
// Главная («Бой»): неподвижный экран. Сверху профиль и валюта, под ними стрик и отсчёт до вулкана,
// по центру арена с кнопкой «В бой» и выбором режима, внизу — амфоры (наборы наклеек).
import { useEffect, useMemo, useState } from 'react';
import { Avatar } from './ui.jsx';
import { PackSlots, Currency } from './Economy.jsx';
import { ARENAS } from '../lib/economy.js';
import { api } from '../lib/client/net.js';
import { arenaSvg } from '../lib/client/arenaArt.js';
import { StreakBar, ExerciseIcon, EXERCISES, FORMATS, roman } from './Greek.jsx';

export default function Home({ profile, status, settings, theme, onPlay, onMode, onStreak, onUnlockPack, onOpenPack, onRoad, onProfile, onInbox, version }) {
  const p = profile;
  const eco = p?.eco;
  const myArena = eco?.arena?.id || 1;
  const [view, setView] = useState(myArena);
  const [belts, setBelts] = useState([]);
  useEffect(() => { setView(myArena); }, [myArena]);
  useEffect(() => { api('belts').then((r) => setBelts(r.belts)).catch(() => {}); }, [version]);
  const dark = theme === 'midnight';
  const art = useMemo(() => arenaSvg(view, dark), [view, dark]);
  if (!p) return <div className="page home" />;

  const arena = ARENAS.find((a) => a.id === view);
  const next = ARENAS.find((a) => a.min > arena.min);
  const locked = (p.eco?.bestRating || p.rating) < arena.min;
  const belt = belts.find((b) => b.arena.id === view);
  const roadReady = eco?.road?.filter((r) => r.status === 'ready').length || 0;
  const ex = EXERCISES.find((e) => e.id === settings.exercise) || EXERCISES[0];
  const fmt = FORMATS.find((f) => f.id === settings.format) || FORMATS[0];
  const needsOnline = fmt.id === 'ranked' || fmt.id === 'friend';

  return (
    <div className="page home">
      <div className="topbar">
        <button className="topbar-me" onClick={onProfile} aria-label="Профиль">
          <Avatar emoji={p.avatar} frame={p.equipped?.frame} size="sm" />
          <span className="topbar-lvl">{roman(p.level)}</span>
          <span className="topbar-col">
            <span className="topbar-name">{p.name}</span>
            <span className="topbar-xp"><i style={{ width: `${p.max ? 100 : Math.round((p.xpInLevel / p.xpNeed) * 100)}%` }} /></span>
          </span>
        </button>
        <Currency eco={eco} />
      </div>

      <StreakBar profile={p} onOpen={onStreak} />

      <section className="glass-panel arena-stage">
        <div className={`arena-art ${locked ? 'locked' : ''}`} dangerouslySetInnerHTML={{ __html: art }} />
        <button className="arena-plate" onClick={onRoad}>
          <span className="greek-title">Арена {roman(arena.id)} · {arena.name}</span>
          <span className="small">
            {locked ? `🔒 откроется на ${arena.min} 🏆`
              : view === myArena ? `🏆 ${p.rating}${next ? ` / ${next.min}` : ''}${belt?.holder ? ` · пояс: ${belt.mine ? 'ваш!' : belt.holder.name}` : ' · пояс свободен'}`
                : belt?.holder ? `пояс: ${belt.holder.name}` : 'пояс свободен'}
          </span>
          {roadReady > 0 && <span className="badge-dot">{roadReady}</span>}
        </button>
        {view > 1 && <button className="arena-arrow l" onClick={() => setView(view - 1)} aria-label="Предыдущая арена">‹</button>}
        {view < ARENAS.length && <button className="arena-arrow r" onClick={() => setView(view + 1)} aria-label="Следующая арена">›</button>}
        {eco?.inbox?.length > 0 && (
          <button className="inbox-btn" onClick={onInbox} aria-label="Награды"><span className="tile gold">📬</span><span className="badge-dot">{eco.inbox.length}</span></button>
        )}
        <div className="meander-band" />
        <div className="arena-actions">
          <button className="gel battle-btn" disabled={needsOnline && status !== 'online'} onClick={onPlay}>
            ⚔ В БОЙ
          </button>
          <button className="mode-pick" onClick={onMode}>
            <ExerciseIcon kind={ex.id} size={40} />
            <b>{ex.name}</b>
            <span>{fmt.icon} {fmt.name} ▾</span>
          </button>
        </div>
      </section>

      <PackSlots eco={eco} profileAt={p._at || Date.now()} onUnlock={onUnlockPack} onOpen={onOpenPack} />
    </div>
  );
}

export function PlayerRow({ r, onClick, right }) {
  return (
    <button className={`row ${r.me ? 'me' : ''}`} onClick={onClick} style={{ textAlign: 'left', width: '100%' }}>
      {r.place !== undefined && <span className="place">{r.place <= 3 ? ['🥇', '🥈', '🥉'][r.place - 1] : r.place}</span>}
      <Avatar emoji={r.avatar} frame={r.frame} size="sm" />
      <div className="grow">
        <div className="title">{r.name}{r.me ? ' (вы)' : ''}</div>
        <div className="sub">{[r.clan && `${r.clan.emoji} ${r.clan.name}`, r.title, `ур. ${r.level}`].filter(Boolean).join(' · ')}</div>
      </div>
      {right}
    </button>
  );
}
