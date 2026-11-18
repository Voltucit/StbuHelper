'use strict';

function toast(message) {
  clearTimeout(timer);
  const notice = $('toast');
  const dialog = $('sheet');
  const parent = dialog.open && !dialog.classList.contains('is-closing') ? dialog : document.body;
  if (notice.parentElement !== parent) parent.append(notice);
  notice.hidden = false;
  notice.textContent = message;
  Motion.enter(notice, 0, 7, 170);
  timer = setTimeout(() => {
    const exit = Motion.leave(notice, 5);
    if (exit) exit.finished.then(() => { notice.hidden = true; }, () => {});
    else notice.hidden = true;
  }, 4500);
}

function sheet(title, html, setup) {
  if (receiptConfirmationRequired) return false;
  const dialog = $('sheet');
  const opening = !dialog.open || dialog.classList.contains('is-closing');
  Motion.stop(dialog);
  Motion.stop($('sheet-body'));
  dialog.classList.remove('is-closing');
  dialog.inert = false;
  cancelAccountLogin();
  sheetSerial++;
  $('sheet-title').textContent = title;
  const body = $('sheet-body');
  body.innerHTML = html;
  if (!dialog.open) dialog.showModal();
  dialog.scrollTop = 0;
  $('sheet-title').focus({ preventScroll: true });
  setup?.();
  if (opening) Motion.enter(dialog, 0, 16, 190);
  else Motion.enter(body, 0, 5, 145);
  return true;
}

function cleanClosedSheet() {
  const dialog = $('sheet');
  if (dialog.open) return;
  dialog.classList.remove('is-closing');
  dialog.inert = false;
  cancelAccountLogin();
  $('sheet-body').replaceChildren();
  if ($('toast').parentElement === dialog) document.body.append($('toast'));
  if (renderPending) render();
  maybeShowReceipt();
}

function closeSheet() {
  const dialog = $('sheet');
  if (receiptConfirmationRequired || !dialog.open || dialog.classList.contains('is-closing')) return;
  cancelAccountLogin();
  const closing = ++sheetSerial;
  if (dialog.contains(document.activeElement)) document.activeElement.blur();
  dialog.classList.add('is-closing');
  dialog.inert = true;
  const finish = () => {
    if (sheetSerial !== closing || !dialog.open) return;
    dialog.close();
    cleanClosedSheet();
  };
  const exit = Motion.leave(dialog, 13);
  if (exit) exit.finished.then(finish, () => {});
  else finish();
}

function empty(title, text, action, label = '连接并同步') {
  return `<div class="empty"><h3>${esc(title)}</h3><p>${esc(text)}</p>${action ? `<button class="secondary" data-action="${action}">${label}</button>` : ''}</div>`;
}

function menu(title, description, action) {
  return `<button class="menu-item" data-action="${action}"><span>${esc(title)}${description ? `<small>${esc(description)}</small>` : ''}</span><span class="arrow" aria-hidden="true">›</span></button>`;
}

function footer() {
  return `<p class="page-footer">${data.updatedAt ? '上次同步 ' + esc(new Date(data.updatedAt).toLocaleString('zh-CN', { hour12: false })) : '数据保存在本机，同步后可离线查看'}</p>`;
}

function confirmAction(title, description, action) {
  sheet(title, `<p>${esc(description)}</p><div class="form-actions"><button class="secondary" id="cancel-confirm">取消</button><button class="danger" id="accept-confirm">确认</button></div>`, () => {
    $('cancel-confirm').onclick = closeSheet;
    const button = $('accept-confirm');
    button.onclick = async () => {
      Motion.busy(button, true);
      try { await action(); if (button.isConnected) closeSheet(); render(); }
      catch (error) { toast(error.message); }
      finally { Motion.busy(button, false); }
    };
  });
}
