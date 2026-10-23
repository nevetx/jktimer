'use strict';

const els = {
  spinner: document.getElementById('spinner'),
  message: document.getElementById('message'),
  deviceSection: document.getElementById('deviceSection'),
  deviceList: document.getElementById('deviceList'),
  manualForm: document.getElementById('manualForm'),
  manualInput: document.getElementById('manualInput'),
  manualError: document.getElementById('manualError'),
  rescanBtn: document.getElementById('rescanBtn'),
};

function render(state) {
  if (!state) return;
  els.message.textContent = state.message || '';
  els.spinner.hidden = !(state.mode === 'searching' || state.scanning);

  els.deviceSection.hidden = state.devices.length === 0;
  els.deviceList.replaceChildren(...state.devices.map((d) => deviceItem(d, d.deviceId === state.savedDeviceId)));
}

function deviceItem(device, saved) {
  const li = document.createElement('li');
  li.className = 'device' + (saved ? ' saved' : '');

  const info = document.createElement('div');
  info.className = 'device-info';
  const name = document.createElement('div');
  name.className = 'device-name';
  name.textContent = device.deviceName;
  if (saved) {
    const badge = document.createElement('span');
    badge.className = 'badge';
    badge.textContent = '上次使用';
    name.appendChild(badge);
  }
  const meta = document.createElement('div');
  meta.className = 'device-meta';
  meta.textContent = `${device.host}:${device.port}` + (device.version ? `　版本 v${device.version}` : '');
  info.append(name, meta);

  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'btn btn-primary';
  btn.textContent = '連線';
  btn.addEventListener('click', () => window.viewer.connect(device.deviceId));

  li.append(info, btn);
  return li;
}

els.manualForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const submit = els.manualForm.querySelector('button');
  els.manualError.hidden = true;
  submit.disabled = true;
  try {
    const result = await window.viewer.connectManual(els.manualInput.value);
    if (result && !result.ok) {
      els.manualError.textContent = result.error || '連線失敗';
      els.manualError.hidden = false;
    }
  } finally {
    submit.disabled = false;
  }
});

els.rescanBtn.addEventListener('click', () => window.viewer.rescan());

window.viewer.onState(render);
window.viewer.getState().then(render);
