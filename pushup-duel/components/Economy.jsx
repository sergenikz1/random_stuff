'use client';
// Валюта, арена, дорога трофеев и описание наборов.
import { PACKS } from '../lib/economy.js';
import { Bar, Card } from './ui.jsx';
import { PackIcon } from './Stickers.jsx';

export { fmtLeft, PackSlots, PackOpenModal } from './Stickers.jsx';

export function Currency({ eco }) {
  if (!eco) return null;
  return (
    <div className="currency">
      <span className="cur-pill">🪙 <b>{eco.coins}</b></span>
      <span className="cur-pill">💎 <b>{eco.gems}</b></span>
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
