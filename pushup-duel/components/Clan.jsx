'use client';
import { useEffect, useState } from 'react';
import { api } from '../lib/client/net.js';
import { Card } from './ui.jsx';
import { PlayerRow } from './Home.jsx';

export default function Clan({ profile, config, openPlayer, openClan, toast, onShareClan, onChanged, version }) {
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
        <Card title="Состав" icon="👥" tone="violet">
          <div className="rows">
            {my.roster.map((m) => (
              <PlayerRow key={m.id} r={{ ...m, me: m.id === profile?.id }} onClick={() => openPlayer(m.id)}
                right={<span className="chip">{m.owner ? '👑 ' : ''}{m.week}</span>} />
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
