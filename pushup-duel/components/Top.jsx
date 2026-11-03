'use client';
import { useEffect, useState } from 'react';
import { api } from '../lib/client/net.js';
import { Bar, Card, Seg, ago, plural } from './ui.jsx';
import { PlayerRow } from './Home.jsx';
import { BeltsList, TourneysView } from './Compete.jsx';

export default function Top({ openPlayer, openClan, toast, version, onTitleChallenge, profile, onTourneyStart, onTourneyPlay, status, feed, community }) {
  const [type, setType] = useState('belts');
  const [data, setData] = useState(null);
  const [clans, setClans] = useState(null);

  useEffect(() => {
    let off = false;
    setData(null);
    if (type === 'belts' || type === 'tourney' || type === 'feed') return () => { off = true; };
    if (type === 'clans') api('clans').then((r) => !off && setClans(r.clans)).catch((e) => toast(e.message));
    else api('leaderboard', { query: { type } }).then((r) => !off && setData(r)).catch((e) => toast(e.message));
    return () => { off = true; };
  }, [type, toast, version]);

  const unit = type === 'week' ? 'повт.' : 'Эло';
  return (
    <div className="page">
      <div className="page-head"><h1>Слава</h1><p>Пояса, турниры, рейтинги и жизнь сообщества</p></div>
      <Seg value={type} onChange={setType} options={[['belts', 'Пояса'], ['tourney', 'Турниры'], ['rating', 'Эло'], ['week', 'Неделя'], ['friends', 'Друзья'], ['clans', 'Кланы'], ['feed', 'Лента']]} />
      {type === 'feed' ? (
        <>
          {community && (
            <Card title="Цель недели" icon="🌍" tone="teal" wave={['#5ee8d8', '#43a8ff']}>
              <div style={{ fontWeight: 800, fontSize: 15 }}>Всем сообществом — {community.goal.toLocaleString('ru-RU')} отжиманий</div>
              <div style={{ margin: '10px 0 6px' }}><Bar value={community.total} max={community.goal} tone="blue" /></div>
              <div className="small muted" style={{ fontWeight: 700 }}>
                {community.total.toLocaleString('ru-RU')} от {community.people} {plural(community.people, 'участника', 'участников', 'участников')}
                {community.reached ? ' · цель выполнена! 🎉' : ` · награда +${community.reward} опыта тем, кто сделал от ${community.minReps}`}
              </div>
              {profile && <div className="small" style={{ fontWeight: 700, marginTop: 6 }}>Ваш вклад: {profile.weekReps}</div>}
            </Card>
          )}
          <Card title="Что происходит" icon="📣" tone="blue">
            {feed?.length ? feed.slice(0, 30).map((f, i) => (
              <div key={i} className="feed-row"><span>{f.icon}</span><span>{f.text}</span><span className="when">{ago(f.at)}</span></div>
            )) : <div className="empty">Пока тихо. Сыграйте первую дуэль!</div>}
          </Card>
        </>
      )
      : type === 'belts' ? <BeltsList openPlayer={openPlayer} onChallenge={onTitleChallenge} toast={toast} version={version} />
      : type === 'tourney' ? <TourneysView profile={profile} onStart={onTourneyStart} onPlay={onTourneyPlay} status={status} version={version} toast={toast} />
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
