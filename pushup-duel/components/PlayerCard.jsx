'use client';
import { Glyph, rich } from './Icon.jsx';
// Карточка игрока с жетонами и интро «VS» перед матчем.
import { useEffect, useState } from 'react';
import { badgeById, RARITY, RARITY_ORDER } from '../lib/pass.js';
import { sfx } from '../lib/client/sound.js';
import { haptic } from '../lib/client/tg.js';
import { Avatar } from './ui.jsx';
import { Sticker } from './Stickers.jsx';
import { ARENAS, arenaOf } from '../lib/economy.js';
import { roman } from './Greek.jsx';
import { Crest } from './Emblem.jsx';

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

// Горизонтальная карточка игрока: слева аватар, справа имя, титул, рейтинг и клан, внизу жетоны.
// Наклейки лежат поверх всей карточки (координаты — доли ширины и высоты).
export function PlayerCard({ player, side = '', bot = false, animate = false }) {
  const rarity = bot ? 'common' : cardRarity(player);
  return (
    <div className={`pcard h r-${rarity} ${side} ${animate ? 'enter' : ''}`} style={{ '--rc': RARITY[rarity].color }}>
      <div className="pcard-holo" />
      <div className="pcard-left">
        <div className="pcard-ava"><Avatar emoji={player.avatar} look={player.look} frame={player.frame} size="lg" /></div>
        <div className="pcard-lvl">{player.premium && <Glyph e="🌟" size="1.2em" />} ур. {player.level ?? 1}</div>
      </div>
      <div className="pcard-right">
        <div className="pcard-top"><span>{bot ? 'БОТ' : (player.rank || '').toUpperCase()}</span>{player.belt && <span className="pcard-belt"><Glyph e="🥊" size="1.25em" /> {ARENAS.find((a) => a.id === player.belt)?.name}</span>}</div>
        <div className="pcard-name">{player.name}</div>
        <div className="pcard-title">{bot ? 'Тренировочный соперник' : player.title || 'Новобранец'}</div>
        <div className="pcard-line">
          <span className="pcard-elo"><b>{player.rating}</b> Эло</span>
          {player.clan && <span className="pcard-clan"><Crest crest={player.clan.crest} size={16} /> {player.clan.name}</span>}
        </div>
        <div className="pcard-badges">
          {(player.badges || []).length
            ? player.badges.map((id, i) => <Badge key={id} id={id} delay={animate ? 1300 + i * 200 : 0} />)
            : <span className="pcard-nobadge">жетонов пока нет</span>}
          {!bot && player.games > 0 && <span className="pcard-stats">{player.wins} побед · рекорд {player.bestReps}</span>}
        </div>
      </div>
      {(player.card || []).map((it, i) => (
        <span key={i} className="pcard-stk" style={{ left: `${it.x * 100}%`, top: `${it.y * 100}%`, transform: `translate(-50%, -50%) rotate(${it.r}deg) scale(${it.s})`, animationDelay: animate ? `${1500 + i * 140}ms` : undefined }}>
          <Sticker id={it.id} size={38} />
        </span>
      ))}
    </div>
  );
}

// Интро перед боем: верхняя карточка влетает слева, нижняя справа, удар «VS»,
// потом каждая уносится дальше по ходу движения — верхняя вправо, нижняя влево.
export function VersusIntro({ me, opp, bot, mode, belt, onDone }) {
  const [phase, setPhase] = useState('in');
  // Время считаем по отрисованным кадрам (шаг не больше 50 мс): если страница подвиснет,
  // интро не «проскочит», а доиграет.
  useEffect(() => {
    sfx.whoosh();
    let raf = 0, last = performance.now(), t = 0, hit = false, leaving = false;
    const tick = (now) => {
      t += Math.min(50, now - last);
      last = now;
      if (!hit && t >= 850) { hit = true; sfx.impact(); haptic('heavy'); }
      if (!leaving && t >= 3300) { leaving = true; setPhase('leave'); sfx.whoosh(); }
      if (t >= 4100) { onDone(); return; }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [onDone]);
  return (
    <div className={`versus ${phase === 'leave' ? 'leaving' : ''}`} onClick={onDone}>
      <div className="versus-rays" />
      <div className="versus-flash" />
      <div className="versus-streaks"><i /><i /><i /><i /><i /><i /></div>
      <div className="versus-tag">{rich(belt ? `🥊 ТИТУЛЬНЫЙ БОЙ · ${ARENAS.find((a) => a.id === belt)?.name?.toUpperCase()}` : { ranked: 'РЕЙТИНГОВЫЙ МАТЧ', friend: 'ДУЭЛЬ С ДРУГОМ', bot: 'БОЙ С БОТОМ', tournament: '🏆 ТУРНИР', title: '🥊 ТИТУЛЬНЫЙ БОЙ', spar: '🤼 СПАРРИНГ В КЛАНЕ', war: '⚔️ ВОЙНА КЛАНОВ' }[mode])}</div>
      <div className="versus-stack">
        <PlayerCard player={me} side="from-left" animate />
        <div className="versus-vs">VS</div>
        <PlayerCard player={opp} side="from-right" bot={bot} animate />
      </div>
      <div className="versus-meta">{(() => { const a = arenaOf(Math.max(me.rating || 0, opp.rating || 0)); return `Арена ${roman(a.id)} · ${a.name}`; })()}<small>Больше отжиманий — победа</small></div>
      <div className="versus-hint">Нажмите, чтобы пропустить</div>
    </div>
  );
}
