import Script from 'next/script';
import { Nunito } from 'next/font/google';
import './globals.css';

const font = Nunito({ subsets: ['latin', 'cyrillic'], weight: ['500', '600', '700', '800', '900'], variable: '--font', display: 'swap' });

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
  themeColor: '#1566cc',
};

export default function RootLayout({ children }) {
  return (
    <html lang="ru" className={font.variable} data-theme="aqua" suppressHydrationWarning>
      <body>
        <Script src="https://telegram.org/js/telegram-web-app.js" strategy="beforeInteractive" />
        {children}
      </body>
    </html>
  );
}
