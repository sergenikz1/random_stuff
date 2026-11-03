'use client';
// Аватар и герб клана, собранные из деталей (lib/heraldry.js), и их редакторы.
// Фигура и знак — одноцветный силуэт (CSS), как на чернофигурной вазе.
import { useId, useState } from 'react';
import {
  PAINT, PAINT_IDS, paint, AVATAR_SHAPES, AVATAR_RIMS, DEFAULT_LOOK, cleanLook,
  CREST_SHAPES, CREST_DIVISIONS, CREST_RIMS, CREST_CHARGES, DEFAULT_CREST, cleanCrest,
} from '../lib/heraldry.js';
import { COSMETICS } from '../lib/pass.js';
import { Sheet } from './ui.jsx';

// ---------------- Аватар ----------------
export function AvatarArt({ fig, look, frame = 'none', size = '', side = '' }) {
  const l = cleanLook(look || DEFAULT_LOOK);
  const fx = frame && frame !== 'none' ? `f-${frame}` : side;
  return (
    <span className={`ava ${size} shape-${l.shape} rim-${l.rim} ${fx}`}
      style={{ '--rim': paint(l.rimColor), '--bg': paint(l.bg), '--fc': paint(l.figColor) }}>
      <i className="l-out" /><i className="l-band" /><i className="l-pat" /><i className="l-in" />
      <b className="face"><em className="sil">{fig || '🦊'}</em></b>
    </span>
  );
}

// ---------------- Герб ----------------
const SHAPE_D = {
  heater: 'M8 6 H92 V52 C92 80 72 97 50 106 C28 97 8 80 8 52 Z',
  round: 'M50 8 A47 47 0 1 1 49.9 8 Z',
  banner: 'M12 4 H88 V104 L50 86 L12 104 Z',
  tablet: 'M18 6 H82 Q94 6 94 18 V94 Q94 106 82 106 H18 Q6 106 6 94 V18 Q6 6 18 6 Z',
};
const DIV = {
  none: null,
  pale: <rect x="50" y="0" width="50" height="112" />,
  fess: <rect x="0" y="56" width="100" height="56" />,
  bend: <polygon points="0,0 100,112 0,112" />,
  chevron: <polygon points="0,112 50,48 100,112 100,82 50,22 0,82" />,
  quarter: <><rect x="50" y="0" width="50" height="56" /><rect x="0" y="56" width="50" height="56" /></>,
  cross: <><rect x="40" y="0" width="20" height="112" /><rect x="0" y="44" width="100" height="20" /></>,
};

export function Crest({ crest, size = 40 }) {
  const c = cleanCrest(crest || DEFAULT_CREST);
  const id = useId().replace(/:/g, '');
  const d = SHAPE_D[c.shape];
  const inner = `translate(50 56) scale(${c.rim === 'plain' ? 0.88 : 0.8}) translate(-50 -56)`;
  return (
    <span className="crest" style={{ width: size, height: size * 1.1, '--fc': paint(c.chargeColor), '--cs': `${size * 0.42}px` }}>
      <svg viewBox="0 0 100 112" width={size} height={size * 1.1} aria-hidden="true">
        <defs>
          <clipPath id={`in${id}`}><path d={d} transform={inner} /></clipPath>
          <pattern id={`mq${id}`} width="12" height="12" patternUnits="userSpaceOnUse">
            <rect width="12" height="12" fill={paint(c.field2)} />
            <path d="M1 11 V2 H10 V8 H5 V5" fill="none" stroke={paint(c.rimColor)} strokeWidth="1.8" />
          </pattern>
        </defs>
        <path d={d} fill={c.rim === 'meander' ? `url(#mq${id})` : paint(c.rimColor)} stroke={paint(c.rimColor)} strokeWidth="4" strokeLinejoin="round" />
        {c.rim === 'double' && <path d={d} transform="translate(50 56) scale(0.9) translate(-50 -56)" fill={paint(c.field)} stroke={paint(c.rimColor)} strokeWidth="3" />}
        <path d={d} transform={inner} fill={paint(c.field)} />
        {DIV[c.division] && <g clipPath={`url(#in${id})`} fill={paint(c.field2)}>{DIV[c.division]}</g>}
        <path d={d} transform={inner} fill="none" stroke={paint(c.rimColor)} strokeWidth="2.5" />
      </svg>
      <em className="sil">{c.charge}</em>
    </span>
  );
}

// ---------------- Общие элементы редакторов ----------------
function Swatches({ value, onChange }) {
  return (
    <div className="swatches">
      {PAINT_IDS.map((k) => (
        <button key={k} className={value === k ? 'on' : ''} style={{ '--sw': PAINT[k].hex }} title={PAINT[k].name} onClick={() => onChange(k)} aria-label={PAINT[k].name} />
      ))}
    </div>
  );
}
function Chips({ map, value, onChange }) {
  return (
    <div className="opt-chips">
      {Object.entries(map).map(([k, name]) => <button key={k} className={value === k ? 'on' : ''} onClick={() => onChange(k)}>{name}</button>)}
    </div>
  );
}

// ---------------- Редактор аватара ----------------
export function AvatarEditor({ profile, onSave, onClose }) {
  const [fig, setFig] = useState(profile.avatar);
  const [look, setLook] = useState(cleanLook(profile.look));
  const [tab, setTab] = useState('fig');
  const unlocked = new Set(profile.unlocked);
  const set = (k) => (v) => setLook({ ...look, [k]: v });
  const figs = Object.keys(COSMETICS.avatar);
  return (
    <Sheet onClose={onClose}>
      <h2>Мой аватар</h2>
      <div className="emblem-preview">
        <AvatarArt fig={fig} look={look} frame={profile.equipped?.frame} size="xl" />
        <AvatarArt fig={fig} look={look} size="md" />
        <AvatarArt fig={fig} look={look} size="sm" />
      </div>
      <div className="seg">
        {[['fig', 'Фигура'], ['shape', 'Форма'], ['rim', 'Окантовка'], ['color', 'Цвета']].map(([k, n]) => (
          <button key={k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{n}</button>
        ))}
      </div>
      {tab === 'fig' && (
        <>
          <div className="fig-grid">
            {figs.map((a) => (
              <button key={a} className={fig === a ? 'on' : ''} disabled={!unlocked.has(`avatar:${a}`)} onClick={() => setFig(a)}>
                <em className="sil">{a}</em>
              </button>
            ))}
          </div>
          <p className="small muted">Редкие фигуры открываются в боевом пропуске, в магазине и за приглашения.</p>
        </>
      )}
      {tab === 'shape' && <Chips map={AVATAR_SHAPES} value={look.shape} onChange={set('shape')} />}
      {tab === 'rim' && (
        <>
          <Chips map={AVATAR_RIMS} value={look.rim} onChange={set('rim')} />
          <div className="field"><span>Цвет окантовки</span><Swatches value={look.rimColor} onChange={set('rimColor')} /></div>
          <p className="small muted">Рамка из пропуска (если надета) перекрашивает окантовку.</p>
        </>
      )}
      {tab === 'color' && (
        <>
          <div className="field"><span>Фон</span><Swatches value={look.bg} onChange={set('bg')} /></div>
          <div className="field"><span>Фигура</span><Swatches value={look.figColor} onChange={set('figColor')} /></div>
          <div className="field"><span>Окантовка</span><Swatches value={look.rimColor} onChange={set('rimColor')} /></div>
        </>
      )}
      <button className="gel xl" onClick={() => onSave({ avatar: fig, look })}>Сохранить</button>
    </Sheet>
  );
}

// ---------------- Редактор герба ----------------
export function CrestEditor({ value, onChange }) {
  const c = cleanCrest(value);
  const [tab, setTab] = useState('charge');
  const set = (k) => (v) => onChange({ ...c, [k]: v });
  return (
    <div className="crest-editor">
      <div className="emblem-preview"><Crest crest={c} size={96} /><Crest crest={c} size={48} /><Crest crest={c} size={28} /></div>
      <div className="seg">
        {[['charge', 'Знак'], ['shape', 'Щит'], ['field', 'Поле'], ['rim', 'Кайма']].map(([k, n]) => (
          <button key={k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{n}</button>
        ))}
      </div>
      {tab === 'charge' && (
        <>
          <div className="fig-grid">
            {CREST_CHARGES.map((e) => <button key={e} className={c.charge === e ? 'on' : ''} onClick={() => set('charge')(e)}><em className="sil">{e}</em></button>)}
          </div>
          <div className="field"><span>Цвет знака</span><Swatches value={c.chargeColor} onChange={set('chargeColor')} /></div>
        </>
      )}
      {tab === 'shape' && <Chips map={CREST_SHAPES} value={c.shape} onChange={set('shape')} />}
      {tab === 'field' && (
        <>
          <Chips map={CREST_DIVISIONS} value={c.division} onChange={set('division')} />
          <div className="field"><span>Основной цвет</span><Swatches value={c.field} onChange={set('field')} /></div>
          <div className="field"><span>Второй цвет</span><Swatches value={c.field2} onChange={set('field2')} /></div>
        </>
      )}
      {tab === 'rim' && (
        <>
          <Chips map={CREST_RIMS} value={c.rim} onChange={set('rim')} />
          <div className="field"><span>Цвет каймы</span><Swatches value={c.rimColor} onChange={set('rimColor')} /></div>
        </>
      )}
    </div>
  );
}

export function CrestSheet({ crest, onSave, onClose }) {
  const [c, setC] = useState(cleanCrest(crest));
  return (
    <Sheet onClose={onClose}>
      <h2>Герб клана</h2>
      <CrestEditor value={c} onChange={setC} />
      <button className="gel xl" onClick={() => onSave(c)}>Сохранить герб</button>
    </Sheet>
  );
}
