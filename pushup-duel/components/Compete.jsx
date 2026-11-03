'use client';
import { Glyph, rich } from './Icon.jsx';
// Пояса чемпионов, турниры и почта с наградами.
import { useEffect, useState } from 'react';
import { PACKS } from '../lib/economy.js';
import { stickerById } from '../lib/stickers.js';
import { api } from '../lib/client/net.js';
import { Avatar, Card, plural } from './ui.jsx';
import { PackIcon, fmtLeft } from './Stickers.jsx';
import { Icon } from './Icon.jsx';

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
    <Card title="Пояса чемпионов" icon="🥊">
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
                  <Avatar emoji={b.holder.avatar} look={b.holder.look} frame={b.holder.frame} size="sm" /> {b.holder.name}{b.mine ? ' (вы)' : ''} · {days(b.since)} {plural(days(b.since), 'день', 'дня', 'дней')} · защит: {b.defenses}
                </button>
              ) : <div className="sub">Свободен — выиграйте рейтинговый бой на этой арене</div>}
              <div className="sub">За неделю: <Glyph e="💎" size="1.25em" /> {b.weekly.gems} + {PACKS[b.weekly.pack].name.toLowerCase()}</div>
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

// Картинка турнира — плоская заглушка, пока нет нарисованного арта.
// Малый: палестра с венком. Большой: стадион с факелами и кубком.
function TourneyArt({ kind }) {
  const big = kind === 'big';
  const col = (x, h = 64) => (
    <g key={x}>
      <rect x={x} y={118 - h} width="12" height={h} fill="var(--card)" stroke="var(--accent)" strokeWidth="2" />
      <rect x={x - 3} y={118 - h - 6} width="18" height="6" fill="var(--accent)" />
      <path d={`M${x + 6} ${122 - h} V114`} stroke="var(--accent)" strokeWidth="1" opacity=".5" />
    </g>
  );
  const torch = (x) => (
    <g key={`t${x}`} transform={`translate(${x} 72)`}>
      <rect x="-3" y="0" width="6" height="46" fill="var(--accent)" /><path d="M-10 0 h20 l-4 -7 h-12 z" fill="var(--accent)" />
      <path d="M0 -30 C9 -19 7 -12 0 -7 C-7 -12 -9 -19 0 -30 Z" fill="#e2531d" /><path d="M0 -21 C4 -16 4 -12 0 -10 C-4 -12 -4 -16 0 -21 Z" fill="#f2c14e" />
    </g>
  );
  return (
    <svg viewBox="0 0 360 150" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
      <rect width="360" height="150" fill="var(--volc-sky1)" />
      {big
        ? [0, 1, 2, 3].map((i) => <path key={i} d={`M${14 + i * 10} ${100 - i * 12} H${346 - i * 10}`} stroke="var(--accent)" strokeWidth="5" opacity={0.85 - i * 0.18} />)
        : <circle cx="300" cy="34" r="16" fill="var(--card)" stroke="var(--accent)" strokeWidth="2" />}
      {big ? [torch(40), torch(320)] : [col(30), col(58, 54), col(290, 54), col(318)]}
      <rect y="118" width="360" height="32" fill="var(--volc-ground)" /><path d="M0 118 H360" stroke="var(--accent)" strokeWidth="2" />
      <g transform={big ? 'translate(136 22) scale(1.8)' : 'translate(142 26) scale(1.6)'}><Icon name={big ? 'trophy' : 'medal'} size={48} /></g>
      <g transform="translate(96 72) scale(1.1)"><Icon name="laurel" size={48} /></g>
      <g transform="translate(212 72) scale(-1.1 1.1) translate(-48 0)"><Icon name="laurel" size={48} /></g>
    </svg>
  );
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
          <section key={t.kind} className="card tourney-card">
            <div className="tourney-art"><TourneyArt kind={t.kind} /><span className="t-name">{t.name}</span></div>
            <div className="tourney-body">
              <p className="tourney-rules">
                Играйте, пока не наберёте {t.maxLosses} поражения или {t.maxWins} {plural(t.maxWins, 'победу', 'победы', 'побед')}. Чем больше побед, тем лучше награда. Ничья не считается.
              </p>
              {run ? (
                <>
                  <div className="run-box">
                    <div className="run-wins"><span className="greek-title">{run.wins}</span><small>побед из {t.maxWins}</small></div>
                    <div className="run-lives">{Array.from({ length: t.maxLosses }, (_, i) => <span key={i} className={i < t.maxLosses - run.losses ? 'heart on' : 'heart'} />)}</div>
                    <div className="run-next small"><b>Сейчас:</b> {rich(rewardLine(t.rewards[run.wins]))}</div>
                  </div>
                  <button className="gel xl" disabled={status !== 'online'} onClick={() => onPlay(t.kind)}><Icon name="swords" size={26} /> Турнирный бой</button>
                </>
              ) : (
                <>
                  <div className="small muted center" style={{ fontWeight: 700, marginBottom: 6 }}>
                    {t.last ? `Прошлый забег: ${t.last.wins} побед` : 'Вы ещё не играли'}{t.best ? ` · рекорд ${t.best}` : ''}
                  </div>
                  <div className="entry-label">Войти за</div>
                  <button className="gel xl entry-btn" disabled={gems < t.entry} onClick={() => onStart(t.kind)}>{t.entry} <Icon name="gem" size={30} /></button>
                  {gems < t.entry && <div className="entry-short">Не хватает {t.entry - gems} 💎</div>}
                </>
              )}
              <div className="small" style={{ fontWeight: 900, margin: '14px 0 6px', color: 'var(--heading)' }}>Награды за победы</div>
              <div className="ladder">
                {t.rewards.map((r, i) => (
                  <div key={i} className={`ladder-row ${run && run.wins === i ? 'cur' : ''} ${run && run.wins > i ? 'past' : ''}`}>
                    <span className="ladder-n">{i}</span><span className="grow">{rich(rewardLine(r))}</span>
                  </div>
                )).reverse()}
              </div>
            </div>
          </section>
        );
      })}
      <p className="small center muted" style={{ fontWeight: 700 }}>
        Кристаллы — за задания дня, дорогу трофеев и наборы. Исход турнира решает только сила.
      </p>
    </>
  );
}

export function InboxCard({ eco, onClaim }) {
  if (!eco?.inbox?.length) return null;
  return (
    <Card title="Награды" icon="📬">
      <div className="rows">
        {eco.inbox.map((it) => (
          <div key={it.id} className="row">
            {it.pack ? <PackIcon type={it.pack} size={34} /> : <Glyph e={it.gift ? '🎁' : '🏅'} size={30} />}
            <div className="grow">
              <div className="title">{it.title}</div>
              <div className="sub">{rich([it.gift && `подарок Telegram ${it.gift} ★`, it.pack && PACKS[it.pack].name.toLowerCase(), it.gems && `💎 ${it.gems}`, it.sticker && `наклейка «${stickerById(it.sticker)?.name}»`].filter(Boolean).join(' · '))}</div>
            </div>
            <button className="gel green" style={{ padding: '6px 14px', fontSize: 13 }} onClick={() => onClaim(it.id)}>{it.gift ? 'Отправить' : 'Забрать'}</button>
          </div>
        ))}
      </div>
    </Card>
  );
}
