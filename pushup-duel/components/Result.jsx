'use client';
import { useEffect, useRef, useState } from 'react';
import { celebrate } from '../lib/client/effects.js';
import { sfx } from '../lib/client/sound.js';
import { haptic, bindBackButton } from '../lib/client/tg.js';
import { cosmeticName, levelInfo } from '../lib/pass.js';
import { Avatar, Bar, Card, fmtTime, plural } from './ui.jsx';
import { RewardIcon } from './Pass.jsx';
import { PackIcon } from './Stickers.jsx';
import { BeltIcon } from './Compete.jsx';
import { PACKS, ARENAS } from '../lib/economy.js';

const TITLES = { win: 'Победа!', loss: 'Поражение', draw: 'Ничья' };
const REASONS = { forfeit: 'соперник сдался', disconnect: 'соперник отключился' };

export default function Result({ data, profile, onAgain, onHome, onShare, onEquip }) {
  const canvasRef = useRef(null);
  const { mode, outcome, me, opp, progress } = data;
  const training = mode === 'training';
  const [shownLevel, setShownLevel] = useState(() => levelInfo(Math.max(0, (profile?.xp || 0) - (progress?.total || 0))));

  useEffect(() => bindBackButton(onHome), [onHome]);

  useEffect(() => {
    const won = outcome === 'win';
    if (won) { sfx.win(); haptic('success'); } else if (outcome === 'loss') { sfx.lose(); haptic('error'); }
    const kind = won ? (profile?.equipped?.victory && profile.equipped.victory !== 'none' ? profile.equipped.victory : 'confetti') : null;
    const stop = kind ? celebrate(canvasRef.current, kind) : () => {};
    const t = setTimeout(() => {
      if (profile) setShownLevel(levelInfo(profile.xp));
      if (progress && progress.levelAfter > progress.levelBefore) { sfx.level(); haptic('success'); }
    }, 900);
    return () => { stop(); clearTimeout(t); };
  }, [outcome, profile, progress]);

  // Если игрок сменил стиль победы прямо отсюда — эффекта не перезапускаем.
  const title = training ? 'Тренировка завершена' : TITLES[outcome] || 'Матч окончен';
  const sub = [
    { ranked: 'Рейтинговая дуэль', friend: 'Дуэль с другом', bot: 'Бой с ботом', training: 'Тренировка', tournament: 'Турнир', title: 'Титульный бой', spar: 'Спарринг в клане', war: 'Бой войны кланов' }[mode],
    fmtTime(data.durationMs),
    REASONS[data.reason],
  ].filter(Boolean).join(' · ');

  return (
    <div className="result">
      <canvas ref={canvasRef} className="fx" />
      <div>
        <div className={`res-title ${outcome === 'loss' ? 'loss' : ''}`}>{title}</div>
        <div className="center" style={{ color: 'var(--sky-soft)', fontWeight: 700, textShadow: 'var(--sky-shadow)' }}>{sub}</div>
      </div>

      <Card>
        {opp ? (
          <div className="res-sides">
            <div>
              <Avatar emoji={profile?.avatar} frame={profile?.frame} size="lg" side="me" />
              <div style={{ fontWeight: 800, marginTop: 6 }}>{profile?.name}</div>
              <div className="num me">{me.reps}</div>
              {mode === 'ranked' && (
                <div className={`chip ${me.delta + me.streakBonus >= 0 ? 'green' : 'red'}`} style={{ marginTop: 6 }}>
                  {me.rating} Эло · {me.delta >= 0 ? '+' : ''}{me.delta}{me.streakBonus ? ` +${me.streakBonus} 🔥` : ''}
                </div>
              )}
            </div>
            <div style={{ fontWeight: 900, fontSize: 22, fontStyle: 'italic', color: 'var(--soft)' }}>VS</div>
            <div>
              <Avatar emoji={opp.avatar} frame={opp.frame} size="lg" side="opp" />
              <div style={{ fontWeight: 800, marginTop: 6 }}>{opp.name}</div>
              <div className="num opp">{opp.reps}</div>
              {mode === 'ranked' && <div className="chip" style={{ marginTop: 6 }}>{opp.ratingBefore + opp.delta} Эло</div>}
              {mode === 'bot' && <div className="chip" style={{ marginTop: 6 }}>бот</div>}
            </div>
          </div>
        ) : (
          <div className="center">
            <div className="big-num" style={{ fontSize: 64 }}>{me.reps}</div>
            <div className="muted" style={{ fontWeight: 700 }}>отжиманий за {fmtTime(data.durationMs)}</div>
          </div>
        )}
      </Card>

      {(data.reward || data.belt || data.run || data.war) && (
        <Card>
          <div className="rows">
            {data.belt?.won && <div className="row new-reward" style={{ border: 0 }}><BeltIcon arena={ARENAS.find((a) => a.id === data.belt.won)} size={56} /><div className="grow"><div className="title">Пояс чемпиона ваш!</div><div className="sub">Арена «{ARENAS.find((a) => a.id === data.belt.won).name}» — удержите его до понедельника</div></div></div>}
            {data.belt?.defended && <div className="row"><span style={{ fontSize: 26 }}>🛡️</span><div className="grow"><div className="title">Пояс защищён</div><div className="sub">Ещё одна защита титула</div></div></div>}
            {data.belt?.lost && <div className="row"><span style={{ fontSize: 26 }}>💔</span><div className="grow"><div className="title">Пояс потерян</div><div className="sub">Отберите его обратно в рейтинге или вызовом</div></div></div>}
            {data.reward?.type && <div className="row new-reward" style={{ border: 0 }}><PackIcon type={data.reward.type} size={48} className="bounce" /><div className="grow"><div className="title">{PACKS[data.reward.type].name}!</div><div className="sub">Ждёт на главной в слоте {data.reward.slot + 1}</div></div></div>}
            {data.reward?.full && <div className="row"><span style={{ fontSize: 26 }}>🎴</span><div className="grow"><div className="title">Все слоты наборов заняты</div><div className="sub">Откройте набор, чтобы получать новые за победы</div></div></div>}
            {data.run && (
              <div className="row">
                <span style={{ fontSize: 26 }}>{data.run.done ? '🏁' : '🏅'}</span>
                <div className="grow">
                  <div className="title">{data.run.kind === 'big' ? 'Большой' : 'Малый'} турнир: {data.run.wins} {plural(data.run.wins, 'победа', 'победы', 'побед')} {'❤️'.repeat(Math.max(0, data.run.maxLosses - data.run.losses))}{'🖤'.repeat(Math.min(data.run.maxLosses, data.run.losses))}</div>
                  <div className="sub">{data.run.done ? 'Забег окончен — награда в почте на главной 📬' : `Ещё ${data.run.maxLosses - data.run.losses} ${plural(data.run.maxLosses - data.run.losses, 'поражение', 'поражения', 'поражений')} до конца забега`}</div>
                </div>
              </div>
            )}
            {data.war && <div className="row"><span style={{ fontSize: 26 }}>⚔️</span><div className="grow"><div className="title">+{data.war.points} очков вашему клану</div><div className="sub">Победа в бою войны</div></div></div>}
          </div>
        </Card>
      )}

      {progress ? (
        <Card title={`+${progress.total} опыта`} icon="⭐">
          <div>
            {progress.items.map((it, i) => (
              <div key={i} className="xp-line" style={{ animationDelay: `${i * 120}ms` }}><span>{it.label}</span><b>+{it.xp}</b></div>
            ))}
          </div>
          <div style={{ marginTop: 12 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontWeight: 800, marginBottom: 6 }}>
              <span>Уровень {shownLevel.level}{progress.levelAfter > progress.levelBefore ? ' 🎉' : ''}</span>
              <span className="muted small">{shownLevel.max ? 'максимум' : `${shownLevel.xpInLevel} / ${shownLevel.xpNeed}`}</span>
            </div>
            <Bar value={shownLevel.max ? 1 : shownLevel.xpInLevel} max={shownLevel.max ? 1 : shownLevel.xpNeed} tone="gold" />
          </div>
          {progress.newRewards?.length > 0 && (
            <div className="rows" style={{ marginTop: 12 }}>
              {progress.newRewards.map((k) => (
                <button key={k} className="new-reward" onClick={() => onEquip(k)} style={{ border: 0, textAlign: 'left' }}>
                  <RewardIcon k={k} /><span style={{ flex: 1 }}>Новая награда: {cosmeticName(k)}</span><span className="chip gold">Надеть</span>
                </button>
              ))}
            </div>
          )}
        </Card>
      ) : data.error ? (
        <Card><div className="empty">Опыт не начислен: {data.error}</div></Card>
      ) : null}

      <div className="rows">
        <button className="gel xl" onClick={onAgain}>
          {mode === 'ranked' ? '⚔️ Новый соперник' : mode === 'tournament' ? (data.run && !data.run.done ? '🏅 Следующий бой турнира' : '🏆 К турнирам') : mode === 'spar' ? '🤼 Ещё спарринг' : mode === 'war' ? '⚔️ Ещё бой войны' : mode === 'friend' || mode === 'title' ? '🔁 Реванш' : mode === 'bot' ? '🤖 Ещё бой' : '🏋️ Ещё подход'}
        </button>
        <div className="row-actions">
          <button className="glass-btn" onClick={onShare}>📤 Поделиться</button>
          <button className="glass-btn" onClick={onHome}>🏠 На главную</button>
        </div>
      </div>
    </div>
  );
}
