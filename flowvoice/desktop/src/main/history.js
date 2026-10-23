'use strict';
const fs = require('fs');
const path = require('path');
const { app } = require('electron');

let cachedPath = null;
function filePath() {
  if (!cachedPath) cachedPath = path.join(app.getPath('userData'), 'history.json');
  return cachedPath;
}

function list() {
  try {
    const data = JSON.parse(fs.readFileSync(filePath(), 'utf8'));
    return Array.isArray(data) ? data : [];
  } catch {
    return [];
  }
}

function add(entry, limit = 200) {
  const items = list();
  items.unshift({ id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`, ...entry });
  const trimmed = items.slice(0, Math.max(1, limit));
  try {
    fs.mkdirSync(path.dirname(filePath()), { recursive: true });
    fs.writeFileSync(filePath(), JSON.stringify(trimmed, null, 2), 'utf8');
  } catch { /* history is a convenience, never fatal */ }
  return trimmed;
}

function clear() {
  try { fs.writeFileSync(filePath(), '[]', 'utf8'); } catch { /* ignore */ }
  return [];
}

module.exports = { list, add, clear, filePath };
