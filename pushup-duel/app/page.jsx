'use client';
import dynamic from 'next/dynamic';

// Приложение целиком клиентское: камера, Telegram SDK, WebSocket.
const App = dynamic(() => import('../components/App.jsx'), {
  ssr: false,
  loading: () => (
    <div className="splash"><div><div className="logo">PUSH<span>DUEL</span></div><div className="spinner" style={{ margin: '18px auto 0' }} /></div></div>
  ),
});

export default function Page() {
  return <App />;
}
