'use client';
// Элементы «Aero × Греция»: стрик с вулканом, окно извержения, выбор режима, пиктограммы.
import { useEffect, useState } from 'react';
import { Sheet } from './ui.jsx';

export const roman = (n) => [['M', 1000], ['CM', 900], ['D', 500], ['CD', 400], ['C', 100], ['XC', 90], ['L', 50], ['XL', 40], ['X', 10], ['IX', 9], ['V', 5], ['IV', 4], ['I', 1]]
  .reduce((s, [r, v]) => { while (n >= v) { s += r; n -= v; } return s; }, '') || '0';

// Конец сезона = извержение Везувия (23:59 по Москве в день окончания сезона).
export const eruptionAt = (season) => new Date(`${season?.ends || '2027-01-31'}T23:59:59+03:00`).getTime();

export function useCountdown(target) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);
  const ms = Math.max(0, target - now);
  return { ms, d: Math.floor(ms / 86400000), h: Math.floor((ms % 86400000) / 3600000), m: Math.floor((ms % 3600000) / 60000), s: Math.floor((ms % 60000) / 1000) };
}

// Во что превратит лава по итогам сезона (показываем текущий прогноз).
export const STATUES = {
  clay: { name: 'глина', icon: '🏺', next: 'bronze', at: 1050 },
  bronze: { name: 'бронза', icon: '🥉', next: 'marble', at: 1300 },
  marble: { name: 'мрамор', icon: '🗿', next: 'gold', at: 1600 },
  gold: { name: 'золото', icon: '🏆', next: 'obsidian', at: null },
  obsidian: { name: 'обсидиан', icon: '🌋', next: null, at: null },
};
export function statueOf(p) {
  if (p?.belt) return 'obsidian';
  const r = p?.rating || 0;
  if (r >= 1600) return 'gold';
  if (r >= 1300) return 'marble';
  if (r >= 1050) return 'bronze';
  return 'clay';
}

// Последние дни серии: profile даёт длину серии и было ли занятие сегодня.
const DOW = ['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб'];
export function streakDays(p, count = 7) {
  const today = new Date();
  const streak = p?.dayStreak || 0;
  const lastOffset = p?.playedToday ? 0 : 1; // серия заканчивается сегодня или вчера
  return Array.from({ length: count }, (_, i) => {
    const off = count - 1 - i; // сколько дней назад
    const d = new Date(today.getTime() - off * 86400000);
    const done = streak > 0 && off >= lastOffset && off < lastOffset + streak;
    return { label: DOW[d.getDay()], date: d.getDate(), done, today: off === 0 };
  });
}

export function StreakBar({ profile, onOpen }) {
  const { d, h } = useCountdown(eruptionAt(profile?.season));
  const days = streakDays(profile);
  return (
    <button className="glass-panel streakbar" onClick={onOpen}>
      <span className="sb-fire"><b>🔥{profile?.dayStreak || 0}</b><small>подряд</small></span>
      <span className="sb-days">
        {days.map((x, i) => (
          <span key={i} className={`sb-day ${x.done ? 'done' : ''} ${x.today ? 'today' : ''}`}>
            <i>{x.done ? '✓' : ''}</i>{x.label}
          </span>
        ))}
      </span>
      <span className="sb-volc">
        <span className="tile rose">🌋</span>
        <span><b>{d}д {h}ч</b><small>до вулкана</small></span>
      </span>
    </button>
  );
}

function VolcanoArt() {
  return (
    <svg viewBox="0 0 360 150" width="100%" height="100%" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
      <defs>
        <linearGradient id="vsky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="var(--volc-sky1)" /><stop offset="1" stopColor="var(--volc-sky2)" /></linearGradient>
        <linearGradient id="vmount" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="var(--volc-m1)" /><stop offset="1" stopColor="var(--volc-m2)" /></linearGradient>
      </defs>
      <rect width="360" height="150" fill="url(#vsky)" />
      {Array.from({ length: 7 }, (_, i) => <circle key={i} className="smoke" cx={170 + (i % 2 ? 14 : -10) + i * 4} cy={42 - i * 9} r={12 + i * 5} style={{ animationDelay: `${-i * 0.6}s` }} />)}
      <path d="M60 150 L150 52 Q180 44 210 52 L300 150 Z" fill="url(#vmount)" />
      <path d="M156 56 Q180 66 204 56" fill="#ff6a1a" />
      <path className="lavaflow" d="M176 60 C172 80 186 92 178 112 C172 128 184 138 180 150" stroke="#ff6a1a" strokeWidth="5" fill="none" />
      <path className="lavaflow" d="M190 60 C200 84 194 100 206 120" stroke="#ffb35a" strokeWidth="3" fill="none" />
      <rect y="138" width="360" height="12" fill="var(--volc-ground)" />
    </svg>
  );
}

export function VolcanoSheet({ profile, onClose }) {
  const { d, h, m, s } = useCountdown(eruptionAt(profile?.season));
  const st = statueOf(profile);
  const S = STATUES[st];
  const next = S.next ? STATUES[S.next] : null;
  // Календарь: последние 28 дней.
  const cal = streakDays(profile, 28);
  return (
    <Sheet onClose={onClose}>
      <div className="meander-band" />
      <div className="volc-art"><VolcanoArt /></div>
      <h2 className="greek-title center">Везувий просыпается</h2>
      <p className="small muted center" style={{ fontWeight: 700 }}>
        {profile?.season?.name || 'Сезон'} закончится извержением. Лава застынет — и ваш результат навсегда останется статуей в Форуме героев.
      </p>
      <div className="timer">
        <div><b>{d}</b><small>дней</small></div><div><b>{String(h).padStart(2, '0')}</b><small>часов</small></div>
        <div><b>{String(m).padStart(2, '0')}</b><small>минут</small></div><div><b>{String(s).padStart(2, '0')}</b><small>секунд</small></div>
      </div>
      <div className="row statue-row">
        <span style={{ fontSize: 30 }}>{S.icon}</span>
        <div className="grow">
          <div className="title">Сейчас ваша статуя — {S.name}</div>
          <div className="sub">{st === 'obsidian' ? 'Вы держите пояс — высшая честь' : next && S.at ? `До «${next.name}»: ${Math.max(0, S.at - (profile?.rating || 0))} 🏆 рейтинга` : 'Завоюйте пояс арены, чтобы стать обсидианом'}</div>
        </div>
      </div>
      <div className="streak-head">
        <span className="greek-title" style={{ fontSize: 20 }}>🔥 Серия {profile?.dayStreak || 0} {profile?.dayStreak === 1 ? 'день' : 'дней'}</span>
        <span className="small muted" style={{ fontWeight: 800 }}>опыт ×{(profile?.multiplier || 1).toFixed(1)}</span>
      </div>
      <div className="cal">{cal.map((x, i) => <i key={i} className={`${x.done ? 'd' : ''} ${x.today ? 't' : ''}`}>{x.date}</i>)}</div>
      <p className="small muted" style={{ fontWeight: 600 }}>Отжимайтесь каждый день: серия умножает опыт до ×2, а пропуск дня обнуляет её.</p>
    </Sheet>
  );
}

// Пиктограммы упражнений в духе вазовой росписи.
export function ExerciseIcon({ kind = 'pushups', size = 46 }) {
  const p = {
    pushups: (<><circle cx="10" cy="24" r="5" /><path d="M14 26 L44 34 L52 36" strokeWidth="6" /><path d="M17 27 L16 38" strokeWidth="3.5" /></>),
    squats: (<><circle cx="30" cy="8" r="5" /><path d="M30 14 L28 28" strokeWidth="6" /><path d="M28 28 L40 30 L38 42" strokeWidth="4.5" /><path d="M30 17 L44 18" strokeWidth="3.5" /></>),
    pullups: (<><path d="M6 4 H54" strokeWidth="3" /><path d="M22 4 L24 14 M38 4 L36 14" strokeWidth="3.5" /><circle cx="30" cy="13" r="5" /><path d="M30 19 L30 34" strokeWidth="6" /><path d="M30 34 L26 44 M30 34 L34 44" strokeWidth="4" /></>),
    abs: (<><circle cx="14" cy="22" r="5" /><path d="M18 25 L32 34" strokeWidth="6" /><path d="M32 34 L42 24 L52 34" strokeWidth="4.5" /><path d="M17 26 L24 18" strokeWidth="3.5" /></>),
  }[kind];
  return (
    <svg viewBox="0 0 60 48" width={size} height={size * 0.8} className="ex-ico" aria-hidden="true">
      <g fill="currentColor" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round">{p}</g>
    </svg>
  );
}

export const EXERCISES = [
  { id: 'pushups', name: 'Отжимания', ready: true },
  { id: 'squats', name: 'Приседания', ready: false },
  { id: 'pullups', name: 'Подтягивания', ready: false },
  { id: 'abs', name: 'Пресс', ready: false },
];
export const FORMATS = [
  { id: 'ranked', name: 'Рейтинг', icon: '🏆' },
  { id: 'friend', name: 'С другом', icon: '🤝' },
  { id: 'training', name: 'Тренировка', icon: '🏋️' },
  { id: 'bot', name: 'С ботом', icon: '🤖' },
];

export function ModeSheet({ settings, updateSettings, onClose, onJoinCode, toast }) {
  return (
    <Sheet onClose={onClose}>
      <div className="meander-band" />
      <h2 className="greek-title">Выбор режима</h2>
      <div className="small muted" style={{ fontWeight: 800, marginTop: -8 }}>Упражнение</div>
      <div className="ex-grid">
        {EXERCISES.map((e) => (
          <button key={e.id} className={`ex-card ${settings.exercise === e.id ? 'on' : ''} ${e.ready ? '' : 'locked'}`}
            onClick={() => (e.ready ? updateSettings({ exercise: e.id }) : toast(`«${e.name}» появятся в следующих обновлениях`))}>
            {!e.ready && <span className="ex-lock">скоро</span>}
            <ExerciseIcon kind={e.id} size={58} />
            <span className="ex-name">{e.name}</span>
          </button>
        ))}
      </div>
      <div className="small muted" style={{ fontWeight: 800 }}>Формат</div>
      <div className="fmt-grid">
        {FORMATS.map((f) => (
          <button key={f.id} className={`fmt ${settings.format === f.id ? 'on' : ''}`} onClick={() => updateSettings({ format: f.id })}>
            <b>{f.icon}</b>{f.name}
          </button>
        ))}
      </div>
      <button className="card-link" onClick={onJoinCode}>У меня есть код комнаты ›</button>
      <button className="gel xl" onClick={onClose}>Готово</button>
      <p className="small muted center" style={{ fontWeight: 600 }}>Турнир недели и титульные бои — во вкладке «Слава»</p>
    </Sheet>
  );
}
