'use client';
import { useEffect, useState } from 'react';
import { CHESTS } from '../lib/economy.js';
import { Avatar, Card } from './ui.jsx';
import { RewardIcon } from './Pass.jsx';
import { ChestIcon, ChestInfo, Currency, fmtLeft } from './Economy.jsx';

function untilMidnightMsk() {
  const now = Date.now();
  const msk = new Date(now + 3 * 3600000);
  const next = Date.UTC(msk.getUTCFullYear(), msk.getUTCMonth(), msk.getUTCDate() + 1) - 3 * 3600000;
  return next - now;
}

export default function Shop({ profile, onBuy, onPremium, busy }) {
  const [, tick] = useState(0);
  useEffect(() => { const t = setInterval(() => tick((x) => x + 1), 30000); return () => clearInterval(t); }, []);
  if (!profile?.eco) return <div className="page"><div className="page-head"><h1>Магазин</h1></div></div>;
  const { eco } = profile;
  return (
    <div className="page">
      <div className="page-head" style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 10 }}>
        <div><h1>Магазин</h1><p>Обновление через {fmtLeft(untilMidnightMsk())}</p></div>
        <Currency eco={eco} />
      </div>

      <Card title="Предложения дня" icon="🛍️" tone="rose" wave={['#ff9fb3', '#ffbe5c']}>
        <div className="items">
          {eco.shop.offers.map((o) => {
            const [cat, id] = o.item.split(':');
            return (
              <div key={o.id} className={`item ${o.owned ? 'on' : ''}`}>
                {cat === 'frame' ? <Avatar emoji={profile.avatar} frame={id} size="sm" /> : <RewardIcon k={o.item} />}
                <span>{o.name}</span>
                <button className="gel" style={{ padding: '6px 12px', fontSize: 13 }} disabled={o.owned || busy || eco.coins < o.coins} onClick={() => onBuy(o.id)}>
                  {o.owned ? 'Куплено' : `🪙 ${o.coins}`}
                </button>
              </div>
            );
          })}
        </div>
        <p className="small muted" style={{ marginTop: 10, fontWeight: 600 }}>Эти предметы бывают только в сундуках и здесь. Монеты — из сундуков и дороги трофеев.</p>
      </Card>

      <Card title="Сундуки" icon="🧰" tone="orange">
        <div className="items">
          {eco.shop.chests.map((c) => (
            <div key={c.id} className="item">
              <ChestIcon type={c.chest} size={52} />
              <span>{CHESTS[c.chest].name}</span>
              <button className="gel violet" style={{ padding: '6px 12px', fontSize: 13 }} disabled={busy || eco.gems < c.gems} onClick={() => onBuy(c.id)}>💎 {c.gems}</button>
            </div>
          ))}
        </div>
        <p className="small muted" style={{ marginTop: 10, fontWeight: 600 }}>Сундук из магазина открывается сразу. Кристаллы дают задания дня, дорога трофеев и сундуки — за звёзды они не продаются.</p>
      </Card>

      <Card title="Бусты" icon="⚡" tone="teal">
        {eco.shop.boosts.map((b) => (
          <div key={b.id} className="row">
            <span style={{ fontSize: 24 }}>⚡</span>
            <div className="grow"><div className="title">{b.name}</div><div className="sub">{eco.boostMs ? `Действует ещё ${fmtLeft(eco.boostMs)} — купленный продлит` : 'Весь опыт за бои и тренировки ×2'}</div></div>
            <button className="gel violet" style={{ padding: '6px 12px', fontSize: 13 }} disabled={busy || eco.gems < b.gems} onClick={() => onBuy(b.id)}>💎 {b.gems}</button>
          </div>
        ))}
      </Card>

      <Card className="premium-card" title="Звёзды Telegram" icon="🌟" tone="gold" wave={['#ffe27a', '#b06bff']}>
        <div className="row">
          <span style={{ fontSize: 26 }}>🎖️</span>
          <div className="grow"><div className="title">Премиум-пропуск</div><div className="sub">{profile.premium ? 'Уже куплен в этом сезоне' : 'Подарки Telegram, скины, +20% опыта'}</div></div>
          <button className="gel star-btn" style={{ padding: '7px 14px', fontSize: 13 }} disabled={profile.premium} onClick={onPremium}>{profile.premium ? '✓' : `${profile.premiumPrice} ⭐`}</button>
        </div>
      </Card>

      <ChestInfo />
    </div>
  );
}
