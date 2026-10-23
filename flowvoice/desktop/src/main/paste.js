'use strict';
const { clipboard } = require('electron');
const { spawn } = require('child_process');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function run(cmd, args) {
  return new Promise((resolve) => {
    try {
      const child = spawn(cmd, args, { windowsHide: true, stdio: 'ignore' });
      child.on('error', () => resolve(false));
      child.on('close', (code) => resolve(code === 0));
    } catch {
      resolve(false);
    }
  });
}

// Simulate the paste shortcut in whatever window currently has focus.
// No native modules: keeps the Windows build a plain, signable, dependency-free binary.
async function sendPasteShortcut() {
  if (process.platform === 'win32') {
    return run('powershell.exe', [
      '-NoProfile',
      '-NonInteractive',
      '-WindowStyle', 'Hidden',
      '-Command',
      '$s = New-Object -ComObject WScript.Shell; Start-Sleep -Milliseconds 30; $s.SendKeys("^v")'
    ]);
  }
  if (process.platform === 'darwin') {
    return run('osascript', ['-e', 'tell application "System Events" to keystroke "v" using command down']);
  }
  return run('xdotool', ['key', '--clearmodifiers', 'ctrl+v']);
}

/**
 * Put `text` into the focused application.
 * @returns {Promise<'pasted'|'clipboard'>}
 */
async function insertText(text, settings) {
  const payload = settings.trailingSpace ? `${text} ` : text;
  const previous = settings.restoreClipboard ? clipboard.readText() : null;

  clipboard.writeText(payload);

  if (settings.pasteMode === 'clipboard') return 'clipboard';

  await sleep(60);
  const ok = await sendPasteShortcut();

  if (previous !== null) {
    // Give the target app time to read the clipboard before we put the old value back.
    await sleep(700);
    if (clipboard.readText() === payload) clipboard.writeText(previous);
  }
  return ok ? 'pasted' : 'clipboard';
}

module.exports = { insertText };
