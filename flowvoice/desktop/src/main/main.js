'use strict';
const path = require('path');
const {
  app, BrowserWindow, globalShortcut, ipcMain, Tray, Menu,
  screen, shell, nativeImage, dialog
} = require('electron');

const settingsStore = require('./settings');
const history = require('./history');
const { transcribe, cleanup } = require('./transcribe');
const { insertText } = require('./paste');

const ASSETS = path.join(__dirname, '..', '..', 'assets');

let overlayWin = null;
let settingsWin = null;
let tray = null;
let settings = null;
let recording = false;
let busy = false;

// Only one instance may own the global hotkey.
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => openSettings());
}

app.commandLine.appendSwitch('disable-features', 'MediaSessionService');

/* ------------------------------------------------------------------ windows */

function createOverlay() {
  const display = screen.getPrimaryDisplay();
  const { width, height } = display.workAreaSize;
  const W = 340;
  const H = 132;

  overlayWin = new BrowserWindow({
    width: W,
    height: H,
    x: Math.round(display.workArea.x + (width - W) / 2),
    y: Math.round(display.workArea.y + height - H - 48),
    frame: false,
    transparent: true,
    resizable: false,
    movable: false,
    minimizable: false,
    maximizable: false,
    fullscreenable: false,
    skipTaskbar: true,
    // Never steal focus: the user must keep typing into their own app.
    focusable: false,
    alwaysOnTop: true,
    show: false,
    hasShadow: false,
    backgroundColor: '#00000000',
    webPreferences: {
      preload: path.join(__dirname, '..', 'preload', 'overlay.js'),
      contextIsolation: true,
      nodeIntegration: false,
      backgroundThrottling: false
    }
  });

  overlayWin.setAlwaysOnTop(true, 'screen-saver');
  overlayWin.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
  overlayWin.setIgnoreMouseEvents(true, { forward: true });
  overlayWin.loadFile(path.join(__dirname, '..', 'renderer', 'overlay.html'));
  overlayWin.on('closed', () => { overlayWin = null; });
}

function openSettings() {
  if (settingsWin && !settingsWin.isDestroyed()) {
    settingsWin.show();
    settingsWin.focus();
    return;
  }
  settingsWin = new BrowserWindow({
    width: 940,
    height: 720,
    minWidth: 760,
    minHeight: 560,
    title: 'FlowVoice',
    backgroundColor: '#0b0b12',
    icon: path.join(ASSETS, 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, '..', 'preload', 'settings.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  settingsWin.loadFile(path.join(__dirname, '..', 'renderer', 'settings.html'));
  settingsWin.on('closed', () => { settingsWin = null; });
}

/* -------------------------------------------------------------------- state */

function overlaySend(channel, payload) {
  if (overlayWin && !overlayWin.isDestroyed()) overlayWin.webContents.send(channel, payload);
}

function settingsSend(channel, payload) {
  if (settingsWin && !settingsWin.isDestroyed()) settingsWin.webContents.send(channel, payload);
}

function showOverlay() {
  if (!overlayWin || overlayWin.isDestroyed()) return;
  overlayWin.showInactive();
  overlayWin.setAlwaysOnTop(true, 'screen-saver');
}

function hideOverlay(delay = 0) {
  setTimeout(() => {
    if (overlayWin && !overlayWin.isDestroyed() && !recording && !busy) overlayWin.hide();
  }, delay);
}

function setTrayState(state) {
  if (!tray) return;
  const label = { idle: 'FlowVoice', recording: 'FlowVoice — запись…', working: 'FlowVoice — расшифровка…' };
  tray.setToolTip(label[state] || 'FlowVoice');
}

/* ---------------------------------------------------------------- recording */

function startRecording() {
  if (recording || busy) return;
  recording = true;
  setTrayState('recording');
  showOverlay();
  overlaySend('overlay:state', { state: 'recording', settings });
  overlaySend('recorder:start', {
    deviceId: settings.inputDeviceId,
    autoStopEnabled: settings.autoStopEnabled,
    autoStopSilenceMs: settings.autoStopSilenceMs,
    maxRecordSeconds: settings.maxRecordSeconds,
    playSounds: settings.playSounds
  });
}

function stopRecording() {
  if (!recording) return;
  recording = false;
  overlaySend('recorder:stop');
}

function cancelRecording() {
  if (!recording && !busy) return;
  recording = false;
  busy = false;
  overlaySend('recorder:cancel');
  overlaySend('overlay:state', { state: 'idle', settings });
  setTrayState('idle');
  hideOverlay(150);
}

function toggleRecording() {
  if (busy) return;
  if (recording) stopRecording();
  else startRecording();
}

function registerHotkeys() {
  globalShortcut.unregisterAll();
  let ok = false;
  try {
    ok = globalShortcut.register(settings.hotkey, toggleRecording);
  } catch {
    ok = false;
  }
  if (!ok) {
    // Fall back so the app is never unusable because of a taken shortcut.
    try {
      ok = globalShortcut.register(settingsStore.DEFAULTS.hotkey, toggleRecording);
      if (ok) settings = settingsStore.write({ hotkey: settingsStore.DEFAULTS.hotkey });
    } catch { /* ignore */ }
  }
  try { globalShortcut.register('CommandOrControl+Shift+Escape', cancelRecording); } catch { /* optional */ }
  return ok;
}

/* --------------------------------------------------------------------- tray */

function buildTray() {
  const image = nativeImage.createFromPath(path.join(ASSETS, 'tray.png'));
  tray = new Tray(image.isEmpty() ? nativeImage.createFromPath(path.join(ASSETS, 'icon.png')) : image);
  tray.setToolTip('FlowVoice');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Диктовать / Dictate', click: toggleRecording },
    { type: 'separator' },
    { label: 'Настройки / Settings', click: openSettings },
    { label: 'Папка данных / Data folder', click: () => shell.openPath(app.getPath('userData')) },
    { type: 'separator' },
    { label: 'Выход / Quit', click: () => { app.isQuitting = true; app.quit(); } }
  ]));
  tray.on('click', openSettings);
}

/* ----------------------------------------------------------------- pipeline */

async function handleAudio(buffer, mime, durationMs) {
  busy = true;
  setTrayState('working');
  overlaySend('overlay:state', { state: 'working', settings });

  const started = Date.now();
  try {
    const result = await transcribe(buffer, mime, settings);
    if (!result.text) {
      overlaySend('overlay:state', { state: 'empty', settings });
      busy = false;
      setTrayState('idle');
      hideOverlay(1200);
      return;
    }

    const finalText = await cleanup(result.text, settings, result.language);
    const mode = await insertText(finalText, settings);

    history.add({
      ts: Date.now(),
      text: finalText,
      raw: result.text,
      language: result.language || settings.language,
      durationMs,
      latencyMs: Date.now() - started,
      inserted: mode
    }, settings.historyLimit);
    settingsSend('history:changed');

    overlaySend('overlay:state', {
      state: 'done',
      settings,
      text: finalText.slice(0, 160),
      mode
    });
    hideOverlay(1100);
  } catch (err) {
    const code = err.code === 'NO_API_KEY' ? 'NO_API_KEY' : 'ERROR';
    overlaySend('overlay:state', { state: 'error', settings, code, message: String(err.message || err) });
    if (code === 'NO_API_KEY') openSettings();
    hideOverlay(2600);
  } finally {
    busy = false;
    setTrayState('idle');
  }
}

/* ---------------------------------------------------------------------- ipc */

ipcMain.handle('settings:get', () => settings);

ipcMain.handle('settings:set', (_e, patch) => {
  const hotkeyChanged = patch && patch.hotkey && patch.hotkey !== settings.hotkey;
  settings = settingsStore.write(patch || {});
  if (hotkeyChanged) registerHotkeys();
  if (process.platform === 'win32' || process.platform === 'darwin') {
    app.setLoginItemSettings({ openAtLogin: !!settings.launchAtLogin, args: ['--hidden'] });
  }
  overlaySend('overlay:settings', settings);
  return settings;
});

ipcMain.handle('settings:reset', () => {
  settings = settingsStore.write({ ...settingsStore.DEFAULTS, apiKey: settings.apiKey });
  registerHotkeys();
  return settings;
});

ipcMain.handle('history:list', () => history.list());
ipcMain.handle('history:clear', () => history.clear());

ipcMain.handle('app:info', () => ({
  version: app.getVersion(),
  platform: process.platform,
  userData: app.getPath('userData'),
  settingsFile: settingsStore.filePath()
}));

ipcMain.handle('app:openDataFolder', () => shell.openPath(app.getPath('userData')));

ipcMain.handle('app:testConnection', async () => {
  if (!settings.apiKey) return { ok: false, error: 'NO_API_KEY' };
  try {
    const res = await fetch(String(settings.apiBaseUrl).replace(/\/+$/, '') + '/models', {
      headers: { Authorization: `Bearer ${settings.apiKey}` }
    });
    if (!res.ok) return { ok: false, error: `HTTP ${res.status}` };
    return { ok: true };
  } catch (err) {
    return { ok: false, error: String(err.message || err) };
  }
});

ipcMain.handle('dictate:toggle', () => { toggleRecording(); return recording; });

ipcMain.on('recorder:audio', async (_e, { data, mime, durationMs }) => {
  await handleAudio(Buffer.from(data), mime || 'audio/webm', durationMs || 0);
});

ipcMain.on('recorder:aborted', (_e, reason) => {
  recording = false;
  overlaySend('overlay:state', { state: reason === 'silence' ? 'empty' : 'idle', settings });
  setTrayState('idle');
  hideOverlay(900);
});

ipcMain.on('recorder:error', (_e, message) => {
  recording = false;
  busy = false;
  overlaySend('overlay:state', { state: 'error', settings, code: 'MIC', message });
  setTrayState('idle');
  hideOverlay(2600);
});

ipcMain.on('recorder:autostop', () => { recording = false; });

ipcMain.on('overlay:cancel', cancelRecording);

/* --------------------------------------------------------------- lifecycle */

app.whenReady().then(() => {
  settings = settingsStore.read();

  // The overlay renderer owns the microphone; grant it silently.
  const { session } = require('electron');
  session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) => {
    callback(permission === 'media' || permission === 'audioCapture');
  });

  createOverlay();
  buildTray();
  const ok = registerHotkeys();

  if (process.platform === 'win32' || process.platform === 'darwin') {
    app.setLoginItemSettings({ openAtLogin: !!settings.launchAtLogin, args: ['--hidden'] });
  }

  if (!process.argv.includes('--hidden')) openSettings();

  if (!ok) {
    dialog.showMessageBox({
      type: 'warning',
      title: 'FlowVoice',
      message: 'Не удалось зарегистрировать горячую клавишу / Could not register the hotkey',
      detail: `${settings.hotkey}\n\nВыберите другую комбинацию в настройках.\nPick another shortcut in Settings.`
    });
  }
});

// Tray app: closing the settings window must not quit dictation.
// Registering this listener without calling app.quit() keeps the process alive.
app.on('window-all-closed', () => {});

app.on('will-quit', () => globalShortcut.unregisterAll());
