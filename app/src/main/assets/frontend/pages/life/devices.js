'use strict';

async function lifeDevices(kind) {
  if (!life.connected) return lifeLogin();
  const name = kind === 'drinking' ? '饮水' : '洗澡';
  sheet(`绑定${name}设备`, '<p class="fine">靠近对应设备，开启蓝牙并核对机器位置。绑定仅保存在本机当前账号下。</p><button class="primary wide" id="scan-ble">扫描附近设备</button><div id="device-results"></div>', () => {
    const button = $('scan-ble'), results = $('device-results');
    button.onclick = async () => { Motion.busy(button, true); results.textContent = '正在扫描附近设备…'; try {
      const result = await Native.call('lifeScan'); if (!results.isConnected) return;
      results.innerHTML = result.devices.length ? result.devices.map(d => `<button class="menu-item" data-mac="${esc(d.mac)}"><span>${esc(d.name)}<small>${esc(d.mac)}</small></span><span class="arrow" aria-hidden="true">›</span></button>`).join('') : '<p class="fine">没有发现设备，请靠近机器并确认蓝牙已开启。</p>';
      results.querySelectorAll('[data-mac]').forEach(deviceButton => { deviceButton.onclick = () => deviceDetail(deviceButton.dataset.mac, kind, true).catch(error => toast(error.message)); });
    } catch (error) { if (results.isConnected) results.textContent = error.message; else toast(error.message); } finally { Motion.busy(button, false); } };
  });
}

async function scanAndBind(kind) {
  if (!life.connected) return lifeLogin();
  const scanned = await Native.call('lifeScanCode'); if (scanned.cancelled) return;
  life = await Native.call('lifeBindDevice', { mac: scanned.mac, kind });
  waterKind = kind; route = 'water'; render();
  await connectDevice(scanned.mac, kind);
}

function boundDevice(mac) { return (life.devices || []).find(device => sameDevice(device.mac, mac)) || { mac, name: '用水设备' }; }

async function connectDevice(mac, kind) {
  const device = boundDevice(mac);
  sheet(`${waterName(kind)}设备`, `<h3>${esc(device.name)}</h3><p class="fine">${esc(mac)}</p><p role="status">正在连接蓝牙…</p>`);
  const serial = sheetSerial;
  try {
    const result = await Native.call('lifeConnect', { mac }); life = result.life || await Native.call('lifeStatus'); render();
    if ($('sheet').open && sheetSerial === serial) showMachine(mac, kind, result.message); else toast(result.message);
  } catch (error) {
    try { life = await Native.call('lifeStatus'); render(); } catch (_) {}
    if ($('sheet').open && sheetSerial === serial) showMachine(mac, kind, error.message); else toast(error.message);
  }
}

async function deviceDetail(mac, kind, binding = false) {
  if (!binding) {
    if (sameDevice(life.active?.mac, mac) || sameDevice(life.bluetooth?.mac, mac)) showMachine(mac, kind);
    else await connectDevice(mac, kind);
    return;
  }
  const serial = sheetSerial, device = await Native.call('lifeDevice', { mac });
  if (!$('sheet').open || sheetSerial !== serial) return;
  sheet(`绑定${waterName(kind)}设备`, `<h3>${esc(device.name)}</h3><p class="fine">${esc(mac)}</p><p>确认机器位置后绑定并尝试蓝牙连接。</p><button class="primary wide" id="bind-water">绑定并连接</button><p class="form-error" id="water-state" role="alert"></p>`, () => {
    const button = $('bind-water'), errorBox = $('water-state');
    button.onclick = async () => {
      Motion.busy(button, true);
      try { life = await Native.call('lifeBindDevice', { mac, kind }); render(); if (button.isConnected) await connectDevice(mac, kind); }
      catch (error) { if (errorBox.isConnected) errorBox.textContent = error.message; else toast(error.message); }
      finally { Motion.busy(button, false); }
    };
  });
}

function showMachine(mac, kind, message = '') {
  const device = boundDevice(mac), active = sameDevice(life.active?.mac, mac), connected = sameDevice(life.bluetooth?.mac, mac), name = waterName(kind);
  sheet(`${name}设备`, `<h3>${esc(device.name)}</h3><p class="fine">${esc(mac)}</p><p>${active ? life.active.confirmed ? '设备正在使用，结束后会提交趣智校园结算。' : '开阀结果尚待确认，请核对设备或结束并结算。' : connected ? '蓝牙已连接。点击开始后按趣智校园实际费率使用。' : '设备尚未连接。'}</p>
    ${active ? `<p>预扣金额：${life.active.preDeductMoney != null ? '¥ ' + esc(life.active.preDeductMoney) : '官方未提供'}</p><p class="fine">按官方设备配置显示，实际消费以结算账单为准。</p>` : ''}
    ${!active ? connected ? `<button class="primary wide" id="start-water">开始${name}</button>` : '<button class="primary wide" id="connect-water">连接设备</button>' : ''}
    ${active || connected ? `<button class="danger wide end-water" id="end-water">${active ? '断开连接并结束使用' : '断开连接'}</button>` : ''}
    ${!active ? '<button class="text-button wide" id="unbind-water">解除本机绑定</button>' : ''}<p class="form-error" id="water-state" role="status">${esc(message)}</p>`, () => {
    const stateBox = $('water-state');
    if ($('connect-water')) $('connect-water').onclick = () => connectDevice(mac, kind);
    if ($('end-water')) $('end-water').onclick = () => finishWater(mac, kind);
    if ($('start-water')) $('start-water').onclick = async () => {
      const serial = sheetSerial, buttons = [...$('sheet-body').querySelectorAll('button')]; buttons.forEach(button => button.disabled = true); Motion.busy($('start-water'), true); stateBox.textContent = '正在确认设备开启…';
      try { const result = await Native.call('lifeStart', { mac }); life = result.life || await Native.call('lifeStatus'); render(); if ($('sheet').open && sheetSerial === serial) showMachine(mac, kind, result.message); else toast(result.message); }
      catch (error) { try { life = await Native.call('lifeStatus'); render(); } catch (_) {} if ($('sheet').open && sheetSerial === serial) showMachine(mac, kind, error.message); else toast(error.message); }
      finally { buttons.forEach(button => Motion.busy(button, false)); }
    };
    if ($('unbind-water')) {
      const button = $('unbind-water'); button.onclick = async () => { Motion.busy(button, true); try { life = await Native.call('lifeUnbindDevice', { mac }); if (button.isConnected) closeSheet(); render(); } catch (error) { if (stateBox.isConnected) stateBox.textContent = error.message; else toast(error.message); } finally { Motion.busy(button, false); } };
    }
  });
}

async function finishWater(mac = null, kind = waterKind) {
  if (waterEnding) return;
  waterEnding = true;
  const serial = sheetSerial, stateBox = $('water-state'), buttons = $('sheet').open ? [...$('sheet-body').querySelectorAll('button')] : [];
  render();
  buttons.forEach(button => button.disabled = true);
  Motion.busy($('end-water'), true);
  if (stateBox) stateBox.textContent = life.active ? '正在关阀并提交结算…' : '正在断开连接…';
  try {
    const result = await Native.call('lifeEnd', mac ? { mac } : {}); life = result.life || await Native.call('lifeStatus'); render();
    if (life.receipt?.id) showWaterReceipt(life.receipt);
    else if (mac && $('sheet').open && sheetSerial === serial) showMachine(mac, kind, result.message); else toast(result.message);
    if (result.settled) {
      if (!lastSettlement) lastSettlement = { orderNo: result.orderNo, money: null, balancePending: false };
      const receipt = lastSettlement;
      try { const fresh = await Native.call('lifeRefresh'); if (lastSettlement === receipt) lifeSnapshot = fresh; }
      catch (_) { if (lastSettlement === receipt) { lifeSnapshot = null; receipt.balancePending = true; } }
      render();
    }
  } catch (error) {
    try { life = await Native.call('lifeStatus'); render(); } catch (_) {}
    if (mac && $('sheet').open && sheetSerial === serial) showMachine(mac, kind, error.message); else toast(error.message);
  } finally { waterEnding = false; buttons.forEach(button => Motion.busy(button, false)); render(); }
}

async function pollLifeState() {
  if (lifePolling) { scheduleContentRefresh(); return; }
  lifePolling = true;
  try {
    const wasUsing = !!life.active, fresh = await Native.call('lifeStatus');
    if (JSON.stringify(life) !== JSON.stringify(fresh)) { life = fresh; render(); }
    if (wasUsing && !life.active && !waterEnding && life.connected) { try { await lifeRefresh(true); } catch (_) { lifeSnapshot = null; render(); } }
    maybeShowReceipt();
  } catch (_) {}
  finally { lifePolling = false; scheduleContentRefresh(); }
}
