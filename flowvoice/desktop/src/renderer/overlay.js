'use strict';

const BARS = 22;
const pill = document.getElementById('pill');
const dot = document.getElementById('dot');
const barsEl = document.getElementById('bars');
const labelEl = document.getElementById('label');
const hintEl = document.getElementById('hint');

for (let i = 0; i < BARS; i += 1) barsEl.appendChild(document.createElement('i'));
const bars = Array.from(barsEl.children);

const T = {
  ru: {
    listening: 'Говорите',
    working: 'Расшифровка…',
    empty: 'Ничего не расслышал',
    noKey: 'Добавьте API-ключ в настройках',
    mic: 'Нет доступа к микрофону',
    error: 'Ошибка',
    pasted: 'Вставлено',
    copied: 'Скопировано'
  },
  en: {
    listening: 'Listening',
    working: 'Transcribing…',
    empty: 'Nothing was heard',
    noKey: 'Add your API key in Settings',
    mic: 'No microphone access',
    error: 'Error',
    pasted: 'Inserted',
    copied: 'Copied'
  }
};

let uiLang = 'ru';
const t = (key) => (T[uiLang] || T.ru)[key];

/* ------------------------------------------------------------ audio capture */

let stream = null;
let recorder = null;
let chunks = [];
let audioCtx = null;
let analyser = null;
let rafId = 0;
let startedAt = 0;
let silenceSince = 0;
let sawSpeech = false;
let cancelled = false;
let opts = {};
let hardStopTimer = 0;

function resetBars() {
  bars.forEach((b) => { b.style.height = '4px'; });
}

function pickMime() {
  const candidates = [
    'audio/webm;codecs=opus',
    'audio/webm',
    'audio/ogg;codecs=opus',
    'audio/mp4'
  ];
  for (const m of candidates) {
    if (window.MediaRecorder && MediaRecorder.isTypeSupported(m)) return m;
  }
  return '';
}

function meter() {
  if (!analyser) return;
  const buf = new Uint8Array(analyser.frequencyBinCount);
  analyser.getByteFrequencyData(buf);

  const step = Math.max(1, Math.floor(buf.length * 0.55 / BARS));
  let sum = 0;
  for (let i = 0; i < BARS; i += 1) {
    let acc = 0;
    for (let j = 0; j < step; j += 1) acc += buf[i * step + j] || 0;
    const v = acc / step;
    sum += v;
    const h = Math.max(4, Math.min(28, (v / 255) * 46 + 4));
    bars[i].style.height = `${h}px`;
  }

  const level = sum / BARS;
  const now = performance.now();
  if (level > 16) {
    sawSpeech = true;
    silenceSince = now;
  } else if (!silenceSince) {
    silenceSince = now;
  }

  if (opts.autoStopEnabled && sawSpeech && now - silenceSince > (opts.autoStopSilenceMs || 1600)) {
    window.flow.autoStop();
    finish();
    return;
  }

  rafId = requestAnimationFrame(meter);
}

async function begin(options) {
  opts = options || {};
  cancelled = false;
  sawSpeech = false;
  silenceSince = 0;
  chunks = [];

  try {
    const audio = { echoCancellation: true, noiseSuppression: true, autoGainControl: true };
    if (opts.deviceId && opts.deviceId !== 'default') audio.deviceId = { exact: opts.deviceId };
    stream = await navigator.mediaDevices.getUserMedia({ audio });
  } catch (err) {
    window.flow.micError(String(err && err.message ? err.message : err));
    return;
  }

  audioCtx = new AudioContext();
  analyser = audioCtx.createAnalyser();
  analyser.fftSize = 512;
  analyser.smoothingTimeConstant = 0.72;
  audioCtx.createMediaStreamSource(stream).connect(analyser);

  const mimeType = pickMime();
  recorder = new MediaRecorder(stream, mimeType ? { mimeType, audioBitsPerSecond: 96000 } : undefined);
  recorder.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data); };
  recorder.onstop = onStopped;
  recorder.start(250);
  startedAt = performance.now();

  hardStopTimer = setTimeout(finish, Math.max(5, opts.maxRecordSeconds || 120) * 1000);
  rafId = requestAnimationFrame(meter);
}

function teardown() {
  cancelAnimationFrame(rafId);
  clearTimeout(hardStopTimer);
  rafId = 0;
  if (stream) stream.getTracks().forEach((tr) => tr.stop());
  stream = null;
  if (audioCtx) audioCtx.close().catch(() => {});
  audioCtx = null;
  analyser = null;
  resetBars();
}

function finish() {
  if (recorder && recorder.state !== 'inactive') recorder.stop();
  else teardown();
}

async function onStopped() {
  const durationMs = Math.round(performance.now() - startedAt);
  const mime = (recorder && recorder.mimeType) || 'audio/webm';
  const blob = new Blob(chunks, { type: mime });
  teardown();
  recorder = null;

  if (cancelled) return;
  // Under ~350 ms or a few hundred bytes there is nothing worth sending.
  if (!sawSpeech || durationMs < 350 || blob.size < 1200) {
    window.flow.aborted('silence');
    return;
  }
  const buf = await blob.arrayBuffer();
  window.flow.sendAudio(new Uint8Array(buf), mime.split(';')[0], durationMs);
}

/* ------------------------------------------------------------------- render */

function render(payload) {
  const s = payload.settings || {};
  uiLang = s.uiLanguage === 'en' ? 'en' : 'ru';
  pill.classList.remove('error', 'done');
  dot.className = '';
  barsEl.classList.remove('hide');
  labelEl.classList.remove('show');
  hintEl.textContent = '';

  switch (payload.state) {
    case 'recording':
      pill.classList.add('in');
      hintEl.textContent = s.hotkey ? shortHotkey(s.hotkey) : '';
      break;
    case 'working':
      dot.className = 'working';
      barsEl.classList.add('hide');
      labelEl.classList.add('show');
      labelEl.textContent = t('working');
      break;
    case 'done':
      pill.classList.add('done');
      dot.className = 'done';
      barsEl.classList.add('hide');
      labelEl.classList.add('show');
      labelEl.textContent = payload.text || '';
      hintEl.textContent = payload.mode === 'pasted' ? t('pasted') : t('copied');
      break;
    case 'empty':
      dot.className = 'done';
      barsEl.classList.add('hide');
      labelEl.classList.add('show');
      labelEl.textContent = t('empty');
      break;
    case 'error':
      pill.classList.add('error');
      dot.className = 'error';
      barsEl.classList.add('hide');
      labelEl.classList.add('show');
      labelEl.textContent = payload.code === 'NO_API_KEY'
        ? t('noKey')
        : payload.code === 'MIC' ? t('mic') : `${t('error')}: ${payload.message || ''}`;
      break;
    default:
      pill.classList.remove('in');
      resetBars();
  }
}

function shortHotkey(hk) {
  return String(hk)
    .replace(/CommandOrControl|Control/g, 'Ctrl')
    .replace(/\+/g, ' + ');
}

/* ---------------------------------------------------------------------- ipc */

window.flow.onStart((options) => {
  pill.classList.add('in');
  begin(options);
});
window.flow.onStop(() => finish());
window.flow.onCancel(() => {
  cancelled = true;
  if (recorder && recorder.state !== 'inactive') recorder.stop();
  else teardown();
  pill.classList.remove('in');
});
window.flow.onState(render);

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') window.flow.cancel();
});
