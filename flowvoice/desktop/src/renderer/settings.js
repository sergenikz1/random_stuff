'use strict';

let S = null;
let lang = 'ru';
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));
const t = (key) => (window.I18N[lang] && window.I18N[lang][key]) || key;

/* --------------------------------------------------------------------- i18n */

function applyLanguage() {
  document.documentElement.lang = lang;
  $$('[data-i18n]').forEach((el) => {
    const value = t(el.dataset.i18n);
    if (value) el.textContent = value;
  });
  $$('[data-uilang]').forEach((b) => b.classList.toggle('active', b.dataset.uilang === lang));
  $('#hotkeyHint').textContent = t('hotkey.hint');
  renderRangeLabels();
  renderDevices();
  renderHistory();
}

/* ------------------------------------------------------------------- toasts */

let toastTimer = 0;
function toast(message) {
  const el = $('#toast');
  el.textContent = message;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 1600);
}

let saveTimer = 0;
async function save(patch, immediate = false) {
  Object.assign(S, patch);
  clearTimeout(saveTimer);
  const commit = async () => {
    S = await window.flow.setSettings(patch);
    toast(t('toast.saved'));
  };
  if (immediate) await commit();
  else saveTimer = setTimeout(commit, 400);
}

/* --------------------------------------------------------------------- tabs */

$$('.tab').forEach((tab) => {
  tab.addEventListener('click', () => {
    $$('.tab').forEach((x) => x.classList.remove('active'));
    $$('section').forEach((x) => x.classList.remove('active'));
    tab.classList.add('active');
    $(`section[data-panel="${tab.dataset.tab}"]`).classList.add('active');
    if (tab.dataset.tab === 'history') renderHistory();
  });
});

$$('[data-uilang]').forEach((b) => {
  b.addEventListener('click', () => {
    lang = b.dataset.uilang;
    save({ uiLanguage: lang }, true);
    applyLanguage();
  });
});

/* ------------------------------------------------------------------ helpers */

function bindText(id, key) {
  const el = $(`#${id}`);
  el.addEventListener('input', () => save({ [key]: el.value.trim() }));
}

function bindCheck(id, key) {
  const el = $(`#${id}`);
  el.addEventListener('change', () => save({ [key]: el.checked }, true));
}

function bindSegmented(id, key, after) {
  $(`#${id}`).addEventListener('click', (e) => {
    const btn = e.target.closest('button[data-value]');
    if (!btn) return;
    save({ [key]: btn.dataset.value }, true);
    syncSegmented(id, key);
    if (after) after();
  });
}

function syncSegmented(id, key) {
  $$(`#${id} button[data-value]`).forEach((b) => b.classList.toggle('active', b.dataset.value === S[key]));
}

function renderRangeLabels() {
  if (!S) return;
  $('#silenceVal').textContent = `${(S.autoStopSilenceMs / 1000).toFixed(1)} ${t('sec.seconds')}`;
  $('#maxLenVal').textContent = `${S.maxRecordSeconds} ${t('sec.seconds')}`;
}

/* ------------------------------------------------------------------ presets */

const PRESETS = {
  openai: { apiBaseUrl: 'https://api.openai.com/v1', sttModel: 'whisper-1', cleanupModel: 'gpt-4o-mini' },
  groq: { apiBaseUrl: 'https://api.groq.com/openai/v1', sttModel: 'whisper-large-v3', cleanupModel: 'llama-3.3-70b-versatile' },
  local: { apiBaseUrl: 'http://127.0.0.1:8080/v1', sttModel: 'whisper-1', cleanupModel: 'gpt-4o-mini' }
};

$$('[data-preset]').forEach((b) => {
  b.addEventListener('click', async () => {
    const preset = PRESETS[b.dataset.preset];
    $('#apiBaseUrl').value = preset.apiBaseUrl;
    $('#sttModel').value = preset.sttModel;
    $('#cleanupModel').value = preset.cleanupModel;
    await save(preset, true);
  });
});

/* ------------------------------------------------------------------- hotkey */

const MOD_ORDER = ['Control', 'Alt', 'Shift', 'Super'];
let capturing = false;

function prettyHotkey(hk) {
  return String(hk || '')
    .replace(/CommandOrControl/g, 'Ctrl')
    .replace(/Control/g, 'Ctrl')
    .replace(/Super/g, 'Win')
    .split('+').join(' + ');
}

$('#hotkeyBtn').addEventListener('click', () => {
  capturing = !capturing;
  $('#hotkeyBtn').classList.toggle('capturing', capturing);
  $('#hotkeyBtn').textContent = capturing ? t('hotkey.press') : prettyHotkey(S.hotkey);
});

$('#hotkeyReset').addEventListener('click', async () => {
  capturing = false;
  $('#hotkeyBtn').classList.remove('capturing');
  await save({ hotkey: 'Control+Shift+Space' }, true);
  $('#hotkeyBtn').textContent = prettyHotkey(S.hotkey);
});

document.addEventListener('keydown', async (e) => {
  if (!capturing) return;
  e.preventDefault();
  if (['Control', 'Alt', 'Shift', 'Meta'].includes(e.key)) return;

  const mods = [];
  if (e.ctrlKey) mods.push('Control');
  if (e.altKey) mods.push('Alt');
  if (e.shiftKey) mods.push('Shift');
  if (e.metaKey) mods.push('Super');
  if (!mods.length) return;

  let key = e.key;
  if (key === ' ') key = 'Space';
  else if (key.length === 1) key = key.toUpperCase();
  else if (/^F\d{1,2}$/.test(key)) { /* keep as-is */ }
  else if (key === 'Escape') { capturing = false; $('#hotkeyBtn').classList.remove('capturing'); $('#hotkeyBtn').textContent = prettyHotkey(S.hotkey); return; }

  const combo = MOD_ORDER.filter((m) => mods.includes(m)).concat(key).join('+');
  capturing = false;
  $('#hotkeyBtn').classList.remove('capturing');
  await save({ hotkey: combo }, true);
  $('#hotkeyBtn').textContent = prettyHotkey(S.hotkey);
});

/* ------------------------------------------------------------------ devices */

let devices = [];
async function loadDevices() {
  try {
    // Permission is needed before labels are readable.
    const probe = await navigator.mediaDevices.getUserMedia({ audio: true });
    probe.getTracks().forEach((tr) => tr.stop());
  } catch { /* labels stay generic — the default device still works */ }
  try {
    devices = (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === 'audioinput');
  } catch {
    devices = [];
  }
  renderDevices();
}

function renderDevices() {
  const sel = $('#inputDeviceId');
  if (!sel || !S) return;
  sel.innerHTML = '';
  const def = document.createElement('option');
  def.value = 'default';
  def.textContent = t('device.default');
  sel.appendChild(def);
  devices.forEach((d, i) => {
    const opt = document.createElement('option');
    opt.value = d.deviceId;
    opt.textContent = d.label || `Microphone ${i + 1}`;
    sel.appendChild(opt);
  });
  sel.value = devices.some((d) => d.deviceId === S.inputDeviceId) ? S.inputDeviceId : 'default';
}

/* ------------------------------------------------------------------ history */

function formatTime(ts) {
  const d = new Date(ts);
  return d.toLocaleString(lang === 'ru' ? 'ru-RU' : 'en-US', {
    day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit'
  });
}

async function renderHistory() {
  const box = $('#historyList');
  if (!box) return;
  const items = await window.flow.listHistory();
  box.innerHTML = '';
  if (!items.length) {
    const p = document.createElement('p');
    p.className = 'empty';
    p.textContent = t('history.empty');
    box.appendChild(p);
    return;
  }
  items.forEach((item) => {
    const el = document.createElement('div');
    el.className = 'item';

    const meta = document.createElement('div');
    meta.className = 'meta';
    const time = document.createElement('span');
    time.textContent = formatTime(item.ts);
    meta.appendChild(time);
    if (item.language) {
      const tag = document.createElement('span');
      tag.className = 'tag';
      tag.textContent = String(item.language).toUpperCase();
      meta.appendChild(tag);
    }
    if (item.latencyMs) {
      const tag = document.createElement('span');
      tag.className = 'tag';
      tag.textContent = `${(item.latencyMs / 1000).toFixed(1)} ${t('sec.seconds')}`;
      meta.appendChild(tag);
    }

    const p = document.createElement('p');
    p.textContent = item.text;

    const acts = document.createElement('div');
    acts.className = 'acts';
    const copy = document.createElement('button');
    copy.textContent = t('f.copy');
    copy.addEventListener('click', async () => {
      await navigator.clipboard.writeText(item.text);
      toast(t('toast.copied'));
    });
    acts.appendChild(copy);

    el.append(meta, p, acts);
    box.appendChild(el);
  });
}

/* --------------------------------------------------------------------- boot */

async function init() {
  S = await window.flow.getSettings();
  lang = S.uiLanguage === 'en' ? 'en' : 'ru';

  $('#apiBaseUrl').value = S.apiBaseUrl;
  $('#apiKey').value = S.apiKey;
  $('#sttModel').value = S.sttModel;
  $('#cleanupModel').value = S.cleanupModel;
  $('#cleanup').checked = S.cleanup;
  $('#autoStopEnabled').checked = S.autoStopEnabled;
  $('#restoreClipboard').checked = S.restoreClipboard;
  $('#trailingSpace').checked = S.trailingSpace;
  $('#launchAtLogin').checked = S.launchAtLogin;
  $('#playSounds').checked = S.playSounds;
  $('#autoStopSilenceMs').value = S.autoStopSilenceMs;
  $('#maxRecordSeconds').value = S.maxRecordSeconds;
  $('#dictionary').value = (S.dictionary || []).join('\n');
  $('#hotkeyBtn').textContent = prettyHotkey(S.hotkey);

  bindText('apiBaseUrl', 'apiBaseUrl');
  bindText('apiKey', 'apiKey');
  bindText('sttModel', 'sttModel');
  bindText('cleanupModel', 'cleanupModel');
  bindCheck('cleanup', 'cleanup');
  bindCheck('autoStopEnabled', 'autoStopEnabled');
  bindCheck('restoreClipboard', 'restoreClipboard');
  bindCheck('trailingSpace', 'trailingSpace');
  bindCheck('launchAtLogin', 'launchAtLogin');
  bindCheck('playSounds', 'playSounds');
  bindSegmented('language', 'language');
  bindSegmented('pasteMode', 'pasteMode');
  syncSegmented('language', 'language');
  syncSegmented('pasteMode', 'pasteMode');

  $('#autoStopSilenceMs').addEventListener('input', (e) => {
    S.autoStopSilenceMs = Number(e.target.value);
    renderRangeLabels();
    save({ autoStopSilenceMs: Number(e.target.value) });
  });
  $('#maxRecordSeconds').addEventListener('input', (e) => {
    S.maxRecordSeconds = Number(e.target.value);
    renderRangeLabels();
    save({ maxRecordSeconds: Number(e.target.value) });
  });
  $('#dictionary').addEventListener('input', (e) => {
    const list = e.target.value.split('\n').map((x) => x.trim()).filter(Boolean);
    save({ dictionary: list });
  });
  $('#inputDeviceId').addEventListener('change', (e) => save({ inputDeviceId: e.target.value }, true));

  $('#toggleKey').addEventListener('click', () => {
    const el = $('#apiKey');
    const shown = el.type === 'text';
    el.type = shown ? 'password' : 'text';
    $('#toggleKey').textContent = shown ? t('f.show') : t('f.hide');
  });

  $('#testConn').addEventListener('click', async () => {
    const out = $('#testResult');
    out.className = 'status';
    out.textContent = '…';
    const res = await window.flow.testConnection();
    if (res.ok) {
      out.className = 'status ok';
      out.textContent = t('test.ok');
    } else {
      out.className = 'status bad';
      out.textContent = res.error === 'NO_API_KEY' ? t('test.noKey') : res.error;
    }
  });

  $('#dictate').addEventListener('click', () => window.flow.toggleDictation());
  $('#openData').addEventListener('click', () => window.flow.openDataFolder());
  $('#clearHistory').addEventListener('click', async () => {
    await window.flow.clearHistory();
    renderHistory();
  });
  $('#resetAll').addEventListener('click', async () => {
    await window.flow.resetSettings();
    // Reload rather than re-running init(), which would double up every listener.
    location.reload();
  });

  window.flow.onHistoryChanged(renderHistory);

  const info = await window.flow.appInfo();
  $('#aboutInfo').textContent = `FlowVoice ${info.version} · ${info.platform} · ${info.userData}`;

  applyLanguage();
  loadDevices();
}

init();
