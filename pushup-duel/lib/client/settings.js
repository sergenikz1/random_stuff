// Настройки устройства: хранятся в браузере, на сервер не уходят.
import { useCallback, useEffect, useState } from 'react';

const KEY = 'pd.settings';
export const DEFAULT_SETTINGS = {
  soloSec: 60,          // длительность тренировки и боя с ботом
  friendSec: 60,        // длительность дуэли с другом
  strictness: 'normal', // строгость засчёта
  facing: 'user',       // фронтальная камера
  sound: true,
  music: true,          // музыка во время боя
  haptics: true,
  skeleton: true,
  theme: 'auto',        // auto | aqua (Эгея, светлая) | midnight (Амфора, тёмная)
  exercise: 'pushups',  // упражнение (пока доступны только отжимания)
  format: 'ranked',     // формат боя по кнопке «В бой»: ranked | friend | training | bot
};

function read() {
  try { return { ...DEFAULT_SETTINGS, ...JSON.parse(localStorage.getItem(KEY) || '{}') }; } catch { return { ...DEFAULT_SETTINGS }; }
}

export function useSettings() {
  const [s, setS] = useState(DEFAULT_SETTINGS);
  useEffect(() => { setS(read()); }, []);
  const update = useCallback((patch) => {
    setS((prev) => {
      const next = { ...prev, ...patch };
      try { localStorage.setItem(KEY, JSON.stringify(next)); } catch { /* приватный режим */ }
      return next;
    });
  }, []);
  return [s, update];
}
