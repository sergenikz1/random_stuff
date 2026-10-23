'use strict';
const fs = require('fs');
const path = require('path');
const { app } = require('electron');

const DEFAULTS = {
  // Transcription backend (any OpenAI-compatible /audio/transcriptions endpoint)
  apiBaseUrl: 'https://api.openai.com/v1',
  apiKey: '',
  sttModel: 'whisper-1',

  // Languages: 'auto' | 'en' | 'ru'
  language: 'auto',

  // Optional LLM post-processing (punctuation, filler removal, dictionary)
  cleanup: true,
  cleanupModel: 'gpt-4o-mini',

  // Capture
  hotkey: 'Control+Shift+Space',
  cancelKey: 'Escape',
  autoStopEnabled: true,
  autoStopSilenceMs: 1600,
  maxRecordSeconds: 120,
  inputDeviceId: 'default',

  // Output
  pasteMode: 'auto', // 'auto' = clipboard + Ctrl+V, 'clipboard' = clipboard only
  restoreClipboard: true,
  trailingSpace: true,

  // Misc
  uiLanguage: 'ru',
  playSounds: true,
  launchAtLogin: false,
  dictionary: [],
  historyLimit: 200
};

let cachedPath = null;
function filePath() {
  if (!cachedPath) cachedPath = path.join(app.getPath('userData'), 'settings.json');
  return cachedPath;
}

function read() {
  try {
    const raw = fs.readFileSync(filePath(), 'utf8');
    const parsed = JSON.parse(raw);
    return { ...DEFAULTS, ...parsed };
  } catch {
    return { ...DEFAULTS };
  }
}

function write(patch) {
  const next = { ...read(), ...patch };
  // Never persist unknown keys — keeps the file readable and predictable.
  const clean = {};
  for (const key of Object.keys(DEFAULTS)) clean[key] = next[key];
  fs.mkdirSync(path.dirname(filePath()), { recursive: true });
  fs.writeFileSync(filePath(), JSON.stringify(clean, null, 2), 'utf8');
  return clean;
}

module.exports = { DEFAULTS, read, write, filePath };
