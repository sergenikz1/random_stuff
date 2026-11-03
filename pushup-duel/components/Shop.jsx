'use client';
import { useEffect, useState } from 'react';
import { PACKS } from '../lib/economy.js';
import { STICKER_RARITY, stickerById } from '../lib/stickers.js';
import { Avatar, Card } from './ui.jsx';
import { RewardIcon } from './Pass.jsx';
import { Currency, PackInfo } from './Economy.jsx';
import { PackIcon, Sticker, fmtLeft } from './Stickers.jsx';

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
      <div className="page-head">
        <h1>Магазин</h1>
        <p>Обновление через {fmtLeft(untilMidnightMsk())}</p>
        <div style={{ marginTop: 8 }}><Currency eco={eco} /></div>
      </div>

      <Card title="Предложения дня" icon="🛍️" tone="rose" wave={['#ff9fb3', '#ffbe5c']}>
        <div className="items four">
          {eco.shop.offers.map((o) => {
            const done = o.bought || o.owned;
            const st = o.sticker ? stickerById(o.sticker) : null;
            const [cat, id] = o.item ? o.item.split(':') : [];
            return (
              <div key={o.id} className={`item ${done ? 'on' : ''}`}>
                {st ? <Sticker id={st.id} size={50} /> : cat === 'frame' ? <Avatar emoji={profile.avatar} frame={id} size="sm" /> : <RewardIcon k={o.item} />}
                <span>{o.name}</span>
                {st && <span className="small" style={{ color: STICKER_RARITY[st.rarity].color, fontWeight: 800 }}>{STICKER_RARITY[st.rarity].name}</span>}
                <button className="gel" style={{ padding: '5px 10px', fontSize: 12.5 }} disabled={done || busy || eco.coins < o.coins} onClick={() => onBuy(o.id)}>
                  {done ? 'Куплено' : `🪙 ${o.coins}`}
                </button>
              </div>
            );
          })}
        </div>
      </Card>

      <Card title="Наборы наклеек" icon="🎴" tone="orange">
        <div className="items">
          {eco.shop.packs.map((c) => (
            <div key={c.id} className="item">
              <PackIcon type={c.pack} size={50} />
              <span>{PACKS[c.pack].name}</span>
              <button className="gel violet" style={{ padding: '6px 12px', fontSize: 13 }} disabled={busy || eco.gems < c.gems} onClick={() => onBuy(c.id)}>💎 {c.gems}</button>
            </div>
          ))}
        </div>
        <p className="small muted" style={{ marginTop: 10, fontWeight: 600 }}>Набор из магазина открывается сразу. Кристаллы дают задания дня, дорога трофеев и наборы — за звёзды они не продаются.</p>
      </Card>

      <Card title="Турнир и бусты" icon="⚡" tone="teal">
        <div className="rows">
          <div className="row">
            <span style={{ fontSize: 24 }}>🎟️</span>
            <div className="grow"><div className="title">Билет на турнир недели</div><div className="sub">У вас {eco.tickets}. Ещё их дают за каждые 3 победы в рейтинге</div></div>
            <button className="gel violet" style={{ padding: '6px 12px', fontSize: 13 }} disabled={busy || eco.gems < eco.shop.ticket.gems} onClick={() => onBuy(eco.shop.ticket.id)}>💎 {eco.shop.ticket.gems}</button>
          </div>
          {eco.shop.boosts.map((b) => (
            <div key={b.id} className="row">
              <span style={{ fontSize: 24 }}>⚡</span>
              <div className="grow"><div className="title">{b.name}</div><div className="sub">{eco.boostMs ? `Действует ещё ${fmtLeft(eco.boostMs)} — купленный продлит` : 'Весь опыт за бои и тренировки ×2'}</div></div>
              <button className="gel violet" style={{ padding: '6px 12px', fontSize: 13 }} disabled={busy || eco.gems < b.gems} onClick={() => onBuy(b.id)}>💎 {b.gems}</button>
            </div>
          ))}
        </div>
      </Card>

      <Card className="premium-card" title="Звёзды Telegram" icon="🌟" tone="gold" wave={['#ffe27a', '#b06bff']}>
        <div className="row">
          <span style={{ fontSize: 26 }}>🎖️</span>
          <div className="grow"><div className="title">Премиум-пропуск</div><div className="sub">{profile.premium ? 'Уже куплен в этом сезоне' : 'Подарки Telegram, скины, +20% опыта'}</div></div>
          <button className="gel star-btn" style={{ padding: '7px 14px', fontSize: 13 }} disabled={profile.premium} onClick={onPremium}>{profile.premium ? '✓' : `${profile.premiumPrice} ⭐`}</button>
        </div>
      </Card>

      <PackInfo />
    </div>
  );
}
