'use client';
import { Glyph, rich } from './Icon.jsx';
import { useEffect, useRef, useState } from 'react';
import {
  CATEGORIES, COSMETICS, PASS_REWARDS, PREMIUM_REWARDS, PREMIUM_XP_MULT, MAX_LEVEL, ACHIEVEMENT_REWARDS, cosmeticName, isGift,
} from '../lib/pass.js';
import { createFx, syntheticPose, celebrate } from '../lib/client/effects.js';
import { music } from '../lib/client/music.js';
import { Avatar, Bar, Card, Seg, plural } from './ui.jsx';
import { roman } from './Greek.jsx';

const CAT_ICON = { skin: '🥷', frame: '💠', trail: '🦴', burst: '💥', victory: '🏆', title: '🏷️', music: '🎵', gift: '🎁' };
const CAT_TONE = {
  avatar: ['#c6e2ff', '#7ab8f5'], skin: ['#ff9fb3', '#e0294f'], frame: ['#5ff6ff', '#2a8fd6'], trail: ['#b49bff', '#6a46d8'],
  burst: ['#ffbe5c', '#f07a12'], victory: ['#ffe27a', '#d99a0b'], title: ['#7ff2a0', '#1fa64a'], music: ['#5ee8d8', '#0b9aa8'], gift: ['#ffe27a', '#b06bff'],
};
const ITEM_EMOJI = {
  skin: { none: '🙂', bones: '💀', robot: '🤖', ninja: '🥷', knight: '🛡️', hero: '🦸', cyber: '👾', flame: '🔥' },
  frame: { none: '⬡', neon: '💜', ice: '🧊', fire: '🔥', gold: '🥇', rainbow: '🌈', galaxy: '🌌', legion: '⚔️', crown: '👑' },
  trail: { classic: '🦴', neon: '💡', fire: '🔥', ice: '❄️', lightning: '⚡', rainbow: '🌈', shadow: '👤', aurora: '🌌' },
  burst: { pop: '➕', sparks: '✨', ring: '💫', confetti: '🎊', emoji: '💪', fireworks: '🎆', meteor: '☄️', stars: '🌠' },
  victory: { none: '·', confetti: '🎉', fireworks: '🎇', goldrain: '🪙', aurora: '🌌' },
  music: { none: '🔇', pulse: '🎧', synthwave: '🌆', chiptune: '👾', dnb: '🥁', epic: '🥁' },
  gift: { 15: '🧸', 25: '🌹', 50: '🎂', 100: '🏆' },
  title: {},
};

export function RewardIcon({ k }) {
  const [cat, id] = k.split(':');
  const [a, b] = CAT_TONE[cat] || CAT_TONE.avatar;
  const emo = cat === 'avatar' ? id : ITEM_EMOJI[cat]?.[id] || CAT_ICON[cat] || '🎁';
  return <span className="reward-ico" style={{ '--t1': a, '--t2': b }}>{emo}</span>;
}

// Примерочная: синтетический человек отжимается со скином, скелетом и всплеском.
function Preview({ skin, trail, burst, victory, frame, avatar, look }) {
  const ref = useRef(null);
  const victoryRef = useRef(null);
  useEffect(() => {
    const fx = createFx(ref.current);
    let raf = 0, wasDown = false;
    const loop = (now) => {
      raf = requestAnimationFrame(loop);
      const { w, h } = fx.size;
      const { pose, phase } = syntheticPose(now / 1000, w, h);
      fx.clear();
      fx.drawPose(pose, trail, now);
      fx.drawSkin(pose, skin, now);
      if (phase > 0.9) wasDown = true;
      if (phase < 0.1 && wasDown) { wasDown = false; fx.burst(burst, (pose.ls.x + pose.rs.x) / 2, pose.ls.y); }
      fx.step(now);
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }, [skin, trail, burst]);
  useEffect(() => {
    if (!victory || victory === 'none') return undefined;
    return celebrate(victoryRef.current, victory);
  }, [victory]);
  return (
    <div style={{ position: 'relative' }}>
      <canvas ref={ref} className="preview" />
      <canvas ref={victoryRef} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', pointerEvents: 'none', borderRadius: 16 }} />
      <div style={{ position: 'absolute', left: 10, top: 14 }}><Avatar emoji={avatar} look={look} frame={frame} size="md" /></div>
    </div>
  );
}

function sourceOf(key) {
  const lvl = Object.entries(PASS_REWARDS).find(([, items]) => items.includes(key))?.[0];
  if (lvl) return `🔒 ур. ${lvl}`;
  const plvl = Object.entries(PREMIUM_REWARDS).find(([, items]) => items.includes(key))?.[0];
  if (plvl) return `🌟 премиум, ур. ${plvl}`;
  const ach = Object.values(ACHIEVEMENT_REWARDS).find((a) => a.items.includes(key));
  if (ach) return `🔒 ${ach.need.toLowerCase()}`;
  return '🔒';
}

const GIFT_STATUS = { ready: 'Забрать', sending: 'Отправляем…', sent: 'Отправлен ✓', failed: 'Повторить', locked: '🔒' };

export default function Pass({ profile, onEquip, onBuy, onClaimGift, buying }) {
  const [cat, setCat] = useState('skin');
  const [victoryPreview, setVictoryPreview] = useState(null);
  const [playing, setPlaying] = useState(null);
  useEffect(() => () => music.stop(), []);
  if (!profile) return <div className="page"><div className="page-head"><h1>Боевой пропуск</h1></div></div>;
  const p = profile;
  const unlocked = new Set(p.unlocked);
  const daysLeft = Math.max(0, Math.ceil((new Date(`${p.season.ends}T23:59:59+03:00`) - Date.now()) / 86400000));
  const gifts = Object.fromEntries((p.gifts || []).map((g) => [g.level, g]));

  const equip = (key) => {
    const [c, id] = key.split(':');
    if (c === 'victory') setVictoryPreview(`${id}:${Date.now()}`);
    onEquip(key);
  };
  const listen = (id) => {
    if (playing === id) { music.stop(); setPlaying(null); return; }
    music.preview(id);
    setPlaying(id);
    setTimeout(() => setPlaying((cur) => (cur === id ? null : cur)), 6000);
  };

  return (
    <div className="page">
      <div className="page-head"><h1>Боевой пропуск</h1><p>{p.season.name} · ещё {daysLeft} {plural(daysLeft, 'день', 'дня', 'дней')} до извержения</p></div>

      <Card title="Задания дня" icon="🎯">
        <div className="rows">
          {p.quests.map((q) => (
            <div key={q.id} className="row">
              <Glyph e={q.done ? '✅' : '⬜'} size={25} />
              <div className="grow">
                <div className="title" style={q.done ? { textDecoration: 'line-through', opacity: 0.6 } : undefined}>{q.text}</div>
                {!q.done && q.goal > 1 && <div style={{ marginTop: 5 }}><Bar value={q.progress} max={q.goal} /></div>}
              </div>
              <span className="chip gold">+{q.xp}</span>
            </div>
          ))}
        </div>
      </Card>

      <Card>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <div className="lvl-num" style={{ width: 62, height: 62, fontSize: 30, color: 'var(--accent-ink)', background: 'var(--accent)', borderColor: 'var(--accent-edge)' }}>{roman(p.level)}</div>
          <div style={{ flex: 1 }}>
            <div style={{ fontWeight: 900, fontSize: 18, color: 'var(--heading)' }}>Уровень {p.level} из {MAX_LEVEL} {p.premium && <span className="chip gold"><Glyph e="🌟" size="1.25em" /> премиум</span>}</div>
            <div className="small muted" style={{ fontWeight: 700, margin: '4px 0 6px' }}>{rich(p.max ? 'Сезон пройден полностью! 👑' : `${p.xpInLevel} / ${p.xpNeed} опыта до следующего`)}</div>
            <Bar value={p.max ? 1 : p.xpInLevel} max={p.max ? 1 : p.xpNeed} tone="gold" />
          </div>
        </div>
        <p className="small" style={{ fontWeight: 600, marginTop: 10 }}>
          Опыт дают отжимания, победы, комбо, серии побед и дней, задания и цель сообщества. Серия дней умножает опыт до ×2.
        </p>
      </Card>

      {!p.premium && (
        <Card className="premium-card" title="Премиум-пропуск" icon="🌟">
          <div className="rows">
            <div className="row"><Glyph e="🎁" size={28} /><div className="grow"><div className="title">Настоящие подарки Telegram</div><div className="sub">на 8, 16, 24 и 30 уровнях — приходят от бота</div></div></div>
            <div className="row"><Glyph e="🦸" size={28} /><div className="grow"><div className="title">Скины Супергерой, Кибер, Дух огня</div><div className="sub">и сияние, звездопад, корона, аватары <Glyph e="🦸" size="1.25em" /> <Glyph e="💎" size="1.25em" /></div></div></div>
            <div className="row"><Glyph e="⚡" size={28} /><div className="grow"><div className="title">+{Math.round((PREMIUM_XP_MULT - 1) * 100)}% опыта</div><div className="sub">до конца сезона, а ещё жетон «Звёздный»</div></div></div>
          </div>
          <button className="gel xl star-btn" style={{ marginTop: 12 }} disabled={buying} onClick={onBuy}>
            {buying ? 'Открываем оплату…' : `Купить за ${p.premiumPrice} ⭐`}
          </button>
          <p className="small muted center" style={{ marginTop: 8, fontWeight: 600 }}>Награды за уже пройденные уровни откроются сразу</p>
        </Card>
      )}

      <Card title="Примерочная" icon="🪞">
        <Preview skin={p.equipped.skin} trail={p.equipped.trail} burst={p.equipped.burst} victory={victoryPreview ? victoryPreview.split(':')[0] : null}
          frame={p.equipped.frame} avatar={p.avatar} look={p.look} key={victoryPreview || 'pv'} />
        <div style={{ marginTop: 12, overflowX: 'auto' }}>
          <Seg value={cat} onChange={setCat} options={[['skin', 'Скин'], ['trail', 'Скелет'], ['burst', 'Повтор'], ['frame', 'Рамка'], ['victory', 'Победа'], ['music', 'Музыка'], ['title', 'Титул']]} />
        </div>
        <div className="items" style={{ marginTop: 12 }}>
          {Object.entries(COSMETICS[cat]).map(([id, item]) => {
            const key = `${cat}:${id}`;
            const own = unlocked.has(key);
            const on = p.equipped[cat] === id;
            return (
              <div key={id} className={`item ${on ? 'on' : ''} ${own ? '' : 'locked'}`} role="button" tabIndex={0} onClick={() => own && equip(key)}>
                {cat === 'frame' ? <Avatar emoji={p.avatar} look={p.look} frame={id} size="sm" /> : <span className="emo">{rich(ITEM_EMOJI[cat]?.[id] || '🏷️')}</span>}
                <span>{item.name}</span>
                <span className="small muted">{on ? 'надето' : own ? 'надеть' : sourceOf(key)}</span>
                {cat === 'music' && id !== 'none' && (
                  <button className="glass-btn" style={{ padding: '4px 10px', fontSize: 12 }} onClick={(e) => { e.stopPropagation(); listen(id); }}>
                    {playing === id ? '⏹ стоп' : '▶ слушать'}
                  </button>
                )}
              </div>
            );
          })}
        </div>
        {cat === 'music' && <p className="small muted" style={{ marginTop: 10, fontWeight: 600 }}>Новые треки открываются за приглашения друзей: 1, 3, 5 и 10 друзей.</p>}
      </Card>

      <Card title="Награды сезона" icon="🎁">
        <div className="small muted" style={{ fontWeight: 700, display: 'flex', justifyContent: 'space-between', margin: '-4px 0 8px 48px' }}>
          <span>Бесплатно</span><span><Glyph e="🌟" size="1.25em" /> Премиум</span>
        </div>
        <div className="track">
          {Array.from({ length: MAX_LEVEL }, (_, i) => i + 1).map((lvl) => {
            const free = PASS_REWARDS[lvl] || [];
            const prem = PREMIUM_REWARDS[lvl] || [];
            if (!free.length && !prem.length) return null;
            const got = p.level >= lvl;
            const cur = p.level + 1 === lvl;
            const gift = gifts[lvl];
            return (
              <div key={lvl} className={`lvl ${got ? 'got' : ''} ${cur ? 'cur' : ''}`}>
                <div className="lvl-num">{lvl}</div>
                <div style={{ flex: 1, display: 'grid', gridTemplateColumns: free.length && prem.length ? '1fr 1fr' : '1fr', gap: 6 }}>
                  {free.length > 0 && (
                    <div className="row" style={{ minWidth: 0 }}>
                      <RewardIcon k={free[0]} />
                      <div className="grow"><div className="title">{free.map(cosmeticName).join(' + ')}</div><div className="sub">{CATEGORIES[free[0].split(':')[0]]}</div></div>
                    </div>
                  )}
                  {prem.length > 0 && (
                    <div className={`row prem ${p.premium ? '' : 'locked'}`} style={{ minWidth: 0 }}>
                      <RewardIcon k={prem[0]} />
                      <div className="grow">
                        <div className="title">{prem.map(cosmeticName).join(' + ')}</div>
                        {isGift(prem[0]) && p.premium && got ? (
                          <button className="glass-btn" style={{ padding: '3px 10px', fontSize: 12, marginTop: 3 }}
                            disabled={!gift || !['ready', 'failed'].includes(gift.status)} onClick={() => onClaimGift(lvl)}>
                            {GIFT_STATUS[gift?.status || 'locked']}
                          </button>
                        ) : <div className="sub">{CATEGORIES[prem[0].split(':')[0]]}</div>}
                        {gift?.status === 'failed' && <div className="sub" style={{ color: 'var(--rose2)' }}>{gift.error}</div>}
                      </div>
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </Card>
    </div>
  );
}
