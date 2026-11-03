'use client';
import { useEffect, useState } from 'react';
import { api } from '../lib/client/net.js';
import { Card } from './ui.jsx';
import { PlayerRow } from './Home.jsx';

function WarTimer({ end }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);
  const ms = Math.max(0, end - now);
  return <>{Math.floor(ms / 3600000)} ч {String(Math.floor((ms % 3600000) / 60000)).padStart(2, '0')} мин</>;
}

// Клановая война: вызов, ответ капитана, счёт и бои войны.
function WarCard({ my, data, status, onWarDuel, onDeclareWar, onRespondWar, openClan }) {
  const w = my.war;
  const [picking, setPicking] = useState(false);
  const enemies = (data.clans || []).filter((c) => c.id !== my.id);
  if (w && w.status === 'active') {
    const lead = w.score.mine - w.score.enemy;
    return (
      <Card title="Война кланов" icon="⚔️" tone="rose" wave={['#ff9fb3', '#ffbe5c']}>
        <div className="war-board">
          <div><span className="war-emoji">{my.emoji}</span><b>{my.name}</b><span className="war-score mine">{w.score.mine}</span></div>
          <div className="war-vs">VS<small>осталось <WarTimer end={w.end} /></small></div>
          <button onClick={() => openClan(w.enemy.id)}><span className="war-emoji">{w.enemy.emoji}</span><b>{w.enemy.name}</b><span className="war-score enemy">{w.score.enemy}</span></button>
        </div>
        <p className="small" style={{ fontWeight: 700, textAlign: 'center', margin: '8px 0' }}>
          {lead > 0 ? `Ведём на ${lead}` : lead < 0 ? `Отстаём на ${-lead}` : 'Равный счёт'} · ваш вклад: {w.myContrib} · боёв выиграно: {w.duels.mine}–{w.duels.enemy}
        </p>
        <button className="gel xl rose" disabled={status !== 'online'} onClick={onWarDuel}>⚔️ Бой войны (+{w.duelWin} за победу)</button>
        <p className="small muted" style={{ marginTop: 8, fontWeight: 600 }}>Каждое отжимание участников в любом режиме — очко клану. Бой против игрока вражеского клана — ещё +{w.duelWin} за победу.</p>
      </Card>
    );
  }
  if (w && w.status === 'pending') {
    return (
      <Card title="Война кланов" icon="📯" tone="rose">
        <p style={{ fontWeight: 700 }}>{w.attacker ? `Ждём ответа клана «${w.enemy.name}»…` : `Клан «${w.enemy.emoji} ${w.enemy.name}» вызывает вас на войну на ${w.hours} часа!`}</p>
        {w.canRespond && (
          <div className="row-actions" style={{ marginTop: 10 }}>
            <button className="gel rose" onClick={() => onRespondWar(w.id, 'accept')}>⚔️ Принять</button>
            <button className="glass-btn" onClick={() => onRespondWar(w.id, 'decline')}>Отклонить</button>
          </div>
        )}
        {!w.attacker && !w.canRespond && <p className="small muted" style={{ marginTop: 6, fontWeight: 600 }}>Ответить может капитан клана.</p>}
      </Card>
    );
  }
  return (
    <Card title="Война кланов" icon="⚔️" tone="rose">
      {w?.status === 'done' && (
        <p style={{ fontWeight: 800, marginBottom: 8 }}>
          Прошлая война с «{w.enemy.name}»: {w.score.mine}:{w.score.enemy} — {w.score.mine > w.score.enemy ? 'победа 🏆' : w.score.mine < w.score.enemy ? 'поражение' : 'ничья'}
        </p>
      )}
      <p className="small" style={{ fontWeight: 600 }}>Сутки противостояния: очки приносят все отжимания участников и победы в боях против вражеского клана. Победители получают кристаллы и эпический набор.</p>
      {my.captain ? (
        picking ? (
          <div className="rows" style={{ marginTop: 10 }}>
            {enemies.length ? enemies.slice(0, 12).map((c) => (
              <div key={c.id} className="row">
                <span className="tile violet" style={{ width: 34, height: 34 }}>{c.emoji}</span>
                <div className="grow"><div className="title">{c.name}</div><div className="sub">{c.members} чел. · {c.week} за неделю</div></div>
                <button className="gel rose" style={{ padding: '6px 12px', fontSize: 13 }} onClick={() => { setPicking(false); onDeclareWar(c.id); }}>Вызвать</button>
              </div>
            )) : <div className="empty">Других кланов пока нет</div>}
            <button className="glass-btn" onClick={() => setPicking(false)}>Отмена</button>
          </div>
        ) : <button className="gel wide rose" style={{ marginTop: 10 }} onClick={() => setPicking(true)}>📯 Объявить войну</button>
      ) : <p className="small muted" style={{ marginTop: 8, fontWeight: 600 }}>Объявить войну может капитан клана.</p>}
    </Card>
  );
}

export default function Clan({ profile, config, openPlayer, openClan, toast, onShareClan, onChanged, version, status, onSpar, onWarDuel, onChallenge, onDeclareWar, onRespondWar }) {
  const [data, setData] = useState(null);
  const [q, setQ] = useState('');
  const [form, setForm] = useState({ name: '', emoji: '🛡️', about: '' });
  const [busy, setBusy] = useState(false);

  const load = () => api('clans', { query: q ? { q } : undefined }).then(setData).catch((e) => toast(e.message));
  useEffect(() => { load(); }, [version]); // eslint-disable-line react-hooks/exhaustive-deps

  const act = async (fn) => {
    setBusy(true);
    try { await fn(); await load(); onChanged(); } catch (e) { toast(e.message); } finally { setBusy(false); }
  };

  const my = data?.myClan;
  if (!data) return <div className="page"><div className="page-head"><h1>Клан</h1></div><Card><div className="empty">Загрузка…</div></Card></div>;

  if (my) {
    return (
      <div className="page">
        <div className="page-head"><h1>{my.emoji} {my.name}</h1><p>{my.place ? `#${my.place} из ${my.of} кланов на этой неделе` : 'Клан'}</p></div>
        <Card wave={['#b49bff', '#43a8ff']}>
          {my.about && <p style={{ fontWeight: 600, marginBottom: 10 }}>{my.about}</p>}
          <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(3, 1fr)' }}>
            <div className="stat"><b>{my.week}</b><span>за неделю</span></div>
            <div className="stat"><b>{my.members}/{my.max}</b><span>участников</span></div>
            <div className="stat"><b>{my.id}</b><span>код клана</span></div>
          </div>
          <button className="gel wide violet" style={{ marginTop: 12 }} onClick={() => onShareClan(my)}>📨 Позвать в клан</button>
        </Card>
        <WarCard my={my} data={data} status={status} onWarDuel={onWarDuel} onDeclareWar={onDeclareWar} onRespondWar={onRespondWar} openClan={openClan} />
        <Card title="Спарринги" icon="🤼" tone="green">
          <p className="small" style={{ fontWeight: 600, marginBottom: 10 }}>Тренируйтесь с соклановцами: без влияния на рейтинг, но с опытом и очками для клана.</p>
          <button className="gel xl green" disabled={status !== 'online' || my.members < 2} onClick={onSpar}>🤼 Найти спарринг в клане</button>
          <p className="small muted" style={{ marginTop: 6, fontWeight: 600 }}>
            В сети: {my.roster.filter((m) => m.online && m.id !== profile?.id).length} · можно вызвать конкретного участника кнопкой ⚔ в составе
          </p>
        </Card>
        <Card title="Состав" icon="👥" tone="violet">
          <div className="rows">
            {my.roster.map((m) => (
              <PlayerRow key={m.id} r={{ ...m, me: m.id === profile?.id }} onClick={() => openPlayer(m.id)}
                right={(
                  <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    {m.online && <span className="live-dot" title="в сети" />}
                    <span className="chip">{m.owner ? '👑 ' : ''}{m.week}</span>
                    {m.id !== profile?.id && <span className="spar-btn" role="button" tabIndex={0} onClick={(e) => { e.stopPropagation(); onChallenge(m); }}>⚔</span>}
                  </span>
                )} />
            ))}
          </div>
          <p className="small muted" style={{ marginTop: 10, fontWeight: 600 }}>Очки клана — сумма отжиманий участников за неделю, в любых режимах.</p>
        </Card>
        <button className="glass-btn danger" disabled={busy} onClick={() => act(() => api('clans/leave', { method: 'POST' }))}>Покинуть клан</button>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-head"><h1>Кланы</h1><p>Отжимайтесь командой и поднимайте клан в недельном рейтинге</p></div>
      <Card title="Создать клан" icon="🛡️" tone="violet" wave={['#b49bff', '#ff9fd5']}>
        <div className="field" style={{ marginBottom: 10 }}>
          <span>Название</span>
          <input className="input" maxLength={24} value={form.name} placeholder="Например, Железные локти" onChange={(e) => setForm({ ...form, name: e.target.value })} />
        </div>
        <div className="field" style={{ marginBottom: 10 }}>
          <span>Герб</span>
          <div className="emoji-grid">
            {(config?.clanEmoji || ['🛡️']).map((e) => <button key={e} className={form.emoji === e ? 'on' : ''} onClick={() => setForm({ ...form, emoji: e })}>{e}</button>)}
          </div>
        </div>
        <div className="field" style={{ marginBottom: 12 }}>
          <span>Девиз</span>
          <input className="input" maxLength={120} value={form.about} placeholder="Ни дня без отжиманий" onChange={(e) => setForm({ ...form, about: e.target.value })} />
        </div>
        <button className="gel wide violet" disabled={busy || form.name.trim().length < 3}
          onClick={() => act(() => api('clans', { method: 'POST', body: form }))}>Основать клан</button>
      </Card>
      <Card title="Вступить" icon="🔎" tone="blue">
        <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
          <input className="input" value={q} placeholder="Название или код клана" onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && load()} />
          <button className="gel" onClick={load}>Найти</button>
        </div>
        {!data.clans.length ? <div className="empty">Ничего не нашлось</div> : (
          <div className="rows">
            {data.clans.map((c) => (
              <div key={c.id} className="row">
                <span className="tile violet" style={{ width: 36, height: 36, fontSize: 19 }}>{c.emoji}</span>
                <button className="grow" style={{ border: 0, background: 'none', textAlign: 'left', padding: 0 }} onClick={() => openClan(c.id)}>
                  <div className="title">{c.name}</div><div className="sub">{c.members}/{c.max} · {c.week} за неделю</div>
                </button>
                <button className="glass-btn" style={{ padding: '7px 12px' }} disabled={busy || c.members >= c.max}
                  onClick={() => act(() => api(`clans/${c.id}/join`, { method: 'POST' }))}>Вступить</button>
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}
