'use client';
// Поиск соперника и комната с другом.
import { useEffect, useState } from 'react';
import { bindBackButton } from '../lib/client/tg.js';
import { Avatar, fmtTime } from './ui.jsx';

export default function Lobby({ flow, profile, stats, onCancel, onBot, onShareRoom, onJoin }) {
  const [elapsed, setElapsed] = useState(0);
  const [code, setCode] = useState('');
  useEffect(() => {
    const start = Date.now();
    const t = setInterval(() => setElapsed(Date.now() - start), 500);
    return () => clearInterval(t);
  }, [flow.kind, flow.code]);
  useEffect(() => bindBackButton(onCancel), [onCancel]);

  if (flow.kind === 'join' && flow.code) {
    return (
      <div className="lobby">
        <div className="spinner" />
        <h1>Входим в комнату {flow.code}…</h1>
        <button className="glass-btn" onClick={onCancel}>Отмена</button>
      </div>
    );
  }

  if (flow.kind === 'join') {
    return (
      <div className="lobby">
        <h1>Код комнаты</h1>
        <p>Попросите друга создать комнату и продиктовать код</p>
        <input className="input" inputMode="numeric" maxLength={4} autoFocus value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
          style={{ maxWidth: 220, textAlign: 'center', fontSize: 34, fontWeight: 900, letterSpacing: '0.3em' }} />
        <button className="gel xl" style={{ maxWidth: 320 }} disabled={code.length !== 4} onClick={() => onJoin(code)}>Войти</button>
        <button className="glass-btn" onClick={onCancel}>Отмена</button>
      </div>
    );
  }

  if (flow.kind === 'room') {
    return (
      <div className="lobby">
        <div className="radar"><span /><span /><span /><Avatar emoji={profile?.avatar} frame={profile?.frame} size="lg" side="me" /></div>
        <h1>{flow.invited ? `Ждём ${flow.invited}…` : 'Комната создана'}</h1>
        {flow.code ? <div className="code">{flow.code}</div> : <div className="spinner" />}
        <p>{flow.invited ? 'Мы отправили вызов в Telegram. Или скиньте ссылку сами:' : `Дуэль ${fmtTime((flow.durationSec || 60) * 1000)}. Отправьте другу ссылку или код:`}</p>
        <button className="gel xl" style={{ maxWidth: 360 }} disabled={!flow.code} onClick={onShareRoom}>📨 Позвать друга</button>
        <button className="glass-btn" onClick={onCancel}>Закрыть комнату</button>
      </div>
    );
  }

  // Рейтинговая очередь.
  return (
    <div className="lobby">
      <div className="radar"><span /><span /><span /><Avatar emoji={profile?.avatar} frame={profile?.frame} size="lg" side="me" /></div>
      <h1>Ищем соперника…</h1>
      <p>{fmtTime(elapsed)} · рейтинг {profile?.rating} · онлайн {stats?.online ?? '—'}, в поиске {stats?.queue ?? '—'}</p>
      {elapsed > 20000 && (
        <>
          <p style={{ opacity: 0.85 }}>Пока никого — позовите друзей или сразитесь с ботом</p>
          <button className="gel orange" onClick={onBot}>🤖 Бой с ботом</button>
        </>
      )}
      <button className="glass-btn" onClick={onCancel}>Отмена</button>
    </div>
  );
}
