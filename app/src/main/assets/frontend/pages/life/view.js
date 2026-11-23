'use strict';

function sameDevice(a, b) { return !!a && !!b && String(a).replace(/[:-]/g, '').toUpperCase() === String(b).replace(/[:-]/g, '').toUpperCase(); }

function waterName(kind) { return kind === 'drinking' ? '饮水' : '洗澡'; }

function waterStatus() {
  if (life.ratePending) return '<div class="alert"><p>上一笔计费请求尚未确认，请先在趣智校园官方 App 核对。</p><button class="secondary wide" data-action="life-review-rate">我已在官方核对订单</button></div>';
  if (life.active) {
    const using = life.active.confirmed && sameDevice(life.active.mac, life.bluetooth?.mac);
    const title = life.active.unsent ? '开启指令尚未发送，有计费订单待处理' : life.active.stopped ? '设备已停止用水，结算待核对' : using ? '设备正在使用' : '有待确认的用水记录';
    const action = life.active.unsent ? '处理计费订单' : life.active.stopped ? '核对结算' : using ? '断开并结束使用' : '结束并核对结算';
    return `<div class="alert"><p>${title}</p><p class="pre-deduct">${life.active.preDeductMoney != null ? '预扣金额：¥ ' + esc(life.active.preDeductMoney) : '预扣金额未提供'}</p><button class="danger wide" data-action="life-end-active" ${waterEnding ? 'disabled' : ''}>${action}</button></div>`;
  }
  if (life.bluetooth?.mac) return `<div class="connection-note"><p>设备蓝牙已连接</p><button class="danger wide" data-action="life-end-active" ${waterEnding ? 'disabled' : ''}>断开连接</button></div>`;
  return '';
}

function settlementView() {
  if (!lastSettlement) return '';
  const message = lastSettlement.settlementConfirmed === false ? '饮水已结束，实际扣费可在消费账单查看'
    : lastSettlement.deviceCleared === false ? '已记账，设备记录待清理' : lastSettlement.balancePending ? '用水已结束，余额可稍后刷新' : '用水已结算，明细可在消费账单查看';
  return `<section class="settlement-note"><h3>最近一次使用</h3><p>${message}</p></section>`;
}

function lifeView() {
  const waterAvailable = !Native.ios;
  const waterEntries = waterAvailable ? `<div class="water-entry-group"><button class="water-entry" data-action="water-shower">${icon('shower')}<span>洗澡</span></button><button class="water-entry" data-action="water-drinking">${icon('drinking')}<span>饮水</span></button></div>` : '<p class="fine">iOS 可查询钱包和账单。洗澡、饮水请使用趣智校园官方 App。</p>';
  if (!life.connected) return waterEntries + '<button class="secondary wide" data-action="life-login">连接趣智校园</button>';
  const balance = lifeSnapshot?.wallet?.money;
  return `${waterStatus()}${waterEntries}
    <div class="section-row"><h2>钱包余额</h2><div class="wallet-actions"><button class="text-button" data-action="life-refresh" ${lifeRefreshing ? 'disabled aria-busy="true" data-pending' : ''}>刷新余额</button><button class="primary" data-action="life-recharge" ${rechargeLaunching ? 'disabled aria-busy="true" data-pending' : ''}>充值</button></div></div><div class="progress-copy"><span class="muted">趣智校园钱包</span><strong>${balance != null && String(balance).trim() && Number.isFinite(Number(balance)) ? '¥ ' + esc(balance) : '—'}</strong></div>${lifeSnapshot?.updatedAt ? `<p class="fine">更新于 ${esc(new Date(lifeSnapshot.updatedAt).toLocaleString('zh-CN'))}</p>` : ''}
    <p class="fine">支付宝充值，返回后刷新余额。支付前请核对学校和账号。</p>${settlementView()}<div class="section-row"><h2>消费记录</h2></div><div class="menu-list">${menu('消费账单', '按月份查询', 'life-bills')}</div>`;
}

function waterView() {
  if (!life.connected) return `<button class="text-button subpage-back" data-action="life">返回生活</button>${empty('连接趣智校园', '登录后查看并绑定' + waterName(waterKind) + '机器。', 'life-login', '连接账号')}`;
  const devices = (life.devices || []).filter(device => device.kind === waterKind), name = waterName(waterKind);
  return `<button class="text-button subpage-back" data-action="life">返回生活</button>${waterStatus()}<div class="section-row"><h2>已绑定的机器</h2>${devices.length ? `<button class="text-button" data-action="scan-${waterKind}">扫码添加</button>` : ''}</div>
    ${devices.length ? `<div class="menu-list">${devices.map(device => `<button class="menu-item" data-device="${esc(device.mac)}" data-kind="${waterKind}"><span>${esc(device.name)}<small>${esc(device.mac)}${sameDevice(life.active?.mac, device.mac) ? ' · 有使用记录' : sameDevice(life.bluetooth?.mac, device.mac) ? ' · 已连接' : ''}</small></span><span class="device-operation">${sameDevice(life.active?.mac, device.mac) || sameDevice(life.bluetooth?.mac, device.mac) ? '查看' : '连接'}</span></button>`).join('')}</div>` : `<p class="fine">还没有绑定${name}设备。</p><button class="primary wide" data-action="scan-${waterKind}">扫码添加绑定</button>`}
    <button class="text-button wide" data-action="bind-${waterKind}">从附近设备添加</button>${settlementView()}`;
}
