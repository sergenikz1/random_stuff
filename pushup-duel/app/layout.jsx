import Script from 'next/script';
import { Manrope, Cormorant_SC } from 'next/font/google';
import './globals.css';

const font = Manrope({ subsets: ['latin', 'cyrillic'], weight: ['500', '600', '700', '800'], variable: '--font', display: 'swap' });
// Заголовки — капитель в духе античных надписей.
const display = Cormorant_SC({ subsets: ['latin', 'cyrillic'], weight: ['600', '700'], variable: '--font-display', display: 'swap' });

export const metadata = {
  title: 'PushDuel',
  description: 'Дуэли отжиманий онлайн: камера считает повторы, соперник отжимается одновременно с тобой.',
};

export const viewport = {
  width: 'device-width',
  initialScale: 1,
  maximumScale: 1,
  userScalable: false,
  viewportFit: 'cover',
  themeColor: '#f3f6fb',
};

export default function RootLayout({ children }) {
  return (
    <html lang="ru" className={`${font.variable} ${display.variable}`} data-theme="aqua" suppressHydrationWarning>
      <body>
        <Script src="https://telegram.org/js/telegram-web-app.js" strategy="beforeInteractive" />
        {children}
      </body>
    </html>
  );
}
