'use strict';

let receiptConfirmationRequired = false;

function receiptFields(receipt) {
  const rows = [
    ['设备名', receipt.deviceName || '未提供'], ['设备类型', receipt.deviceType || '未提供']
  ];
  return `<dl class="receipt-details">${rows.map(([label, value]) => `<div><dt>${esc(label)}</dt><dd>${esc(value)}</dd></div>`).join('')}</dl>${receipt.deviceCleared === false ? '<p class="fine">服务端已记账，设备记录仍待清理。确认后可继续核对设备。</p>' : ''}`;
}

function showWaterReceipt(receipt) {
  if (!receipt?.id || receiptConfirmationRequired) return;
  const settled = receipt.settlementConfirmed !== false;
  if (lastSettlement?.id !== receipt.id) lastSettlement = { id: receipt.id, balancePending: false };
  lastSettlement.deviceCleared = receipt.deviceCleared !== false;
  lastSettlement.settlementConfirmed = settled;
  lastSettlement.deviceType = receipt.deviceType;
  sheet(settled ? '本次用水结算' : waterEndedTitle(receipt.deviceType), `${settled ? receiptFields(receipt) : ''}<p class="fine">实际扣费可在「生活 → 消费账单」查看。</p><p class="form-error" id="receipt-error" role="alert"></p><button class="primary wide" id="confirm-receipt">${settled ? '确认' : '知道了'}</button>`, () => {
    receiptConfirmationRequired = true;
    $('close-sheet').disabled = true;
    $('confirm-receipt').onclick = async () => {
      const button = $('confirm-receipt'); Motion.busy(button, true);
      try {
        life = await Native.call('lifeConfirmReceipt', { id: receipt.id });
        receiptConfirmationRequired = false; $('close-sheet').disabled = false; closeSheet(); render();
      } catch (error) { if ($('receipt-error')) $('receipt-error').textContent = error.message; }
      finally { if (button.isConnected) Motion.busy(button, false); }
    };
  });
}

function maybeShowReceipt() {
  if (!document.hidden && life.connected && life.receipt?.id && !receiptConfirmationRequired && !InputCompatibility.active() && !$('sheet').open) showWaterReceipt(life.receipt);
}
