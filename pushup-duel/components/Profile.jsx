'use client';
import { Glyph, rich } from './Icon.jsx';
import { useState } from 'react';
import { cosmeticName, REFERRAL_XP, BADGES, RARITY } from '../lib/pass.js';
import { PlayerCard, Badge } from './PlayerCard.jsx';
import { Album } from './Stickers.jsx';
import { Avatar, Card, Seg, ago, fmtTime } from './ui.jsx';
import { RewardIcon } from './Pass.jsx';
import { Crest } from './Emblem.jsx';

const OUT = { win: ['Победа', 'green'], loss: ['Поражение', 'red'], draw: ['Ничья', ''] };
const MODE = { ranked: '⚔️', friend: '🤝', bot: '🤖', training: '🏋️' };

export default function Profile({ profile, settings, updateSettings, onSave, onInvite, inTg, onEditCard, onEditAvatar }) {
  const [name, setName] = useState(profile?.name || '');
  if (!profile) return <div className="page"><div className="page-head"><h1>Профиль</h1></div></div>;
  const p = profile;

  return (
    <div className="page">
      <Card>
        <div style={{ display: 'flex', gap: 14, alignItems: 'center' }}>
          <button style={{ border: 0, background: 'none', padding: 0 }} onClick={onEditAvatar} aria-label="Изменить аватар"><Avatar emoji={p.avatar} look={p.look} frame={p.equipped.frame} size="lg" /></button>
          <div style={{ minWidth: 0, flex: 1 }}>
            <div style={{ fontSize: 22, fontWeight: 900, color: 'var(--heading)' }}>{p.name}</div>
            <div className="small" style={{ fontWeight: 700 }}>{p.title}</div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 6 }}>
              <span className="chip gold">{p.rank} · {p.rating}</span>
              <span className="chip">ур. {p.level}</span>
              {p.clan && <span className="chip"><Crest crest={p.clan.crest} size={16} /> {p.clan.name}</span>}
            </div>
          </div>
        </div>
        <div className="stat-grid" style={{ marginTop: 14 }}>
          <div className="stat"><b>{p.games}</b><span>дуэлей</span></div>
          <div className="stat"><b>{p.games ? Math.round((p.wins / p.games) * 100) : 0}%</b><span>побед</span></div>
          <div className="stat"><b>{p.bestCombo}</b><span>комбо</span></div>
          <div className="stat"><b>{p.bestWinStreak}</b><span>серия</span></div>
        </div>
      </Card>

      <Card title="Моя карточка" icon="🃏">
        <p className="small" style={{ fontWeight: 600, marginBottom: 12 }}>Её видит соперник в интро перед боем. Украсьте наклейками и выберите до трёх жетонов для витрины.</p>
        <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 12 }}><PlayerCard player={p} /></div>
        <button className="gel wide violet" style={{ marginBottom: 14 }} onClick={onEditCard}><Glyph e="✨" size="1.25em" /> Наклейки на карточке</button>
        <div className="rows">
          {BADGES.map((b) => {
            const own = p.earnedBadges.includes(b.id);
            const chosen = p.badges.includes(b.id);
            const toggle = () => {
              if (!own) return;
              const cur = p.chosenBadges?.length ? p.chosenBadges : p.badges;
              const next = cur.includes(b.id) ? cur.filter((x) => x !== b.id) : [...cur, b.id].slice(-3);
              onSave({ badges: next });
            };
            return (
              <button key={b.id} className={`row ${chosen ? 'me' : ''}`} style={{ textAlign: 'left', width: '100%', opacity: own ? 1 : 0.7, filter: own ? 'none' : 'grayscale(1)' }} onClick={toggle}>
                <Badge id={b.id} />
                <div className="grow"><div className="title">{b.name}</div><div className="sub">{b.need}</div></div>
                <span className="chip" style={{ color: RARITY[b.rarity].color, borderColor: RARITY[b.rarity].color }}>{rich(own ? (chosen ? '✓ в витрине' : RARITY[b.rarity].name) : '🔒')}</span>
              </button>
            );
          })}
        </div>
      </Card>

      <Album eco={p.eco} />

      <Card title="Пригласить друзей" icon="🎁">
        <p style={{ fontWeight: 600 }}>За каждого друга — <b>+{REFERRAL_XP.inviter} опыта</b>, другу — +{REFERRAL_XP.invitee}. И музыка для боя:</p>
        <div className="rows" style={{ marginTop: 8 }}>
          {[[1, '🌆 Синтвейв + аватар 🤝'], [3, '👾 8-бит + титул «Вербовщик»'], [5, '🥁 Драм-н-бейс'], [10, '⚔️ Эпик + рамка «Легион»']].map(([n, text]) => (
            <div key={n} className="row" style={{ opacity: p.invites >= n ? 1 : 0.7 }}>
              <span className="chip">{n} {n === 1 ? 'друг' : n < 5 ? 'друга' : 'друзей'}</span>
              <div className="grow"><div className="title">{text}</div></div>
              <span>{rich(p.invites >= n ? '✅' : '🔒')}</span>
            </div>
          ))}
        </div>
        <div className="row" style={{ margin: '10px 0' }}>
          <Glyph e="👥" size={28} />
          <div className="grow"><div className="title">Приглашено: {p.invites}</div><div className="sub">Друзей в PushDuel: {p.friends}</div></div>
        </div>
        <button className="gel wide green" onClick={onInvite}><Glyph e="📨" size="1.25em" /> Позвать друга</button>
      </Card>

      <Card title="Достижения" icon="🏅">
        <div className="rows">
          {p.achievements.map((a) => (
            <div key={a.id} className="row" style={a.done ? undefined : { opacity: 0.6 }}>
              <RewardIcon k={a.items[0]} />
              <div className="grow"><div className="title">{a.need}</div><div className="sub">{a.items.map(cosmeticName).join(', ')}</div></div>
              <span>{rich(a.done ? '✅' : '🔒')}</span>
            </div>
          ))}
        </div>
      </Card>

      <Card title="Имя и аватар" icon="🎭">
        <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
          <input className="input" maxLength={16} value={name} onChange={(e) => setName(e.target.value)} />
          <button className="gel" disabled={name.trim() === p.name || name.trim().length < 2} onClick={() => onSave({ name: name.trim() })}>OK</button>
        </div>
        <button className="row" style={{ width: '100%', textAlign: 'left' }} onClick={onEditAvatar}>
          <Avatar emoji={p.avatar} look={p.look} frame={p.equipped.frame} size="md" />
          <div className="grow"><div className="title">Конструктор аватара</div><div className="sub">Фигура, форма, окантовка и цвета</div></div>
          <span className="chip">Изменить</span>
        </button>
      </Card>

      <Card title="История" icon="📜">
        {!p.history.length ? <div className="empty">Сыграйте первую дуэль</div> : (
          <div className="rows">
            {p.history.slice(0, 15).map((h, i) => (
              <div key={i} className="row">
                <span style={{ fontSize: 20 }}>{MODE[h.mode]}</span>
                <div className="grow">
                  <div className="title">{h.opp ? `${h.reps} : ${h.oppReps} — ${h.opp}` : `${h.reps} отжиманий`}</div>
                  <div className="sub">{ago(h.at)} назад · {fmtTime(h.durationMs)}{h.delta ? ` · ${h.delta > 0 ? '+' : ''}${h.delta} Эло` : ''}</div>
                </div>
                {h.outcome && <span className={`chip ${OUT[h.outcome][1]}`}>{OUT[h.outcome][0]}</span>}
              </div>
            ))}
          </div>
        )}
      </Card>

      <Card title="Настройки" icon="⚙️">
        <div className="field" style={{ marginBottom: 12 }}>
          <span>Тренировка и бот</span>
          <Seg value={settings.soloSec} onChange={(v) => updateSettings({ soloSec: v })} options={[[30, '0:30'], [60, '1:00'], [90, '1:30'], [120, '2:00']]} />
        </div>
        <div className="field" style={{ marginBottom: 12 }}>
          <span>Дуэль с другом</span>
          <Seg value={settings.friendSec} onChange={(v) => updateSettings({ friendSec: v })} options={[[30, '0:30'], [60, '1:00'], [90, '1:30'], [120, '2:00']]} />
        </div>
        <div className="field" style={{ marginBottom: 12 }}>
          <span>Строгость засчёта</span>
          <Seg value={settings.strictness} onChange={(v) => updateSettings({ strictness: v })} options={[['easy', 'Мягко'], ['normal', 'Обычно'], ['strict', 'Строго']]} />
        </div>
        <div className="field" style={{ marginBottom: 12 }}>
          <span>Тема</span>
          <Seg value={settings.theme} onChange={(v) => updateSettings({ theme: v })} options={[['auto', inTg ? 'Как в Telegram' : 'Авто'], ['aqua', 'Эгея'], ['midnight', 'Амфора']]} />
        </div>
        <label className="switch"><span>Музыка в бою</span><input type="checkbox" checked={settings.music} onChange={(e) => updateSettings({ music: e.target.checked })} /></label>
        <label className="switch"><span>Звук</span><input type="checkbox" checked={settings.sound} onChange={(e) => updateSettings({ sound: e.target.checked })} /></label>
        <label className="switch"><span>Вибрация на повтор</span><input type="checkbox" checked={settings.haptics} onChange={(e) => updateSettings({ haptics: e.target.checked })} /></label>
        <label className="switch"><span>Показывать скелет</span><input type="checkbox" checked={settings.skeleton} onChange={(e) => updateSettings({ skeleton: e.target.checked })} /></label>
      </Card>
    </div>
  );
}
