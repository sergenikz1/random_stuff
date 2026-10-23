'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('flow', {
  getSettings: () => ipcRenderer.invoke('settings:get'),
  setSettings: (patch) => ipcRenderer.invoke('settings:set', patch),
  resetSettings: () => ipcRenderer.invoke('settings:reset'),
  listHistory: () => ipcRenderer.invoke('history:list'),
  clearHistory: () => ipcRenderer.invoke('history:clear'),
  appInfo: () => ipcRenderer.invoke('app:info'),
  openDataFolder: () => ipcRenderer.invoke('app:openDataFolder'),
  testConnection: () => ipcRenderer.invoke('app:testConnection'),
  toggleDictation: () => ipcRenderer.invoke('dictate:toggle'),
  onHistoryChanged: (cb) => ipcRenderer.on('history:changed', () => cb())
});
