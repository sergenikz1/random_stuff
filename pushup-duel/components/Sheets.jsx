'use client';
// Шторки: профиль игрока (с вызовом на дуэль), клан, входящий вызов.
import { useEffect, useState } from 'react';
import { api } from '../lib/client/net.js';
import { Avatar, Sheet, ago, fmtTime } from './ui.jsx';
import { PlayerRow } from './Home.jsx';

export function PlayerSheet({ id, onClose, onChallenge, canChallenge, toast, myArena }) {
  const [d, setD] = useState(null);
  useEffect(() => { api(`players/${id}`).then(setD).catch((e) => { toast(e.message); onClose(); }); }, [id, onClose, toast]);
  return (
    <Sheet onClose={onClose}>
      {!d ? <div className="empty">Загрузка…</div> : (
        <>
          <div style={{ display: 'flex', gap: 14, alignItems: 'center' }}>
            <Avatar emoji={d.player.avatar} frame={d.player.frame} size="lg" />
            <div style={{ minWidth: 0 }}>
              <h2>{d.player.name}</h2>
              <div className="small" style={{ fontWeight: 700 }}>{d.player.title}</div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 6 }}>
                <span className="chip gold">{d.player.rank} · {d.player.rating}</span>
                <span className="chip">ур. {d.player.level}</span>
                {d.online && <span className="chip green">в сети</span>}
                {d.friend && <span className="chip">друг</span>}
              </div>
            </div>
          </div>
          {d.player.clan && <div className="row"><span style={{ fontSize: 20 }}>{d.player.clan.emoji}</span><div className="grow"><div className="title">{d.player.clan.name}</div><div className="sub">клан</div></div></div>}
          <div className="stat-grid">
            <div className="stat"><b>{d.player.games}</b><span>дуэлей</span></div>
            <div className="stat"><b>{d.player.wins}</b><span>побед</span></div>
            <div className="stat"><b>{d.player.bestReps}</b><span>рекорд</span></div>
            <div className="stat"><b>{d.player.totalReps}</b><span>всего</span></div>
          </div>
          {d.recent.length > 0 && (
            <div className="rows">
              {d.recent.map((h, i) => (
                <div key={i} className="row"><div className="grow"><div className="title">{h.opp ? `${h.reps} : ${h.oppReps} — ${h.opp}` : `${h.reps} отжиманий`}</div><div className="sub">{ago(h.at)} назад</div></div>
                  {h.outcome && <span className={`chip ${h.outcome === 'win' ? 'green' : h.outcome === 'loss' ? 'red' : ''}`}>{{ win: 'победа', loss: 'поражение', draw: 'ничья' }[h.outcome]}</span>}</div>
              ))}
            </div>
          )}
          {!d.me && (
            <button className="gel xl" disabled={!canChallenge} onClick={() => onChallenge(d.player)}>⚔️ Вызвать на дуэль</button>
          )}
          {!d.me && d.player.belt && d.player.belt === myArena && (
            <button className="gel xl orange" disabled={!canChallenge} onClick={() => onChallenge(d.player, true)}>🥊 Вызов за пояс</button>
          )}
          {!d.me && d.player.tg && <p className="small muted center" style={{ fontWeight: 600 }}>Вызов придёт игроку сообщением от бота в Telegram</p>}
        </>
      )}
    </Sheet>
  );
}

export function ClanSheet({ id, onClose, onJoined, openPlayer, toast, invite }) {
  const [d, setD] = useState(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { api(`clans/${id}`).then(setD).catch((e) => { toast(e.message); onClose(); }); }, [id, onClose, toast]);
  const join = async () => {
    setBusy(true);
    try { await api(`clans/${id}/join`, { method: 'POST' }); toast('Вы в клане! 🛡️'); onJoined(); onClose(); } catch (e) { toast(e.message); } finally { setBusy(false); }
  };
  return (
    <Sheet onClose={onClose}>
      {!d ? <div className="empty">Загрузка…</div> : (
        <>
          {invite && <div className="chip gold" style={{ alignSelf: 'flex-start' }}>Вас пригласили в клан</div>}
          <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
            <span className="tile violet" style={{ width: 58, height: 58, borderRadius: 16, fontSize: 30 }}>{d.clan.emoji}</span>
            <div><h2>{d.clan.name}</h2><div className="small muted" style={{ fontWeight: 700 }}>#{d.clan.place} из {d.clan.of} · {d.clan.week} отжиманий за неделю</div></div>
          </div>
          {d.clan.about && <p style={{ fontWeight: 600 }}>{d.clan.about}</p>}
          <div className="rows">
            {d.clan.roster.map((m) => <PlayerRow key={m.id} r={m} onClick={() => openPlayer(m.id)} right={<span className="chip">{m.owner ? '👑 ' : ''}{m.week}</span>} />)}
          </div>
          {!d.mine && <button className="gel xl violet" disabled={busy || d.clan.members >= d.clan.max} onClick={join}>🛡️ Вступить ({d.clan.members}/{d.clan.max})</button>}
        </>
      )}
    </Sheet>
  );
}

export function ChallengedSheet({ from, durationSec, onAccept, onClose }) {
  return (
    <Sheet onClose={onClose} center>
      <div className="center"><Avatar emoji={from.avatar} frame={from.frame} size="xl" /></div>
      <h2 className="center">{from.name} вызывает вас на дуэль!</h2>
      <p className="center muted" style={{ fontWeight: 700 }}>{fmtTime(durationSec * 1000)} · {from.rank} · {from.rating} Эло</p>
      <button className="gel xl" onClick={onAccept}>⚔️ Принять</button>
      <button className="glass-btn" onClick={onClose}>Не сейчас</button>
    </Sheet>
  );
}
