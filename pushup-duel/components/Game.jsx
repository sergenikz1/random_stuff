'use client';
import { Glyph, rich } from './Icon.jsx';
// Экран боя: камера + MediaPipe Pose + счётчик. Режимы: ranked и friend (онлайн, судья — сервер),
// bot (соперник-бот на клиенте) и training (без соперника).
import { useCallback, useEffect, useRef, useState } from 'react';
import { RepCounter, LM } from '../lib/counter.js';
import { BotOpponent } from '../lib/duel.js';
import { COMBO_GAP_MS } from '../lib/pass.js';
import { loadPose } from '../lib/client/pose.js';
import { createFx } from '../lib/client/effects.js';
import { sfx } from '../lib/client/sound.js';
import { api } from '../lib/client/net.js';
import { haptic, setClosingConfirmation, bindBackButton } from '../lib/client/tg.js';
import { music } from '../lib/client/music.js';
import { Avatar, fmtTime } from './ui.jsx';
import { VersusIntro } from './PlayerCard.jsx';
import { ClipRecorder, canRecord } from '../lib/client/recorder.js';

const COMBO_WORDS = ['Огонь!', 'Машина!', 'Неудержим!', 'Зверь!', 'Легенда!', 'Бог отжиманий!'];
const TAGS = { ranked: 'РЕЙТИНГОВЫЙ МАТЧ', friend: 'ДУЭЛЬ С ДРУГОМ', bot: 'БОЙ С БОТОМ', training: 'ТРЕНИРОВКА', tournament: 'ТУРНИР', title: 'ТИТУЛЬНЫЙ БОЙ', spar: 'СПАРРИНГ В КЛАНЕ', war: 'БОЙ ВОЙНЫ КЛАНОВ' };

export default function Game({ mode, durationMs, opponent, belt = null, socket, settings, updateSettings, profile, onDone, onCancel, inviteLabel = 'PushDuel в Telegram' }) {
  const online = ['ranked', 'friend', 'tournament', 'title', 'spar', 'war'].includes(mode);
  const videoRef = useRef(null);
  const canvasRef = useRef(null);
  const st = useRef({
    phase: 'loading', liveAt: 0, endAt: 0, my: 0, opp: 0, times: [], streak: 0, lastRepAt: -1e9,
    visibleSince: 0, readySent: false, bot: null, finished: false, chest: null,
    introDone: !opponent, hot: false,
  });
  const [showIntro, setShowIntro] = useState(!!opponent);
  const endIntro = useCallback(() => { st.current.introDone = true; setShowIntro(false); }, []);
  const [phase, setPhaseState] = useState('loading');
  const [err, setErr] = useState('');
  const [count, setCount] = useState({ my: 0, opp: 0, bumpMe: 0, bumpOpp: 0 });
  const [timeLeft, setTimeLeft] = useState(durationMs);
  const [countdown, setCountdown] = useState(null);
  const [depth, setDepth] = useState(0);
  const [seen, setSeen] = useState(false);
  const [oppReady, setOppReady] = useState(false);
  const [combo, setCombo] = useState(null);
  const [floaters, setFloaters] = useState([]);
  const [confirmGiveUp, setConfirmGiveUp] = useState(false);
  const [facing, setFacing] = useState(settings.facing);
  const counter = useRef(new RepCounter({ strictness: settings.strictness }));

  // ---- Запись ролика ----
  const recRef = useRef(null);   // идёт запись
  const clipRef = useRef(null);  // готовый ролик, если запись остановили вручную
  const [recOn, setRecOn] = useState(false);
  const [recMs, setRecMs] = useState(0);
  const recordable = useRef(canRecord()).current;
  const stopRec = useCallback(async () => {
    const r = recRef.current;
    if (!r) return clipRef.current;
    recRef.current = null;
    setRecOn(false);
    const clip = await r.stop();
    if (clip) { if (clipRef.current) URL.revokeObjectURL(clipRef.current.url); clipRef.current = clip; }
    return clipRef.current;
  }, []);
  const startRec = useCallback(() => {
    if (recRef.current || !videoRef.current) return;
    const s = st.current;
    const r = new ClipRecorder({
      video: videoRef.current,
      overlay: canvasRef.current,
      mirror: facing === 'user',
      getHud: () => ({
        tag: belt ? TAGS.title : TAGS[mode],
        time: fmtTime(s.phase === 'live' ? Math.max(0, s.endAt - performance.now()) : s.phase === 'judging' ? 0 : durationMs),
        me: { name: profile?.name || 'Я', reps: s.my },
        opp: opponent ? { name: opponent.name, reps: s.opp } : null,
        invite: inviteLabel,
      }),
    });
    try { r.start(() => { stopRec(); }); } catch { return; }
    recRef.current = r;
    setRecOn(true);
    haptic('light');
  }, [belt, mode, durationMs, profile, opponent, facing, inviteLabel, stopRec]);
  const toggleRec = () => (recRef.current ? stopRec() : startRec());
  // Ролик для итога: останавливаем запись, если она ещё идёт.
  const takeClip = useCallback(async () => { const c = await stopRec(); clipRef.current = null; return c; }, [stopRec]);
  useEffect(() => {
    if (!recOn) return undefined;
    const t = setInterval(() => setRecMs(recRef.current?.ms || 0), 500);
    return () => clearInterval(t);
  }, [recOn]);
  useEffect(() => () => { recRef.current?.stop(); }, []);
  useEffect(() => { if (recRef.current) recRef.current.mirror = facing === 'user'; }, [facing]);

  const setPhase = useCallback((p) => { st.current.phase = p; setPhaseState(p); }, []);
  const addFloater = useCallback((f) => {
    const id = Math.random();
    setFloaters((arr) => [...arr.slice(-8), { ...f, id }]);
    setTimeout(() => setFloaters((arr) => arr.filter((x) => x.id !== id)), 950);
  }, []);

  // ---- Итог одиночного режима (бот или тренировка) ----
  const finishSolo = useCallback(async (forced) => {
    const s = st.current;
    if (s.finished) return;
    s.finished = true;
    setPhase('judging');
    const elapsed = s.liveAt ? Math.min(durationMs, Math.max(0, performance.now() - s.liveAt)) : 0;
    let outcome = null;
    if (mode === 'bot') outcome = forced || (s.my > s.opp ? 'win' : s.my < s.opp ? 'loss' : 'draw');
    let progress = null, prof = null, error = '';
    try {
      const r = await api('solo', {
        method: 'POST',
        body: { type: mode, durationMs: Math.round(elapsed), repTimes: s.times.map(Math.round), outcome, oppReps: s.opp, oppName: opponent?.name },
      });
      progress = r.progress; prof = r.profile;
    } catch (e) { error = e.message; }
    const clip = await takeClip();
    onDone({ kind: 'solo', mode, outcome, durationMs, me: { reps: s.my }, opp: opponent ? { ...opponent, reps: s.opp } : null, progress, profile: prof, error, clip });
  }, [durationMs, mode, onDone, opponent, setPhase, takeClip]);

  const startCountdown = useCallback((ms) => {
    const s = st.current;
    if (s.phase === 'countdown' || s.phase === 'live') return;
    s.liveAt = performance.now() + ms;
    s.endAt = s.liveAt + durationMs;
    if (mode === 'bot') s.bot = new BotOpponent(opponent.rating, durationMs);
    setPhase('countdown');
  }, [durationMs, mode, opponent, setPhase]);

  // ---- Сообщения сервера ----
  useEffect(() => {
    if (!online) return undefined;
    const offs = [
      socket.on('start', (m) => startCountdown(m.countdownMs)),
      socket.on('opp_ready', () => setOppReady(true)),
      socket.on('opp_rep', (m) => {
        st.current.opp = m.count;
        setCount((c) => ({ ...c, opp: m.count, bumpOpp: c.bumpOpp + 1 }));
        addFloater({ opp: true, x: 86, y: 14, text: '+1' });
        sfx.opp();
      }),
      socket.on('match_over', async (m) => { st.current.finished = true; const clip = await takeClip(); onDone({ kind: 'online', ...m, clip }); }),
      socket.on('match_cancelled', (m) => { st.current.finished = true; onCancel(m.msg); }),
      socket.on('status', (s) => { if (s === 'offline' && !st.current.finished) setErr('Связь с сервером потеряна…'); }),
    ];
    return () => offs.forEach((f) => f());
  }, [online, socket, startCountdown, addFloater, onDone, onCancel, takeClip]);

  // ---- Камера ----
  useEffect(() => {
    let stream = null;
    let cancelled = false;
    (async () => {
      try {
        if (!navigator.mediaDevices?.getUserMedia) throw Object.assign(new Error('nocam'), { name: 'NoCamera' });
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: facing, width: { ideal: 640 }, height: { ideal: 480 }, frameRate: { ideal: 30 } },
          audio: false,
        });
        if (cancelled) { stream.getTracks().forEach((t) => t.stop()); return; }
        const v = videoRef.current;
        v.srcObject = stream;
        await v.play().catch(() => {});
        setErr('');
      } catch (e) {
        setErr(e.name === 'NotAllowedError'
          ? 'Нет доступа к камере. Разрешите его в настройках Telegram или браузера и нажмите «Повторить».'
          : 'Камера недоступна на этом устройстве.');
        setPhase('camera_error');
      }
    })();
    return () => { cancelled = true; stream?.getTracks().forEach((t) => t.stop()); };
  }, [facing, setPhase]);

  // ---- Экран не гаснет, закрытие — с подтверждением ----
  useEffect(() => {
    let lock = null;
    const req = () => navigator.wakeLock?.request('screen').then((l) => { lock = l; }).catch(() => {});
    req();
    const onVis = () => { if (document.visibilityState === 'visible') req(); };
    document.addEventListener('visibilitychange', onVis);
    setClosingConfirmation(true);
    return () => { document.removeEventListener('visibilitychange', onVis); lock?.release?.(); setClosingConfirmation(false); };
  }, []);

  // ---- Главный цикл ----
  useEffect(() => {
    let raf = 0;
    let landmarker = null;
    let lastVideoTime = -1;
    let lm = null;
    let lastUi = 0;
    const fx = createFx(canvasRef.current);
    const onResize = () => fx.resize();
    window.addEventListener('resize', onResize);

    loadPose().then((l) => {
      landmarker = l;
      if (st.current.phase === 'loading') setPhase('waiting');
    }).catch((e) => {
      console.error(e);
      setErr('Не удалось загрузить нейросеть позы. Проверьте интернет и попробуйте ещё раз.');
      setPhase('camera_error');
    });

    const toScreen = (p, v, mirror) => {
      const { w, h } = fx.size;
      const vw = v.videoWidth, vh = v.videoHeight;
      const scale = Math.max(w / vw, h / vh);
      const dx = (w - vw * scale) / 2, dy = (h - vh * scale) / 2;
      let x = p.x * vw * scale + dx;
      if (mirror) x = w - x;
      return { x, y: p.y * vh * scale + dy };
    };

    const loop = () => {
      raf = requestAnimationFrame(loop);
      const v = videoRef.current;
      const s = st.current;
      const now = performance.now();
      if (!v || !landmarker || v.readyState < 2 || !v.videoWidth) return;

      let res = null;
      // Во время интро не распознаём: первый прогон сети компилирует шейдеры и подвешивает страницу.
      if (!s.introDone) return;
      if (v.currentTime !== lastVideoTime) {
        lastVideoTime = v.currentTime;
        try {
          const out = landmarker.detectForVideo(v, now);
          lm = out.landmarks?.[0] || null;
        } catch { lm = null; }
        res = counter.current.update(lm, now);
      }

      // Отрисовка скелета и эффектов.
      fx.clear();
      const mirror = facing === 'user';
      if (lm && settings.skeleton) {
        const pt = (i) => (lm[i] && (lm[i].visibility ?? 1) > 0.4 ? toScreen(lm[i], v, mirror) : null);
        const pose = {
          ls: pt(LM.L_SHOULDER), rs: pt(LM.R_SHOULDER), le: pt(LM.L_ELBOW), re: pt(LM.R_ELBOW),
          lw: pt(LM.L_WRIST), rw: pt(LM.R_WRIST), nose: pt(LM.NOSE), leye: pt(LM.L_EYE), reye: pt(LM.R_EYE),
        };
        fx.drawPose(pose, profile?.equipped?.trail || 'classic', now);
        fx.drawSkin(pose, profile?.equipped?.skin, now);
        if (pose.ls && pose.rs) s.chest = { x: (pose.ls.x + pose.rs.x) / 2, y: (pose.ls.y + pose.rs.y) / 2 };
      } else {
        fx.drawPose(null);
      }
      fx.step(now);

      if (res) {
        // Ждём, пока человек встанет в кадр.
        if (s.phase === 'waiting' && s.introDone) {
          if (res.visible) {
            if (!s.visibleSince) s.visibleSince = now;
            if (now - s.visibleSince > 700) {
              if (online) {
                if (!s.readySent) { s.readySent = true; socket.send({ t: 'ready' }); setPhase('opp_wait'); }
              } else startCountdown(3000);
            }
          } else s.visibleSince = 0;
        }
        // Засчитанный повтор.
        if (res.rep && s.phase === 'live' && now <= s.endAt) {
          s.my += 1;
          s.times.push(now - s.liveAt);
          s.streak = now - s.lastRepAt <= COMBO_GAP_MS ? s.streak + 1 : 1;
          s.lastRepAt = now;
          if (online) socket.send({ t: 'rep' });
          setCount((c) => ({ ...c, my: s.my, bumpMe: c.bumpMe + 1 }));
          const { w, h } = fx.size;
          const at = s.chest || { x: w / 2, y: h * 0.55 };
          addFloater({ x: (at.x / w) * 100, y: (at.y / h) * 100 - 6, text: '+1' });
          fx.burst(profile?.equipped?.burst || 'pop', at.x, at.y);
          sfx.rep(s.streak);
          if (settings.haptics) haptic('medium');
          if (s.streak >= 5 && s.streak % 5 === 0) {
            const word = COMBO_WORDS[Math.min(COMBO_WORDS.length - 1, s.streak / 5 - 1)];
            setCombo({ word, n: s.streak, id: now });
            setTimeout(() => setCombo((c) => (c && c.id === now ? null : c)), 1300);
            fx.burst('ring', at.x, at.y);
            sfx.combo();
            if (settings.haptics) haptic('heavy');
          }
        }
      }

      // Отсчёт, таймер, бот.
      if (s.phase === 'countdown') {
        const left = s.liveAt - now;
        if (left <= 0) {
          setPhase('live');
          if (settings.music) music.play(profile?.equipped?.music || 'pulse');
          setCountdown('Старт!');
          sfx.go();
          if (settings.haptics) haptic('heavy');
          setTimeout(() => setCountdown(null), 700);
        } else {
          const n = Math.min(3, Math.ceil(left / 1000));
          if (s.cd !== n) {
            s.cd = n;
            setCountdown(n);
            sfx.tick();
            if (settings.haptics) haptic('light');
          }
        }
      }
      if (s.phase === 'live') {
        if (s.bot) {
          const added = s.bot.tick(now - s.liveAt);
          if (added) {
            s.opp = s.bot.count;
            setCount((c) => ({ ...c, opp: s.opp, bumpOpp: c.bumpOpp + 1 }));
            addFloater({ opp: true, x: 86, y: 14, text: '+1' });
            sfx.opp();
          }
        }
        if (!s.hot && s.endAt - now < 10000) { s.hot = true; music.setIntensity(2); }
        if (now >= s.endAt) {
          music.stop();
          if (online) setPhase('judging');
          else finishSolo();
        }
      }
      if (now - lastUi > 90) {
        lastUi = now;
        setDepth(res ? res.depth : counter.current.depth);
        setSeen(counter.current.visible);
        if (s.phase === 'live') setTimeLeft(Math.max(0, s.endAt - now));
      }
    };
    raf = requestAnimationFrame(loop);
    return () => { cancelAnimationFrame(raf); window.removeEventListener('resize', onResize); music.stop(); };
  }, [facing, online, socket, settings.skeleton, settings.haptics, settings.music, profile, startCountdown, finishSolo, addFloater, setPhase]);

  // Судья молчит дольше 10 секунд — что-то со связью.
  useEffect(() => {
    if (phase !== 'judging' || !online) return undefined;
    const t = setTimeout(() => onCancel('Сервер не прислал итог матча. Проверьте связь.'), 10000);
    return () => clearTimeout(t);
  }, [phase, online, onCancel]);

  const giveUp = useCallback(() => {
    const s = st.current;
    if (s.finished) return;
    if (!confirmGiveUp && (s.phase === 'live' || s.phase === 'countdown')) {
      setConfirmGiveUp(true);
      setTimeout(() => setConfirmGiveUp(false), 3000);
      return;
    }
    music.stop();
    if (online) {
      socket.send({ t: 'forfeit' });
      if (s.phase !== 'live' && s.phase !== 'countdown') { s.finished = true; onCancel(null); }
    } else if (s.phase === 'live') {
      finishSolo(mode === 'bot' ? 'loss' : undefined);
    } else {
      s.finished = true;
      onCancel(null);
    }
  }, [confirmGiveUp, online, socket, onCancel, finishSolo, mode]);

  useEffect(() => bindBackButton(giveUp), [giveUp]);

  const manualReady = () => {
    const s = st.current;
    if (online) { if (!s.readySent) { s.readySent = true; socket.send({ t: 'ready' }); setPhase('opp_wait'); } }
    else startCountdown(3000);
  };

  const flip = () => {
    const next = facing === 'user' ? 'environment' : 'user';
    setFacing(next);
    updateSettings({ facing: next });
  };

  const total = count.my + count.opp;
  const share = total ? (count.my / total) * 100 : 50;
  const elapsed = durationMs - timeLeft;
  const pace = elapsed > 3000 ? Math.round((count.my / elapsed) * 60000) : 0;

  return (
    <div className="game">
      <video ref={videoRef} className={facing === 'user' ? 'mirror' : ''} playsInline muted autoPlay />
      <canvas ref={canvasRef} />
      <div className="vignette" />

      <div className="hud">
        <div className="hud-tag">{rich(belt ? `🥊 ${TAGS.title}` : TAGS[mode])}</div>
        <div className="hud-row">
          <div className="hud-p">
            <Avatar emoji={profile?.avatar} look={profile?.look} frame={profile?.frame || profile?.equipped?.frame} size="sm" side="me" />
            <div style={{ minWidth: 0 }}><div className="n">{profile?.name}</div><div className="r">{profile?.rating}</div></div>
          </div>
          <div className={`hud-timer ${phase === 'live' && timeLeft < 10000 ? 'hot' : ''}`}>{fmtTime(phase === 'live' ? timeLeft : durationMs)}</div>
          {opponent ? (
            <div className="hud-p right">
              <div style={{ minWidth: 0 }}><div className="n">{opponent.name}</div><div className="r">{opponent.bot ? `бот · ${opponent.rating}` : opponent.rating}</div></div>
              <Avatar emoji={opponent.avatar} look={opponent.look} frame={opponent.frame} size="sm" side="opp" />
            </div>
          ) : <div className="hud-p right"><div className="r">темп {pace}/мин</div></div>}
        </div>
        {opponent && (
          <div className="score-row">
            <div key={`m${count.bumpMe}`} className="score me bump">{count.my}</div>
            <div className="tug"><div className="a" style={{ width: `${share}%` }} /><div className="b" /></div>
            <div key={`o${count.bumpOpp}`} className="score opp bump">{count.opp}</div>
          </div>
        )}
      </div>

      {!opponent && <div className="train-count">{count.my}<small>{phase === 'live' ? `отжиманий · ${pace} в минуту` : 'отжиманий'}</small></div>}

      {recOn && <div className="rec-badge"><i /> REC {fmtTime(recMs)}</div>}
      <div className="game-tools">
        {recordable && phase !== 'camera_error' && (
          <button className={`icon-btn rec-btn ${recOn ? 'on' : ''}`} onClick={toggleRec} aria-label={recOn ? 'Остановить запись' : 'Записать видео'}><i /></button>
        )}
        <button className="icon-btn" onClick={flip} aria-label="Сменить камеру"><Glyph e="🔄" size="1.25em" /></button>
        <button className="icon-btn" onClick={() => updateSettings({ sound: !settings.sound })} aria-label="Звук">{rich(settings.sound ? '🔊' : '🔇')}</button>
      </div>

      {seen && (
        <>
          <div className="depth-pct">{Math.round(depth * 100)}%</div>
          <div className="depth"><i style={{ height: `${Math.round(depth * 100)}%` }} /><div className="line" style={{ bottom: `${counter.current.downAt * 100}%` }} /></div>
        </>
      )}

      <div className="center-msg">
        {countdown !== null && <div key={String(countdown)} className="big" style={typeof countdown === 'string' ? { fontSize: 64 } : undefined}>{countdown}</div>}
        {countdown === null && phase === 'loading' && <div className="panel"><div className="spinner" style={{ margin: '0 auto 10px' }} /><div className="mid">Загружаю нейросеть…</div><div className="hint">Первый раз это займёт несколько секунд</div></div>}
        {countdown === null && phase === 'waiting' && (
          <div className="panel">
            <div className="mid">{seen ? 'Вижу вас! Замрите…' : 'Встаньте в упор лёжа'}</div>
            <div className="hint">Положите телефон на пол перед собой: в кадре должны быть плечи и кисти</div>
          </div>
        )}
        {countdown === null && phase === 'opp_wait' && (
          <div className="panel"><div className="mid">{oppReady ? 'Соперник готов!' : 'Ждём соперника…'}</div><div className="hint">Старт — как только он встанет в упор</div></div>
        )}
        {phase === 'judging' && <div className="panel"><div className="spinner" style={{ margin: '0 auto 10px' }} /><div className="mid">Подводим итоги…</div></div>}
        {phase === 'camera_error' && (
          <div className="panel"><div className="mid"><Glyph e="😕" size="1.25em" /></div><div className="hint">{err}</div>
            <button className="gel" style={{ marginTop: 12 }} onClick={() => location.reload()}>Повторить</button></div>
        )}
        {err && phase !== 'camera_error' && <div className="hint" style={{ marginTop: 80 }}>{err}</div>}
      </div>

      {combo && <div key={combo.id} className="combo">{combo.word}<small>Комбо ×{combo.n}</small></div>}
      {floaters.map((f) => <div key={f.id} className={`plus ${f.opp ? 'opp' : ''}`} style={{ left: `${f.x}%`, top: `${f.y}%` }}>{f.text}</div>)}

      {showIntro && phase !== 'loading' && phase !== 'camera_error' && <VersusIntro me={profile} opp={opponent} bot={!!opponent?.bot} mode={mode} belt={belt} onDone={endIntro} />}

      <div className="game-bottom">
        {(phase === 'waiting' || phase === 'loading') && <button className="gel green" onClick={manualReady} disabled={phase === 'loading'}>Я готов</button>}
        {phase !== 'judging' && (
          <button className="gel rose" onClick={giveUp}>
            {confirmGiveUp ? 'Точно сдаться?' : phase === 'live' ? (mode === 'training' ? '⏹ Закончить' : '🏳️ Сдаться') : '✕ Выйти'}
          </button>
        )}
      </div>
    </div>
  );
}
