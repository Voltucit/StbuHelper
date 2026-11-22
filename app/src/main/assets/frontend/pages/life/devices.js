'use strict';

let waterStarting = false;
let machineSheet = null;

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
  const description = life.ratePending ? '上一笔计费请求尚未确认，请先在趣智校园官方 App 核对。'
    : active ? life.active.unsent ? '开启指令尚未发送，可以先处理这笔计费订单。'
      : life.active.stopped ? '设备已停止用水，正在核对结算。'
      : !connected ? '蓝牙连接已断开，用水状态待核对。'
      : life.active.confirmed ? '设备正在使用，结束后会提交趣智校园结算。' : '水表开启结果尚待确认，请核对设备。'
    : connected ? '蓝牙已连接。点击开始后按趣智校园实际费率使用。' : '设备尚未连接。';
  const endLabel = active ? life.active.unsent ? '处理计费订单' : life.active.stopped ? '核对结算' : connected ? '断开连接并结束使用' : '结束并核对结算' : '断开连接';
  sheet(`${name}设备`, `<h3>${esc(device.name)}</h3><p class="fine">${esc(mac)}</p><p>${description}</p>
    ${active ? `<p>预扣金额：${life.active.preDeductMoney != null ? '¥ ' + esc(life.active.preDeductMoney) : '官方未提供'}</p><p class="fine">按官方设备配置显示，实际消费以结算账单为准。</p>` : ''}
    ${life.ratePending ? '<button class="secondary wide" data-action="life-review-rate">我已在官方核对订单</button>' : ''}
    ${!active && !life.ratePending ? connected ? `<button class="primary wide" id="start-water">开始${name}</button>` : '<button class="primary wide" id="connect-water">连接设备</button>' : ''}
    ${active || connected ? `<button class="danger wide end-water" id="end-water">${endLabel}</button>` : ''}
    ${!active ? '<button class="text-button wide" id="unbind-water">解除本机绑定</button>' : ''}<p class="form-error" id="water-state" role="status">${esc(message)}</p>`, () => {
    const stateBox = $('water-state');
    if ($('connect-water')) $('connect-water').onclick = () => connectDevice(mac, kind);
    if ($('end-water')) $('end-water').onclick = () => finishWater(mac, kind);
    if ($('start-water')) $('start-water').onclick = async () => {
      if (waterStarting || life.active || life.ratePending) return;
      waterStarting = true;
      const serial = sheetSerial, buttons = [...$('sheet-body').querySelectorAll('button')]; buttons.forEach(button => button.disabled = true); Motion.busy($('start-water'), true); stateBox.textContent = '正在确认设备开启…';
      try { const result = await Native.call('lifeStart', { mac }); life = result.life || await Native.call('lifeStatus'); render(); if ($('sheet').open && sheetSerial === serial) showMachine(mac, kind, result.message); else toast(result.message); }
      catch (error) { try { life = await Native.call('lifeStatus'); render(); } catch (_) {} if ($('sheet').open && sheetSerial === serial) showMachine(mac, kind, error.message); else toast(error.message); }
      finally { waterStarting = false; buttons.forEach(button => Motion.busy(button, false)); }
    };
    if ($('unbind-water')) {
      const button = $('unbind-water'); button.onclick = async () => { Motion.busy(button, true); try { life = await Native.call('lifeUnbindDevice', { mac }); if (button.isConnected) closeSheet(); render(); } catch (error) { if (stateBox.isConnected) stateBox.textContent = error.message; else toast(error.message); } finally { Motion.busy(button, false); } };
    }
  });
  machineSheet = { mac, kind, serial: sheetSerial, active: life.active, bluetooth: life.bluetooth, receiptId: life.receipt?.id };
}

function reviewRateOrder() {
  confirmAction('确认官方订单已处理？', '请先在趣智校园官方 App 核对上一笔计费。确认没有未处理的订单后，再解除本机的开启限制。如果仍有预扣或扣款争议，请先处理该订单。', async () => {
    life = await Native.call('lifeConfirmRateReview');
  });
}

async function finishWater(mac = null, kind = waterKind) {
  if (waterEnding) return;
  waterEnding = true;
  const serial = sheetSerial, stateBox = $('water-state'), buttons = $('sheet').open ? [...$('sheet-body').querySelectorAll('button')] : [];
  render();
  buttons.forEach(button => button.disabled = true);
  Motion.busy($('end-water'), true);
  if (stateBox) stateBox.textContent = life.active?.unsent ? '正在核对计费订单…' : life.active?.stopped ? '正在核对结算…' : life.active ? '正在关阀并提交结算…' : '正在断开连接…';
  try {
    const result = await Native.call('lifeEnd', mac ? { mac } : {}); life = result.life || await Native.call('lifeStatus'); render();
    if (life.receipt?.id) showWaterReceipt(life.receipt);
    else if (mac && $('sheet').open && sheetSerial === serial) showMachine(mac, kind, result.message); else toast(result.message);
    if (result.settled) {
      if (!lastSettlement) lastSettlement = {};
      const receipt = lastSettlement;
      receipt.deviceCleared = true;
      receipt.balancePending = false;
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
    const fresh = await Native.call('lifeStatus'), previous = life;
    if (JSON.stringify(previous) !== JSON.stringify(fresh)) { life = fresh; render(); }
    const machine = machineSheet;
    if (machine && $('sheet').open && sheetSerial === machine.serial && !waterEnding && !waterStarting) {
      const newReceipt = life.receipt?.id && life.receipt.id !== machine.receiptId && sameDevice(machine.active?.mac, machine.mac);
      if (newReceipt) showWaterReceipt(life.receipt);
      else if (JSON.stringify(machine.active) !== JSON.stringify(life.active) || JSON.stringify(machine.bluetooth) !== JSON.stringify(life.bluetooth)) {
        showMachine(machine.mac, machine.kind);
      }
    }
    if (previous.active && !life.active && !waterEnding && life.connected) { try { await lifeRefresh(true); } catch (_) { lifeSnapshot = null; render(); } }
    maybeShowReceipt();
  } catch (_) {}
  finally { lifePolling = false; scheduleContentRefresh(); }
}
