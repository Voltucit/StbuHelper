'use strict';

let receiptConfirmationRequired = false;
let receiptVisibleId = '';
let receiptRefreshing = false;

function receiptFields(receipt) {
  const rows = [
    ['设备名', receipt.deviceName || '未提供'], ['设备类型', receipt.deviceType || '未提供'],
    ['消费金额', receipt.money != null ? '¥ ' + receipt.money : '待官方账单返回'],
    ['消费时间', receipt.consumeTime || '待官方账单返回'], ['订单号', receipt.orderNo || '官方未提供']
  ];
  return `<dl class="receipt-details">${rows.map(([label, value]) => `<div><dt>${esc(label)}</dt><dd>${esc(value)}</dd></div>`).join('')}</dl>${receipt.deviceCleared === false ? '<p class="fine">服务端已记账，设备记录仍待清理；确认账单后可继续核对设备。</p>' : ''}`;
}

function rememberReceipt(receipt) {
  if (!lastSettlement || lastSettlement.id !== receipt.id) lastSettlement = { id: receipt.id, orderNo: receipt.orderNo, money: receipt.money, balancePending: false };
  else lastSettlement.money = receipt.money;
}

function showWaterReceipt(receipt) {
  if (!receipt?.id || receiptConfirmationRequired) return;
  receiptVisibleId = receipt.id;
  rememberReceipt(receipt);
  sheet('本次用水账单', `<div id="receipt-fields">${receiptFields(receipt)}</div><p class="fine" id="receipt-status" role="status">正在查询官方账单</p><button class="text-button wide" id="refresh-receipt">刷新明细</button><button class="primary wide" id="confirm-receipt">确认</button><p class="fine stats-note">请核对账单后确认。确认仅表示已查看，不会再次扣费。</p>`, () => {
    receiptConfirmationRequired = true;
    $('close-sheet').disabled = true;
    $('refresh-receipt').onclick = refreshWaterReceipt;
    $('confirm-receipt').onclick = async () => {
      const button = $('confirm-receipt'); Motion.busy(button, true);
      try {
        life = await Native.call('lifeConfirmReceipt', { id: receiptVisibleId });
        receiptConfirmationRequired = false; receiptVisibleId = ''; $('close-sheet').disabled = false; closeSheet(); render();
      } catch (error) { if ($('receipt-status')) $('receipt-status').textContent = error.message; }
      finally { if (button.isConnected) Motion.busy(button, false); }
    };
  });
  refreshWaterReceipt();
}

async function refreshWaterReceipt() {
  if (!receiptConfirmationRequired || receiptRefreshing) return;
  receiptRefreshing = true;
  const id = receiptVisibleId, refresh = $('refresh-receipt'), confirm = $('confirm-receipt');
  Motion.busy(refresh, true); confirm.disabled = true;
  try {
    const receipt = await Native.call('lifeReceipt', { id });
    if (receiptVisibleId !== id || !$('receipt-fields')) return;
    life.receipt = receipt; rememberReceipt(receipt);
    $('receipt-fields').innerHTML = receiptFields(receipt);
    Motion.enter($('receipt-fields'), 0, 4, 155);
    $('receipt-status').textContent = receipt.money == null ? '明细尚未返回，可稍后刷新或在消费账单查看。' : '金额和时间来自同一订单的官方账单。';
  } catch (_) { if (receiptVisibleId === id && $('receipt-status')) $('receipt-status').textContent = '明细暂未更新，可稍后刷新；不会把未返回金额当作 0 元。'; }
  finally { receiptRefreshing = false; if (refresh.isConnected) Motion.busy(refresh, false); if (confirm.isConnected) confirm.disabled = false; }
}

function maybeShowReceipt() {
  if (!document.hidden && life.connected && life.receipt?.id && !receiptConfirmationRequired && !InputCompatibility.active() && !$('sheet').open) showWaterReceipt(life.receipt);
}
