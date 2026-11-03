'use client';
// Пояса чемпионов, турнир недели и почта с наградами.
import { useEffect, useState } from 'react';
import { PACKS } from '../lib/economy.js';
import { stickerById } from '../lib/stickers.js';
import { api } from '../lib/client/net.js';
import { Avatar, Card, plural } from './ui.jsx';
import { PackIcon, fmtLeft } from './Stickers.jsx';

// Чемпионский пояс: кожаный ремень с золотой пластиной и эмблемой арены.
export function BeltIcon({ arena, size = 64, empty = false }) {
  return (
    <span className={`belt ${empty ? 'empty' : ''}`} style={{ '--sz': `${size}px`, '--a1': arena.colors[0], '--a2': arena.colors[1] }}>
      <span className="belt-strap" />
      <span className="belt-plate"><span>{arena.emoji}</span></span>
    </span>
  );
}

const days = (since) => Math.max(0, Math.floor((Date.now() - since) / 86400000));

export function BeltsList({ openPlayer, onChallenge, toast, version }) {
  const [belts, setBelts] = useState(null);
  useEffect(() => { api('belts').then((r) => setBelts(r.belts)).catch((e) => toast(e.message)); }, [version, toast]);
  if (!belts) return <Card><div className="empty">Загрузка…</div></Card>;
  return (
    <Card title="Пояса чемпионов" icon="🥊" tone="gold" wave={['#ffe27a', '#ff9f43']}>
      <p className="small" style={{ fontWeight: 600, marginBottom: 10 }}>
        На каждой арене один пояс. Победите чемпиона своей арены в рейтинговом или титульном бою — пояс ваш.
        Кто держит пояс в момент смены недели, получает большую награду.
      </p>
      <div className="rows">
        {belts.slice().reverse().map((b) => (
          <div key={b.arena.id} className={`row belt-row ${b.mine ? 'me' : ''}`}>
            <BeltIcon arena={b.arena} size={54} empty={!b.holder} />
            <div className="grow">
              <div className="title">Арена {b.arena.id} · {b.arena.name}</div>
              {b.holder ? (
                <button className="sub belt-holder" onClick={() => openPlayer(b.holder.id)}>
                  <Avatar emoji={b.holder.avatar} frame={b.holder.frame} size="sm" /> {b.holder.name}{b.mine ? ' (вы)' : ''} · {days(b.since)} {plural(days(b.since), 'день', 'дня', 'дней')} · защит: {b.defenses}
                </button>
              ) : <div className="sub">Свободен — выиграйте рейтинговый бой на этой арене</div>}
              <div className="sub">За неделю: 💎 {b.weekly.gems} + {PACKS[b.weekly.pack].name.toLowerCase()}</div>
            </div>
            {b.canChallenge && <button className="gel orange" style={{ padding: '7px 12px', fontSize: 13 }} onClick={() => onChallenge(b.holder)}>Вызов</button>}
          </div>
        ))}
      </div>
    </Card>
  );
}

export function TourneyCard({ profile, onRegister, onPlay, status, version, toast, compact = false }) {
  const [t, setT] = useState(null);
  const [, tick] = useState(0);
  useEffect(() => { api('tourney').then((r) => setT(r.tourney)).catch((e) => toast?.(e.message)); }, [version, profile?.eco?.tickets, toast]);
  useEffect(() => { const i = setInterval(() => tick((x) => x + 1), 30000); return () => clearInterval(i); }, []);
  if (!t) return null;
  const left = t.entry ? t.matchesPerEntry - t.entry.matches : 0;
  const when = t.active ? `идёт · закончится через ${fmtLeft(t.end - Date.now())}` : t.upcoming ? `старт в пятницу, через ${fmtLeft(t.start - Date.now())}` : 'закончен';
  return (
    <Card title="Турнир недели" icon="🎟️" tone="violet" wave={['#b49bff', '#ff9fd5']}>
      <div className="small" style={{ fontWeight: 800, marginBottom: 8 }}>Пятница — воскресенье · {when}</div>
      {t.entry ? (
        <div className="row" style={{ marginBottom: 10 }}>
          <span className="place" style={{ fontSize: 20 }}>#{t.place}</span>
          <div className="grow"><div className="title">{t.entry.points} очков · {t.entry.wins} побед</div><div className="sub">из {t.players} участников · матчей осталось: {left}</div></div>
        </div>
      ) : (
        <p className="small" style={{ fontWeight: 600, marginBottom: 10 }}>Вход — 1 билет 🎟️ ({t.tickets} у вас). Билеты дают за каждые 3 победы в рейтинге и на дороге трофеев. {t.matchesPerEntry} матчей, 3 очка за победу.</p>
      )}
      {!t.entry && <button className="gel xl violet" disabled={t.tickets < 1 || !(t.active || t.upcoming)} onClick={onRegister}>Участвовать за 1 🎟️</button>}
      {t.entry && t.active && <button className="gel xl violet" disabled={left <= 0 || status !== 'online'} onClick={onPlay}>{left > 0 ? '⚔️ Турнирный матч' : 'Все матчи сыграны'}</button>}
      {!compact && (
        <>
          <div className="small" style={{ fontWeight: 900, margin: '14px 0 6px', color: 'var(--heading)' }}>Призы</div>
          <div className="rows">
            {t.prizes.map((p) => (
              <div key={p.places[0]} className="row">
                <span className="chip gold">{p.places[0] === p.places[1] ? `${p.places[0]} место` : `${p.places[0]}–${p.places[1]}`}</span>
                <div className="grow"><div className="title">{[p.gift && `🎁 подарок Telegram ${p.gift} ★`, PACKS[p.pack].name.toLowerCase(), `💎 ${p.gems}`, p.sticker && `наклейка «${stickerById(p.sticker).name}»`].filter(Boolean).join(' · ')}</div></div>
              </div>
            ))}
          </div>
          <p className="small muted" style={{ marginTop: 6, fontWeight: 600 }}>Чтобы попасть в призы, сыграйте хотя бы {t.minMatches} матча. Исход решает только сила — никакой случайности.</p>
          {t.top.length > 0 && (
            <>
              <div className="small" style={{ fontWeight: 900, margin: '14px 0 6px', color: 'var(--heading)' }}>Таблица</div>
              <div className="rows">
                {t.top.slice(0, 10).map((r) => (
                  <div key={r.id} className={`row ${r.me ? 'me' : ''}`}>
                    <span className="place">{r.place <= 3 ? ['🥇', '🥈', '🥉'][r.place - 1] : r.place}</span>
                    <Avatar emoji={r.avatar} frame={r.frame} size="sm" />
                    <div className="grow"><div className="title">{r.name}</div><div className="sub">{r.wins} побед · {r.matches} матчей</div></div>
                    <span className="chip">{r.points}</span>
                  </div>
                ))}
              </div>
            </>
          )}
        </>
      )}
    </Card>
  );
}

export function InboxCard({ eco, onClaim }) {
  if (!eco?.inbox?.length) return null;
  return (
    <Card title="Награды" icon="📬" tone="gold">
      <div className="rows">
        {eco.inbox.map((it) => (
          <div key={it.id} className="row">
            {it.pack ? <PackIcon type={it.pack} size={34} /> : <span style={{ fontSize: 24 }}>{it.gift ? '🎁' : '🏅'}</span>}
            <div className="grow">
              <div className="title">{it.title}</div>
              <div className="sub">{[it.gift && `подарок Telegram ${it.gift} ★`, it.pack && PACKS[it.pack].name.toLowerCase(), it.gems && `💎 ${it.gems}`, it.sticker && `наклейка «${stickerById(it.sticker)?.name}»`].filter(Boolean).join(' · ')}</div>
            </div>
            <button className="gel green" style={{ padding: '6px 14px', fontSize: 13 }} onClick={() => onClaim(it.id)}>{it.gift ? 'Отправить' : 'Забрать'}</button>
          </div>
        ))}
      </div>
    </Card>
  );
}
