'use client';
import { Avatar, Bar, Card, ago, plural } from './ui.jsx';
import { PASS_REWARDS, cosmeticName } from '../lib/pass.js';
import { RewardIcon } from './Pass.jsx';

function greeting() {
  const h = new Date().getHours();
  if (h < 5) return 'Доброй ночи';
  if (h < 12) return 'Доброе утро';
  if (h < 18) return 'Добрый день';
  return 'Добрый вечер';
}

export default function Home({ profile, stats, community, feed, status, onRanked, onFriend, onJoinCode, onTraining, onBot, goTab, openPlayerByName }) {
  const p = profile;
  const nextReward = p && !p.max ? Object.keys(PASS_REWARDS).map(Number).find((l) => l > p.level) : null;
  return (
    <div className="page">
      <div className="page-head">
        <h1>{greeting()}, {p?.name || 'атлет'}!</h1>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 8 }}>
          <span className="streak-pill">🔥 {p?.dayStreak || 0} {plural(p?.dayStreak || 0, 'день', 'дня', 'дней')} подряд{p?.multiplier > 1 ? ` · опыт ×${p.multiplier.toFixed(1)}` : ''}</span>
          {!p?.playedToday && <span className="streak-pill">Отожмись сегодня, чтобы {p?.dayStreak ? 'не потерять' : 'начать'} серию</span>}
        </div>
      </div>

      <Card className="hero" wave={['#4fdcc8', '#6fd36a']}>
        <div className="online">
          <span className="live-dot" style={status !== 'online' ? { background: '#f59e0b', animation: 'none' } : undefined} />
          {status === 'online' ? `${stats?.online ?? 0} онлайн · ${stats?.playing ?? 0} в бою` : 'Подключаюсь к серверу…'}
        </div>
        <div className="hero-title">Онлайн-дуэль</div>
        <p className="muted small" style={{ fontWeight: 600, marginBottom: 12 }}>Минута, реальный соперник, кто больше отожмётся. Победа поднимает рейтинг.</p>
        <button className="gel xl" onClick={onRanked} disabled={status !== 'online'}>⚔️ Найти соперника</button>
        <div className="mode-grid">
          <button className="mode-btn" onClick={onFriend} disabled={status !== 'online'}><span className="tile violet">🤝</span>С другом</button>
          <button className="mode-btn" onClick={onTraining}><span className="tile green">🏋️</span>Тренировка</button>
          <button className="mode-btn" onClick={onBot}><span className="tile orange">🤖</span>С ботом</button>
        </div>
        <button className="card-link" style={{ marginTop: 8 }} onClick={onJoinCode}>У меня есть код комнаты</button>
      </Card>

      {p && (
        <Card title="Боевой пропуск" icon="🎖️" tone="gold" link="Награды" onLink={() => goTab('pass')}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <div className="big-num">{p.level}</div>
            <div style={{ flex: 1 }}>
              <div className="small muted" style={{ fontWeight: 700, marginBottom: 6 }}>{p.season.name} · {p.max ? 'максимальный уровень' : `${p.xpInLevel} / ${p.xpNeed} опыта`}</div>
              <Bar value={p.max ? 1 : p.xpInLevel} max={p.max ? 1 : p.xpNeed} tone="gold" />
            </div>
          </div>
          {nextReward && (
            <div className="row" style={{ marginTop: 10 }}>
              <RewardIcon k={PASS_REWARDS[nextReward][0]} />
              <div className="grow"><div className="title">{cosmeticName(PASS_REWARDS[nextReward][0])}</div><div className="sub">награда за {nextReward} уровень</div></div>
            </div>
          )}
        </Card>
      )}

      {p && (
        <Card title="Задания дня" icon="🎯" tone="rose">
          <div className="rows">
            {p.quests.map((q) => (
              <div key={q.id} className="row">
                <span style={{ fontSize: 20 }}>{q.done ? '✅' : '⬜'}</span>
                <div className="grow">
                  <div className="title" style={q.done ? { textDecoration: 'line-through', opacity: 0.6 } : undefined}>{q.text}</div>
                  {!q.done && q.goal > 1 && <div style={{ marginTop: 5 }}><Bar value={q.progress} max={q.goal} /></div>}
                </div>
                <span className="chip gold">+{q.xp}</span>
              </div>
            ))}
          </div>
        </Card>
      )}

      {community && (
        <Card title="Цель недели" icon="🌍" tone="teal" wave={['#5ee8d8', '#43a8ff']}>
          <div style={{ fontWeight: 800, fontSize: 15 }}>
            Всем сообществом — {community.goal.toLocaleString('ru-RU')} отжиманий
          </div>
          <div style={{ margin: '10px 0 6px' }}><Bar value={community.total} max={community.goal} tone="blue" /></div>
          <div className="small muted" style={{ fontWeight: 700 }}>
            {community.total.toLocaleString('ru-RU')} от {community.people} {plural(community.people, 'участника', 'участников', 'участников')}
            {community.reached ? ' · цель выполнена! 🎉' : ` · награда +${community.reward} опыта и титул тем, кто сделал от ${community.minReps}`}
          </div>
          {p && <div className="small" style={{ fontWeight: 700, marginTop: 6 }}>Ваш вклад: {p.weekReps}</div>}
        </Card>
      )}

      <Card title="Что происходит" icon="📣" tone="blue">
        {feed?.length ? feed.slice(0, 7).map((f, i) => (
          <div key={i} className="feed-row"><span>{f.icon}</span><span>{f.text}</span><span className="when">{ago(f.at)}</span></div>
        )) : <div className="empty">Пока тихо. Сыграйте первую дуэль!</div>}
      </Card>

      {p && (
        <Card title="Мой прогресс" icon="📈" tone="violet" link="Профиль" onLink={() => goTab('me')}>
          <div className="stat-grid">
            <div className="stat"><b>{p.rating}</b><span>рейтинг</span></div>
            <div className="stat"><b>{p.wins}</b><span>побед</span></div>
            <div className="stat"><b>{p.bestReps}</b><span>рекорд</span></div>
            <div className="stat"><b>{p.totalReps}</b><span>всего</span></div>
          </div>
        </Card>
      )}
    </div>
  );
}

export function PlayerRow({ r, onClick, right }) {
  return (
    <button className={`row ${r.me ? 'me' : ''}`} onClick={onClick} style={{ textAlign: 'left', width: '100%' }}>
      {r.place !== undefined && <span className="place">{r.place <= 3 ? ['🥇', '🥈', '🥉'][r.place - 1] : r.place}</span>}
      <Avatar emoji={r.avatar} frame={r.frame} size="sm" />
      <div className="grow">
        <div className="title">{r.name}{r.me ? ' (вы)' : ''}</div>
        <div className="sub">{[r.clan && `${r.clan.emoji} ${r.clan.name}`, r.title, `ур. ${r.level}`].filter(Boolean).join(' · ')}</div>
      </div>
      {right}
    </button>
  );
}
