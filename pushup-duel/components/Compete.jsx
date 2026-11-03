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

// Турниры «до трёх поражений»: вход за кристаллы, награда по числу побед.
function rewardLine(r) {
  return [r.gift && `🎁 подарок ${r.gift} ★`, r.pack && PACKS[r.pack].name.toLowerCase(), r.gems && `💎 ${r.gems}`, r.coins && `🪙 ${r.coins}`, r.sticker && `наклейка «${stickerById(r.sticker)?.name}»`]
    .filter(Boolean).join(' · ');
}

export function TourneysView({ profile, onStart, onPlay, status, version, toast }) {
  const [runs, setRuns] = useState(null);
  useEffect(() => { api('tourney').then((r) => setRuns(r.runs)).catch((e) => toast?.(e.message)); }, [version, profile?.eco?.gems, toast]);
  if (!runs) return <Card><div className="empty">Загрузка…</div></Card>;
  const gems = profile?.eco?.gems ?? 0;
  return (
    <>
      {runs.map((t) => {
        const run = t.run;
        return (
          <Card key={t.kind} title={t.name} icon={t.kind === 'big' ? '🏆' : '🏅'} tone={t.kind === 'big' ? 'gold' : 'violet'} wave={t.kind === 'big' ? ['#ffe27a', '#ff9f43'] : ['#b49bff', '#ff9fd5']}>
            <p className="small" style={{ fontWeight: 600, marginBottom: 10 }}>
              Вход — <b>{t.entry} 💎</b>. Играйте, пока не наберёте {t.maxLosses} поражения или {t.maxWins} побед. Чем больше побед, тем лучше награда. Ничья не считается.
            </p>
            {run ? (
              <div className="run-box">
                <div className="run-wins"><span className="greek-title">{run.wins}</span><small>побед из {t.maxWins}</small></div>
                <div className="run-lives">{Array.from({ length: t.maxLosses }, (_, i) => <span key={i} className={i < t.maxLosses - run.losses ? 'heart on' : 'heart'}>{i < t.maxLosses - run.losses ? '❤️' : '🖤'}</span>)}</div>
                <div className="run-next small"><b>Сейчас:</b> {rewardLine(t.rewards[run.wins])}</div>
              </div>
            ) : (
              <div className="small muted" style={{ fontWeight: 700, marginBottom: 8 }}>
                {t.last ? `Прошлый забег: ${t.last.wins} побед` : 'Вы ещё не играли'}{t.best ? ` · рекорд ${t.best}` : ''}
              </div>
            )}
            {run
              ? <button className="gel xl" disabled={status !== 'online'} onClick={() => onPlay(t.kind)}>⚔ Турнирный бой</button>
              : <button className="gel xl violet" disabled={gems < t.entry} onClick={() => onStart(t.kind)}>Войти за {t.entry} 💎</button>}
            <div className="small" style={{ fontWeight: 900, margin: '14px 0 6px', color: 'var(--heading)' }}>Награды за победы</div>
            <div className="ladder">
              {t.rewards.map((r, i) => (
                <div key={i} className={`ladder-row ${run && run.wins === i ? 'cur' : ''} ${run && run.wins > i ? 'past' : ''}`}>
                  <span className="ladder-n">{i}</span><span className="grow">{rewardLine(r)}</span>
                </div>
              )).reverse()}
            </div>
          </Card>
        );
      })}
      <p className="small center" style={{ color: 'var(--sky-soft)', fontWeight: 700, textShadow: 'var(--sky-shadow)' }}>
        Кристаллы — за задания дня, дорогу трофеев и наборы. Исход турнира решает только сила.
      </p>
    </>
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
