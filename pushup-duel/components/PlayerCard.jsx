'use client';
// Карточка игрока с жетонами и интро «VS» перед матчем.
import { useEffect, useState } from 'react';
import { badgeById, RARITY, RARITY_ORDER } from '../lib/pass.js';
import { sfx } from '../lib/client/sound.js';
import { haptic } from '../lib/client/tg.js';
import { Avatar } from './ui.jsx';
import { Sticker } from './Stickers.jsx';
import { ARENAS } from '../lib/economy.js';

// Редкость карточки — по самому редкому жетону; премиум — всегда легендарная рамка.
function cardRarity(player) {
  if (player.premium) return 'legend';
  const rs = (player.badges || []).map((id) => badgeById(id)?.rarity).filter(Boolean);
  return RARITY_ORDER.find((r) => rs.includes(r)) || 'common';
}

export function Badge({ id, delay = 0, size = 'md' }) {
  const b = badgeById(id);
  if (!b) return null;
  return (
    <span className={`badge ${b.rarity} ${size}`} style={{ '--rc': RARITY[b.rarity].color, animationDelay: `${delay}ms` }} title={`${b.name} — ${b.need}`}>
      <span className="badge-ico">{b.icon}</span>
    </span>
  );
}

export function PlayerCard({ player, side = '', bot = false, animate = false }) {
  const rarity = bot ? 'common' : cardRarity(player);
  return (
    <div className={`pcard r-${rarity} ${side} ${animate ? 'enter' : ''}`} style={{ '--rc': RARITY[rarity].color }}>
      <div className="pcard-holo" />
      <div className="pcard-top">
        <span>{bot ? 'БОТ' : (player.rank || '').toUpperCase()}</span>
        <span>{player.premium ? '🌟' : ''} ур. {player.level ?? 1}</span>
      </div>
      <div className="pcard-ava"><Avatar emoji={player.avatar} frame={player.frame} size="xl" /></div>
      <div className="pcard-name">{player.name}</div>
      <div className="pcard-title">{bot ? 'Тренировочный соперник' : player.title || 'Новобранец'}</div>
      <div className="pcard-elo"><b>{player.rating}</b> Эло</div>
      {player.clan && <div className="pcard-clan">{player.clan.emoji} {player.clan.name}</div>}
      <div className="pcard-badges">
        {(player.badges || []).length
          ? player.badges.map((id, i) => <Badge key={id} id={id} delay={animate ? 1500 + i * 220 : 0} />)
          : <span className="pcard-nobadge">жетонов пока нет</span>}
      </div>
      {!bot && player.games > 0 && <div className="pcard-stats">{player.wins} побед · рекорд {player.bestReps}</div>}
      {player.belt && <div className="pcard-belt">🥊 Чемпион · {ARENAS.find((a) => a.id === player.belt)?.name}</div>}
      {(player.card || []).map((it, i) => (
        <span key={i} className="pcard-stk" style={{ left: `${it.x * 100}%`, top: `${it.y * 100}%`, transform: `translate(-50%, -50%) rotate(${it.r}deg) scale(${it.s})`, animationDelay: animate ? `${1700 + i * 140}ms` : undefined }}>
          <Sticker id={it.id} size={40} />
        </span>
      ))}
    </div>
  );
}

// Интро перед боем: карточки вылетают навстречу, удар «VS», жетоны переворачиваются по очереди.
export function VersusIntro({ me, opp, bot, mode, belt, onDone }) {
  const [out, setOut] = useState(false);
  // Время интро считаем по отрисованным кадрам (шаг не больше 50 мс): если страница
  // подвиснет, интро не «проскочит», а дождётся и доиграет.
  useEffect(() => {
    sfx.whoosh();
    let raf = 0, last = performance.now(), t = 0, hit = false, faded = false;
    const tick = (now) => {
      t += Math.min(50, now - last);
      last = now;
      if (!hit && t >= 900) { hit = true; sfx.impact(); haptic('heavy'); }
      if (!faded && t >= 3700) { faded = true; setOut(true); }
      if (t >= 4200) { onDone(); return; }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [onDone]);
  return (
    <div className={`versus ${out ? 'out' : ''}`} onClick={onDone}>
      <div className="versus-rays" />
      <div className="versus-flash" />
      <div className="versus-tag">{belt ? `🥊 ТИТУЛЬНЫЙ БОЙ · ${ARENAS.find((a) => a.id === belt)?.name?.toUpperCase()}` : { ranked: 'РЕЙТИНГОВЫЙ МАТЧ', friend: 'ДУЭЛЬ С ДРУГОМ', bot: 'БОЙ С БОТОМ', tournament: '🎟️ ТУРНИР НЕДЕЛИ', title: '🥊 ТИТУЛЬНЫЙ БОЙ' }[mode]}</div>
      <div className="versus-cards">
        <PlayerCard player={me} side="left" animate />
        <div className="versus-vs">VS</div>
        <PlayerCard player={opp} side="right" bot={bot} animate />
      </div>
      <div className="versus-hint">Нажмите, чтобы пропустить</div>
    </div>
  );
}
