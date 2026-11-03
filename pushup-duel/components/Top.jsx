'use client';
import { useEffect, useState } from 'react';
import { api } from '../lib/client/net.js';
import { Card, Seg } from './ui.jsx';
import { PlayerRow } from './Home.jsx';
import { BeltsList, TourneyCard } from './Compete.jsx';

export default function Top({ openPlayer, openClan, toast, version, onTitleChallenge, profile, onTourneyRegister, onTourneyPlay, status }) {
  const [type, setType] = useState('belts');
  const [data, setData] = useState(null);
  const [clans, setClans] = useState(null);

  useEffect(() => {
    let off = false;
    setData(null);
    if (type === 'belts' || type === 'tourney') return () => { off = true; };
    if (type === 'clans') api('clans').then((r) => !off && setClans(r.clans)).catch((e) => toast(e.message));
    else api('leaderboard', { query: { type } }).then((r) => !off && setData(r)).catch((e) => toast(e.message));
    return () => { off = true; };
  }, [type, toast, version]);

  const unit = type === 'week' ? 'повт.' : 'Эло';
  return (
    <div className="page">
      <div className="page-head"><h1>Рейтинги</h1><p>Кто сильнее всех в сообществе</p></div>
      <Seg value={type} onChange={setType} options={[['belts', 'Пояса'], ['tourney', 'Турнир'], ['rating', 'Эло'], ['week', 'Неделя'], ['friends', 'Друзья'], ['clans', 'Кланы']]} />
      {type === 'belts' ? <BeltsList openPlayer={openPlayer} onChallenge={onTitleChallenge} toast={toast} version={version} />
      : type === 'tourney' ? <TourneyCard profile={profile} onRegister={onTourneyRegister} onPlay={onTourneyPlay} status={status} version={version} toast={toast} />
      : type === 'clans' ? (
        <Card title="Кланы недели" icon="🛡️" tone="violet">
          {!clans ? <div className="empty">Загрузка…</div> : !clans.length ? <div className="empty">Кланов пока нет — создайте первый во вкладке «Клан»</div> : (
            <div className="rows">
              {clans.map((c, i) => (
                <button key={c.id} className="row" style={{ textAlign: 'left', width: '100%' }} onClick={() => openClan(c.id)}>
                  <span className="place">{i < 3 ? ['🥇', '🥈', '🥉'][i] : i + 1}</span>
                  <span className="tile violet" style={{ width: 36, height: 36, fontSize: 19 }}>{c.emoji}</span>
                  <div className="grow"><div className="title">{c.name}</div><div className="sub">{c.members}/{c.max} участников · капитан {c.owner}</div></div>
                  <span className="chip">{c.week}</span>
                </button>
              ))}
            </div>
          )}
        </Card>
      ) : (
        <Card title={{ rating: 'Общий рейтинг', week: 'Отжимания за неделю', friends: 'Вы и друзья' }[type]} icon={{ rating: '🏆', week: '📅', friends: '🤝' }[type]} tone={type === 'week' ? 'green' : 'gold'}>
          {!data ? <div className="empty">Загрузка…</div> : !data.top.length ? (
            <div className="empty">{type === 'friends' ? 'Друзья появятся после дуэлей по коду и приглашений' : 'Пока пусто — станьте первым!'}</div>
          ) : (
            <div className="rows">
              {data.top.map((r) => <PlayerRow key={r.id} r={r} onClick={() => openPlayer(r.id)} right={<span className="chip">{r.score} {unit}</span>} />)}
              {data.me && !data.top.some((r) => r.me) && (
                <>
                  <div className="center muted">···</div>
                  <PlayerRow r={data.me} onClick={() => openPlayer(data.me.id)} right={<span className="chip">{data.me.score} {unit}</span>} />
                </>
              )}
            </div>
          )}
        </Card>
      )}
    </div>
  );
}
