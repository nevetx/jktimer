'use strict';

const { contextBridge, ipcRenderer } = require('electron');

// 只提供給本機的啟動頁（搜尋 / 選擇平板）使用；平板的儀表板網頁不會取得任何額外權限
if (location.protocol === 'file:') {
  contextBridge.exposeInMainWorld('viewer', {
    getState: () => ipcRenderer.invoke('viewer:getState'),
    connect: (deviceId) => ipcRenderer.invoke('viewer:connect', deviceId),
    connectManual: (text) => ipcRenderer.invoke('viewer:connectManual', text),
    rescan: () => ipcRenderer.invoke('viewer:rescan'),
    onState: (callback) => {
      ipcRenderer.removeAllListeners('viewer:state');
      ipcRenderer.on('viewer:state', (_e, state) => callback(state));
    },
  });
}
