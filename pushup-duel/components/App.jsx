'use client';
// Корневой компонент мини-приложения: соединение с сервером, вкладки и сценарии боя.
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Socket, api } from '../lib/client/net.js';
import { initTelegram, applyTelegramColors, telegramScheme, inTelegram, appLink, share, haptic, bindBackButton, startParam, tg, openInvoice } from '../lib/client/tg.js';
import { sfx, setSound, unlockAudio } from '../lib/client/sound.js';
import { useSettings } from '../lib/client/settings.js';
import { makeOpponent } from '../lib/duel.js';
import { loadPose } from '../lib/client/pose.js';
import { Sky, Toast } from './ui.jsx';
import Home from './Home.jsx';
import Top from './Top.jsx';
import Clan from './Clan.jsx';
import Pass from './Pass.jsx';
import Profile from './Profile.jsx';
import Lobby from './Lobby.jsx';
import Game from './Game.jsx';
import Result from './Result.jsx';
import { PlayerSheet, ClanSheet, ChallengedSheet } from './Sheets.jsx';
import Shop from './Shop.jsx';
import { PackOpenModal } from './Economy.jsx';
import TrophyMap from './TrophyMap.jsx';
import { CardEditor } from './Stickers.jsx';
import { startTilt } from '../lib/client/tilt.js';
import { Sheet } from './ui.jsx';
import { VolcanoSheet, ModeSheet } from './Greek.jsx';
import { InboxCard } from './Compete.jsx';

// Вкладки: плитка-иконка с глянцем (тон задаёт цвет), подпись.
const TABS = [['shop', '🏺', 'Агора', 'orange'], ['pass', '📜', 'Пропуск', 'violet'], ['home', '⚔️', 'Бой', 'blue'], ['clan', '🛡️', 'Клан', 'green'], ['top', '🏛️', 'Слава', 'gold']];

export default function App() {
  const [settings, updateSettings] = useSettings();
  const socketRef = useRef(null);
  const [status, setStatus] = useState('connecting');
  const [profile, setProfileRaw] = useState(null);
  // Отметка времени получения профиля — от неё идут таймеры сундуков.
  const setProfile = useCallback((pr) => setProfileRaw(pr ? { ...pr, _at: Date.now() } : pr), []);
  const [loot, setLoot] = useState(null);
  const [ecoBusy, setEcoBusy] = useState(false);
  const [config, setConfig] = useState({});
  const [stats, setStats] = useState(null);
  const [community, setCommunity] = useState(null);
  const [feed, setFeed] = useState([]);
  const [tab, setTab] = useState('home');
  const [flow, setFlow] = useState(null);
  const [sheet, setSheet] = useState(null);
  const [toastText, setToastText] = useState('');
  const [version, setVersion] = useState(0);
  const [fatal, setFatal] = useState(null);
  const [booted, setBooted] = useState(false);
  const [buying, setBuying] = useState(false);
  const flowRef = useRef(null);
  flowRef.current = flow;
  const toastTimer = useRef(0);

  const toast = useCallback((text) => {
    setToastText(text);
    clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToastText(''), 3200);
  }, []);

  // ---- Тема ----
  const theme = useMemo(() => {
    if (settings.theme !== 'auto') return settings.theme;
    if (typeof window === 'undefined') return 'aqua';
    const scheme = telegramScheme() || (window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    return scheme === 'dark' ? 'midnight' : 'aqua';
  }, [settings.theme]);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    applyTelegramColors(theme);
  }, [theme]);
  useEffect(() => { setSound(settings.sound); }, [settings.sound]);

  // ---- Данные, не требующие сокета ----
  const refresh = useCallback(() => {
    api('feed').then((r) => { setFeed(r.feed); setCommunity(r.community); setStats(r.stats); }).catch(() => {});
  }, []);

  useEffect(() => {
    initTelegram(theme);
    startTilt();
    api('me').then((r) => {
      setProfile(r.profile);
      setCommunity(r.community);
      setStats(r.stats);
      setConfig((c) => ({ ...c, ...r.config }));
      if (r.referral) toast(`+${r.referral.xp} опыта за приглашение от ${r.referral.inviter} 🎁`);
      if (r.communityBonus) toast(`Цель сообщества выполнена: +${r.communityBonus} опыта 🌍`);
    }).catch((e) => toast(e.message)).finally(() => setBooted(true));
    refresh();
    const t = setInterval(refresh, 30000);
    // Фоновая предзагрузка модели, когда главная уже отрисована и человек осматривается.
    const idle = setTimeout(() => { loadPose().catch(() => {}); }, 6000);
    const unlock = () => unlockAudio();
    window.addEventListener('pointerdown', unlock, { once: true });
    return () => { clearInterval(t); clearTimeout(idle); };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // ---- Сокет ----
  useEffect(() => {
    const s = new Socket();
    socketRef.current = s;
    const offs = [
      s.on('status', setStatus),
      s.on('welcome', (m) => {
        setProfile(m.profile);
        setStats(m.stats);
        setConfig((c) => ({ ...c, ...m.config }));
        // Параметр запуска срабатывает один раз за сессию.
        const sp = startParam();
        let done = '';
        try { done = sessionStorage.getItem('pd.sp') || ''; } catch { /* */ }
        if (sp && sp !== done) {
          try { sessionStorage.setItem('pd.sp', sp); } catch { /* */ }
          const [kind, val] = sp.split('_');
          if (kind === 'duel') { setFlow({ kind: 'join', code: val }); s.send({ t: 'room_join', code: val }); }
          if (kind === 'clan') setSheet({ kind: 'clan', id: val, invite: true });
        }
      }),
      s.on('stats', (m) => setStats({ online: m.online, queue: m.queue, playing: m.playing })),
      s.on('queued', (m) => setStats({ online: m.online, queue: m.queue, playing: m.playing })),
      s.on('profile', (m) => setProfile(m.profile)),
      s.on('room_created', (m) => setFlow({ kind: 'room', code: m.code, durationSec: m.durationSec, invited: m.invited, title: m.title })),
      s.on('matched', (m) => {
        loadPose().catch(() => {});
        sfx.found();
        haptic('success');
        setSheet(null);
        setFlow({ kind: 'game', mode: m.mode, durationMs: m.durationMs, opponent: m.opponent, belt: m.belt || null, key: m.matchId });
      }),
      s.on('challenged', (m) => { if (!flowRef.current) { haptic('warning'); setSheet({ kind: 'challenged', ...m }); } }),
      s.on('challenge_sent', (m) => toast(m.delivered ? `Вызов отправлен: ${m.to}` : `${m.to} не в сети — отправьте ссылку сами`)),
      s.on('error', (m) => {
        toast(m.msg);
        const f = flowRef.current;
        if (f && f.kind !== 'game' && f.kind !== 'result' && ['room_join', 'queue', 'room_create', 'challenge'].includes(m.req)) setFlow(null);
      }),
      s.on('kicked', (m) => setFatal(m.msg)),
      s.on('auth_failed', () => setFatal('Не удалось войти. Откройте приложение из Telegram заново.')),
    ];
    return () => { offs.forEach((f) => f()); s.close(); };
  }, [toast]);

  const send = (m) => socketRef.current?.send(m);

  // ---- Сценарии ----
  // Нейросеть позы грузится несколько секунд и на это время подвешивает страницу —
  // начинаем загрузку заранее, пока человек ищет соперника.
  const preload = () => { loadPose().catch(() => {}); };
  const startRanked = () => { preload(); unlockAudio(); setFlow({ kind: 'queue' }); send({ t: 'queue' }); };
  const startFriend = () => { preload(); unlockAudio(); setFlow({ kind: 'room' }); send({ t: 'room_create', durationSec: settings.friendSec }); };
  const startJoin = () => { preload(); setSheet(null); setFlow({ kind: 'join' }); };
  const startTraining = () => { unlockAudio(); setFlow({ kind: 'game', mode: 'training', durationMs: settings.soloSec * 1000, opponent: null, key: Date.now() }); };
  const startBot = () => {
    unlockAudio();
    if (flowRef.current?.kind === 'queue') send({ t: 'unqueue' });
    const opp = { ...makeOpponent(profile?.rating || 1000, profile?.avatar), bot: true };
    setFlow({ kind: 'game', mode: 'bot', durationMs: settings.soloSec * 1000, opponent: opp, key: Date.now() });
  };
  // «В бой» запускает формат, выбранный в окне режима (по умолчанию — рейтинг).
  const playSelected = () => {
    const f = settings.format || 'ranked';
    if (f === 'friend') startFriend();
    else if (f === 'training') startTraining();
    else if (f === 'bot') startBot();
    else startRanked();
  };

  const challenge = (player, title = false) => {
    setSheet(null);
    preload();
    setFlow({ kind: 'room', invited: player.name, title });
    send({ t: 'challenge', playerId: player.id, durationSec: settings.friendSec, title });
  };
  const titleChallenge = (holder) => challenge(holder, true);
  // Очереди: турнирный забег (small/big), спарринг в клане (clan), бой клановой войны (war).
  const queueKind = (kind) => { preload(); unlockAudio(); setSheet(null); setFlow({ kind: 'queue', queue: kind }); send({ t: 'queue', kind }); };
  const tourneyStart = (kind) => ecoCall(`tourney/${kind}/start`, null, () => { haptic('success'); sfx.level(); toast('Вход оплачен — удачи в турнире! ⚔️'); setVersion((v) => v + 1); });
  const tourneyPlay = (kind) => queueKind(kind);
  const clanCall = (path, body, msg) => ecoCall(path, body, () => { haptic('success'); if (msg) toast(msg); setVersion((v) => v + 1); });
  const cancelLobby = useCallback(() => {
    const f = flowRef.current;
    if (f?.kind === 'queue') socketRef.current?.send({ t: 'unqueue' });
    if (f?.kind === 'room') socketRef.current?.send({ t: 'room_leave' });
    setFlow(null);
  }, []);

  const onGameDone = useCallback((data) => {
    if (data.profile) setProfile(data.profile);
    setFlow({ kind: 'result', data });
    setVersion((v) => v + 1);
    refresh();
  }, [refresh]);
  const onGameCancel = useCallback((msg) => { setFlow(null); if (msg) toast(msg); }, [toast]);

  const saveProfile = async (body) => {
    try { const r = await api('profile', { method: 'POST', body }); setProfile(r.profile); haptic('select'); } catch (e) { toast(e.message); }
  };
  const equip = (key) => {
    const [cat, id] = key.split(':');
    saveProfile(cat === 'avatar' ? { avatar: id } : { equip: { cat, id } });
  };

  // Покупка премиума: счёт в звёздах → оплата в Telegram → бот подтверждает → профиль обновится.
  const buyPremium = async () => {
    setBuying(true);
    try {
      const r = await api('premium/invoice', { method: 'POST' });
      if (r.dev) {
        const d = await api('premium/dev', { method: 'POST' });
        setProfile(d.profile);
        toast('Режим разработки: премиум выдан без оплаты');
        return;
      }
      const status = await openInvoice(r.link);
      if (status === 'paid') {
        haptic('success');
        toast('Оплата прошла! Активируем пропуск…');
        for (let i = 0; i < 10; i++) {
          const me = await api('me');
          if (me.profile.premium) { setProfile(me.profile); toast('Премиум-пропуск активирован 🌟'); sfx.level(); break; }
          await new Promise((res) => setTimeout(res, 1500));
        }
      } else if (status === 'failed') toast('Оплата не прошла');
    } catch (e) { toast(e.message); } finally { setBuying(false); }
  };
  const claimGift = async (level) => {
    try {
      const r = await api(`gifts/${level}`, { method: 'POST' });
      setProfile(r.profile);
      if (r.status === 'sent') { haptic('success'); toast('Подарок отправлен — загляните в профиль Telegram 🎁'); } else toast(r.error || 'Не получилось, попробуйте позже');
    } catch (e) { toast(e.message); }
  };

  // ---- Экономика ----
  const ecoCall = async (path, body, after) => {
    setEcoBusy(true);
    try {
      const r = await api(path, { method: 'POST', body });
      setProfile(r.profile);
      if (after) after(r);
    } catch (e) { toast(e.message); } finally { setEcoBusy(false); }
  };
  const unlockPack = (slot) => ecoCall(`packs/${slot}/unlock`, null, () => { haptic('select'); toast('Набор открывается ⏳'); });
  const openPack = (slot, withGems, gems) => {
    if (withGems) { setSheet({ kind: 'packGems', slot, gems }); return; }
    ecoCall(`packs/${slot}/open`, {}, (r) => setLoot(r.loot));
  };
  const claimInbox = (id) => ecoCall(`inbox/${id}`, null, (r) => {
    haptic('success');
    if (r.loot) setLoot(r.loot);
    else if (r.gift) toast(r.gift.status === 'sent' ? 'Подарок отправлен 🎁' : r.gift.error || 'Не получилось');
    else toast('Награда получена');
  });
  const saveCard = (items) => ecoCall('card', { items }, () => { setSheet(null); haptic('success'); toast('Карточка обновлена ✨'); });
  const claimRoad = (at) => ecoCall(`road/${at}/claim`, null, () => { sfx.level(); haptic('success'); toast('Награда получена 🏆'); });
  const shopBuy = (id) => ecoCall(`shop/${id}`, null, (r) => {
    haptic('success');
    if (r.loot) setLoot(r.loot);
    else if (r.name) toast(`Куплено: ${r.name}`);
    else if (r.boostUntil) toast('Буст ×2 активирован ⚡');
  });

  const refLink = () => appLink(config, `ref_${profile?.id || ''}`);
  const doShare = async (url, text) => {
    const r = await share(url, text);
    if (r === 'copied') toast('Ссылка скопирована');
  };
  const invite = () => doShare(refLink(), 'Го отжиматься в PushDuel! Дуэли онлайн, камера сама считает повторы 💪');
  const shareRoom = () => flow?.code && doShare(appLink(config, `duel_${flow.code}`), `Вызываю тебя на дуэль отжиманий! Комната ${flow.code} ⚔️`);
  const shareClan = (c) => doShare(appLink(config, `clan_${c.id}`), `Вступай в мой клан «${c.name}» в PushDuel ${c.emoji}`);
  const shareResult = (data) => {
    const me = data.me.reps;
    const text = data.opp
      ? `${{ win: 'Победил', loss: 'Проиграл', draw: 'Сыграл вничью с' }[data.outcome] || 'Сразился с'} ${data.opp.name} в PushDuel — ${me}:${data.opp.reps}! Сможешь больше? 💪`
      : `Сделал ${me} отжиманий в PushDuel. Сможешь больше? 💪`;
    doShare(refLink(), text);
  };
  const again = (data) => {
    if (data.mode === 'ranked') startRanked();
    else if (data.mode === 'tournament' && data.run && !data.run.done) tourneyPlay(data.run.kind);
    else if (data.mode === 'tournament') setTab('top');
    else if (data.mode === 'spar') queueKind('clan');
    else if (data.mode === 'war') queueKind('war');
    else if (data.mode === 'title' && data.opp?.id) challenge(data.opp, !!data.opp.belt);
    else if (data.mode === 'friend' && data.opp?.id) challenge(data.opp);
    else if (data.mode === 'bot') startBot();
    else startTraining();
  };

  // «Назад» в Telegram с вкладки — на главную.
  useEffect(() => {
    if (tab === 'home' || flow) return undefined;
    return bindBackButton(() => setTab('home'));
  }, [tab, flow]);

  const closeSheet = useCallback(() => setSheet(null), []);
  const openPlayer = useCallback((id) => setSheet({ kind: 'player', id }), []);
  const openClan = useCallback((id) => setSheet({ kind: 'clan', id }), []);

  if (fatal) {
    return (
      <div className="splash"><div className="center" style={{ padding: 24 }}>
        <div className="logo">PUSH<span>DUEL</span></div>
        <p style={{ marginTop: 14, fontWeight: 700 }}>{fatal}</p>
        <button className="gel" style={{ marginTop: 16 }} onClick={() => (tg()?.close ? tg().close() : location.reload())}>Закрыть</button>
      </div></div>
    );
  }

  if (!booted && !profile) {
    return <div className="splash"><div><div className="logo">PUSH<span>DUEL</span></div><div className="spinner" style={{ margin: '18px auto 0' }} /></div></div>;
  }

  if (flow?.kind === 'game') {
    return (
      <>
        <Game key={flow.key} mode={flow.mode} durationMs={flow.durationMs} opponent={flow.opponent} belt={flow.belt}
          socket={socketRef.current} settings={settings} updateSettings={updateSettings} profile={profile}
          onDone={onGameDone} onCancel={onGameCancel} />
        <Toast text={toastText} />
      </>
    );
  }

  return (
    <>
      <Sky />
      {flow?.kind === 'result' ? (
        <Result key={flow.data.at || flow.data.durationMs + flow.data.me.reps} data={flow.data} profile={profile}
          onAgain={() => again(flow.data)} onHome={() => setFlow(null)} onShare={() => shareResult(flow.data)} onEquip={equip} />
      ) : flow ? (
        <Lobby flow={flow} profile={profile} stats={stats} onCancel={cancelLobby} onBot={startBot} onShareRoom={shareRoom}
          onJoin={(code) => { setFlow({ kind: 'join', code }); send({ t: 'room_join', code }); }} />
      ) : (
        <div className="shell">
          {tab === 'shop' && <Shop profile={profile} onBuy={shopBuy} onPremium={buyPremium} busy={ecoBusy} />}
          {tab === 'home' && (
            <Home profile={profile} status={status} settings={settings} theme={theme} version={version}
              onPlay={playSelected} onMode={() => setSheet({ kind: 'mode' })} onStreak={() => setSheet({ kind: 'streak' })}
              onUnlockPack={unlockPack} onOpenPack={openPack} onRoad={() => setSheet({ kind: 'road' })} onProfile={() => setTab('me')}
              onInbox={() => setSheet({ kind: 'inbox' })} />
          )}
          {tab === 'top' && <Top openPlayer={openPlayer} openClan={openClan} toast={toast} version={version} onTitleChallenge={titleChallenge}
            profile={profile} onTourneyStart={tourneyStart} onTourneyPlay={tourneyPlay} status={status} feed={feed} community={community} />}
          {tab === 'clan' && <Clan profile={profile} config={config} openPlayer={openPlayer} openClan={openClan} toast={toast} onShareClan={shareClan} onChanged={() => { setVersion((v) => v + 1); api('me').then((r) => setProfile(r.profile)).catch(() => {}); }} version={version}
            status={status} onSpar={() => queueKind('clan')} onWarDuel={() => queueKind('war')} onChallenge={challenge}
            onDeclareWar={(clanId) => clanCall('clans/war/declare', { clanId }, 'Вызов отправлен капитану ⚔️')}
            onRespondWar={(id, action) => clanCall(`clans/war/${id}`, { action }, action === 'accept' ? 'Война началась! ⚔️' : 'Вызов отклонён')} />}
          {tab === 'pass' && <Pass profile={profile} onEquip={equip} onBuy={buyPremium} onClaimGift={claimGift} buying={buying} />}
          {tab === 'me' && <Profile key={profile?.id} profile={profile} settings={settings} updateSettings={updateSettings} onSave={saveProfile} onInvite={invite} inTg={inTelegram()} onEditCard={() => setSheet({ kind: 'card' })} />}
          <nav className="tabbar">
            {TABS.map(([id, icon, label, tone]) => (
              <button key={id} className={tab === id ? 'on' : ''} onClick={() => { setTab(id); haptic('select'); }}>
                <span className={`tile ${tone}`}>{icon}</span>{label}
              </button>
            ))}
          </nav>
        </div>
      )}

      {sheet?.kind === 'streak' && <VolcanoSheet profile={profile} onClose={closeSheet} />}
      {sheet?.kind === 'mode' && <ModeSheet settings={settings} updateSettings={updateSettings} onClose={closeSheet} onJoinCode={startJoin} toast={toast} />}
      {sheet?.kind === 'inbox' && (
        <Sheet onClose={closeSheet}>
          {profile?.eco?.inbox?.length ? <InboxCard eco={profile.eco} onClaim={(id) => { setSheet(null); claimInbox(id); }} /> : <div className="empty">Наград нет</div>}
        </Sheet>
      )}
      {sheet?.kind === 'road' && <TrophyMap eco={profile?.eco} profile={profile} theme={theme} onClose={closeSheet} onClaim={claimRoad} />}
      {sheet?.kind === 'card' && profile && <CardEditor profile={profile} onSave={saveCard} onClose={closeSheet} />}
      {sheet?.kind === 'packGems' && (
        <Sheet onClose={closeSheet} center>
          <h2 className="center">Открыть сейчас?</h2>
          <p className="center muted" style={{ fontWeight: 700 }}>Набор откроется мгновенно за {sheet.gems} 💎 (у вас {profile?.eco?.gems ?? 0})</p>
          <button className="gel xl violet" disabled={ecoBusy || (profile?.eco?.gems ?? 0) < sheet.gems}
            onClick={() => { const slot = sheet.slot; setSheet(null); ecoCall(`packs/${slot}/open`, { gems: true }, (r) => setLoot(r.loot)); }}>💎 {sheet.gems} — открыть</button>
          <button className="glass-btn" onClick={closeSheet}>Подождать</button>
        </Sheet>
      )}
      {loot && <PackOpenModal loot={loot} onClose={() => setLoot(null)} />}
      {sheet?.kind === 'player' && <PlayerSheet id={sheet.id} onClose={closeSheet} toast={toast} canChallenge={status === 'online'} onChallenge={challenge} myArena={profile?.eco?.arena?.id} />}
      {sheet?.kind === 'clan' && <ClanSheet id={sheet.id} invite={sheet.invite} onClose={closeSheet} toast={toast} openPlayer={openPlayer} onJoined={() => { setVersion((v) => v + 1); setTab('clan'); }} />}
      {sheet?.kind === 'challenged' && (
        <ChallengedSheet from={sheet.from} durationSec={sheet.durationSec} onClose={closeSheet}
          onAccept={() => { const code = sheet.code; setSheet(null); setFlow({ kind: 'join', code }); send({ t: 'room_join', code }); }} />
      )}
      <Toast text={toastText} />
    </>
  );
}
