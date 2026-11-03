'use client';
// Валюта и описание наборов.
import { PACKS } from '../lib/economy.js';
import { Card } from './ui.jsx';
import { PackIcon } from './Stickers.jsx';
import { Icon } from './Icon.jsx';

export { fmtLeft, PackSlots, PackOpenModal } from './Stickers.jsx';

export function Currency({ eco }) {
  if (!eco) return null;
  return (
    <div className="currency">
      <span className="cur-pill"><Icon name="coin" size={22} /><b>{eco.coins}</b></span>
      <span className="cur-pill"><Icon name="gem" size={22} /><b>{eco.gems}</b></span>
    </div>
  );
}

export function PackInfo() {
  return (
    <Card title="Наборы наклеек" icon="🎴">
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
