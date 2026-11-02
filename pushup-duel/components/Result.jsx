'use client';
import { useEffect, useRef, useState } from 'react';
import { celebrate } from '../lib/client/effects.js';
import { sfx } from '../lib/client/sound.js';
import { haptic, bindBackButton } from '../lib/client/tg.js';
import { cosmeticName, levelInfo } from '../lib/pass.js';
import { Avatar, Bar, Card, fmtTime } from './ui.jsx';
import { RewardIcon } from './Pass.jsx';

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
    { ranked: 'Рейтинговая дуэль', friend: 'Дуэль с другом', bot: 'Бой с ботом', training: 'Тренировка' }[mode],
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

      <Card wave={outcome === 'loss' ? ['#ff9fb3', '#ffbe5c'] : undefined}>
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

      {progress ? (
        <Card title={`+${progress.total} опыта`} icon="⭐" tone="gold">
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
          {mode === 'ranked' ? '⚔️ Новый соперник' : mode === 'friend' ? '🔁 Реванш' : mode === 'bot' ? '🤖 Ещё бой' : '🏋️ Ещё подход'}
        </button>
        <div className="row-actions">
          <button className="glass-btn" onClick={onShare}>📤 Поделиться</button>
          <button className="glass-btn" onClick={onHome}>🏠 На главную</button>
        </div>
      </div>
    </div>
  );
}
