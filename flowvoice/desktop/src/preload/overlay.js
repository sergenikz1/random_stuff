'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('flow', {
  onStart: (cb) => ipcRenderer.on('recorder:start', (_e, opts) => cb(opts)),
  onStop: (cb) => ipcRenderer.on('recorder:stop', () => cb()),
  onCancel: (cb) => ipcRenderer.on('recorder:cancel', () => cb()),
  onState: (cb) => ipcRenderer.on('overlay:state', (_e, payload) => cb(payload)),
  onSettings: (cb) => ipcRenderer.on('overlay:settings', (_e, s) => cb(s)),

  sendAudio: (data, mime, durationMs) => ipcRenderer.send('recorder:audio', { data, mime, durationMs }),
  aborted: (reason) => ipcRenderer.send('recorder:aborted', reason),
  micError: (message) => ipcRenderer.send('recorder:error', message),
  autoStop: () => ipcRenderer.send('recorder:autostop'),
  cancel: () => ipcRenderer.send('overlay:cancel')
});
