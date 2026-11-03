'use client';
// Валюта, арена, дорога трофеев и описание наборов.
import { PACKS, ROAD, ARENAS } from '../lib/economy.js';
import { cosmeticName } from '../lib/pass.js';
import { Bar, Card, Sheet } from './ui.jsx';
import { RewardIcon } from './Pass.jsx';
import { PackIcon } from './Stickers.jsx';

export { fmtLeft, PackSlots, PackOpenModal } from './Stickers.jsx';

export function Currency({ eco, tickets = true }) {
  if (!eco) return null;
  return (
    <div className="currency">
      <span className="cur-pill">🪙 <b>{eco.coins}</b></span>
      <span className="cur-pill">💎 <b>{eco.gems}</b></span>
      {tickets && <span className="cur-pill">🎟️ <b>{eco.tickets}</b></span>}
    </div>
  );
}

export function ArenaCard({ eco, onRoad }) {
  if (!eco) return null;
  const { arena, nextArena, trophies } = eco;
  const ready = eco.road.filter((r) => r.status === 'ready').length;
  return (
    <button className="arena-card" onClick={onRoad} style={{ '--a1': arena.colors[0], '--a2': arena.colors[1] }}>
      <span className="arena-emoji">{arena.emoji}</span>
      <div className="grow" style={{ textAlign: 'left' }}>
        <div className="arena-name">Арена {arena.id} · {arena.name}</div>
        <div className="arena-sub">🏆 {trophies}{nextArena ? ` / ${nextArena.min} до «${nextArena.name}»` : ' · высшая арена'}</div>
        {nextArena && <div style={{ marginTop: 6 }}><Bar value={trophies - arena.min} max={nextArena.min - arena.min} tone="gold" /></div>}
      </div>
      {ready > 0 && <span className="badge-dot">{ready}</span>}
      <span className="arena-go">›</span>
    </button>
  );
}

function rewardText(r) {
  if (r.coins) return { icon: '🪙', text: `${r.coins} монет` };
  if (r.gems) return { icon: '💎', text: `${r.gems} кристаллов` };
  if (r.tickets) return { icon: '🎟️', text: `${r.tickets} ${r.tickets === 1 ? 'билет' : 'билета'} на турнир` };
  if (r.pack) return { pack: r.pack, text: PACKS[r.pack].name };
  if (r.item) return { item: r.item, text: cosmeticName(r.item) };
  return { icon: '🎁', text: 'Награда' };
}

export function TrophyRoad({ eco, onClose, onClaim }) {
  if (!eco) return null;
  const items = [];
  for (const a of ARENAS) {
    items.push({ kind: 'arena', a });
    const nextMin = ARENAS.find((n) => n.min > a.min)?.min ?? Infinity;
    for (const r of eco.road.filter((x) => x.at >= a.min && x.at < nextMin)) items.push({ kind: 'step', r });
  }
  return (
    <Sheet onClose={onClose}>
      <h2>Дорога трофеев</h2>
      <p className="small muted" style={{ fontWeight: 700 }}>🏆 Кубки — это ваш рейтинг. Награды открываются по лучшему результату и остаются навсегда.</p>
      <div className="road">
        {items.map((it) => it.kind === 'arena' ? (
          <div key={`a${it.a.id}`} className={`road-arena ${eco.bestRating >= it.a.min ? 'reached' : ''}`} style={{ '--a1': it.a.colors[0], '--a2': it.a.colors[1] }}>
            <span className="arena-emoji">{it.a.emoji}</span>
            <div><div className="arena-name">Арена {it.a.id} · {it.a.name}</div><div className="arena-sub">от {it.a.min} 🏆 · свой пояс чемпиона</div></div>
          </div>
        ) : (() => {
          const t = rewardText(it.r.reward);
          return (
            <div key={`s${it.r.at}`} className={`road-step ${it.r.status}`}>
              <span className="road-at">{it.r.at} 🏆</span>
              <div className="row" style={{ flex: 1 }}>
                {t.pack ? <PackIcon type={t.pack} size={30} /> : t.item ? <RewardIcon k={t.item} /> : <span style={{ fontSize: 22 }}>{t.icon}</span>}
                <div className="grow"><div className="title">{t.text}</div></div>
                {it.r.status === 'claimed' ? <span>✅</span> : it.r.status === 'ready'
                  ? <button className="gel green" style={{ padding: '6px 14px', fontSize: 13 }} onClick={() => onClaim(it.r.at)}>Забрать</button>
                  : <span>🔒</span>}
              </div>
            </div>
          );
        })())}
      </div>
    </Sheet>
  );
}

export function PackInfo() {
  return (
    <Card title="Наборы наклеек" icon="🎴" tone="orange">
      <div className="rows">
        {Object.entries(PACKS).map(([id, c]) => (
          <div key={id} className="row">
            <PackIcon type={id} size={34} />
            <div className="grow">
              <div className="title">{c.name}</div>
              <div className="sub">{c.count} наклеек{c.guarantee ? ` · точно ${{ rare: 'редкая', epic: 'эпическая', legend: 'легендарная' }[c.guarantee]}` : ''} · {c.unlockMin >= 60 ? `${c.unlockMin / 60} ч` : `${c.unlockMin} мин`}</div>
            </div>
          </div>
        ))}
      </div>
      <p className="small muted" style={{ marginTop: 8, fontWeight: 600 }}>Шансы на каждую наклейку: обычная 72%, редкая 22%, эпическая 5%, легендарная 1%. Сверх трёх копий наклейка превращается в монеты.</p>
    </Card>
  );
}
