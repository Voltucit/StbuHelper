'use strict';

async function lifeLogin() {
  if (life.connected) return sheet('趣智校园账号', '<p>账号已连接，会话与绑定设备保存在本机。</p><button class="danger wide" data-action="life-logout">退出趣智校园</button>');
  sheet('连接趣智校园', `<p class="fine">使用趣智校园的手机号和密码。密码仅用于此次官方登录，登录会话加密保存在本机。</p><form id="life-form"><label for="life-phone">手机号</label><input id="life-phone" type="tel" autocomplete="off" inputmode="tel" pattern="1[0-9]{10}" required placeholder="趣智校园绑定的手机号"><label for="life-password">密码</label><input id="life-password" type="password" autocomplete="off" required placeholder="趣智校园密码"><p class="form-error" id="life-error" role="alert"></p><button class="primary wide">登录并保存会话</button></form>${life.connected ? '<button class="text-button wide" data-action="life-logout">退出趣智校园</button>' : ''}`, () => {
    $('life-form').onsubmit = async event => { event.preventDefault(); const button = event.submitter, form = event.currentTarget, errorBox = $('life-error'), passwordInput = $('life-password'); Motion.busy(button, true); try { life = await Native.call('lifeLogin', { telephone: $('life-phone').value, password: passwordInput.value }); passwordInput.value = ''; if (form.isConnected) closeSheet(); render(); await lifeRefresh(true); } catch (error) { if (errorBox.isConnected) errorBox.textContent = error.message; else toast(error.message); } finally { Motion.busy(button, false); } };
  });
}

function lifeRefresh(quiet = false) {
  if (lifeRefreshPromise) return lifeRefreshPromise;
  lifeRefreshing = true; showSyncState();
  lifeRefreshPromise = (async () => {
    try { lifeSnapshot = await Native.call('lifeRefresh'); render(); if (!quiet) toast('余额已更新'); }
    finally { lifeRefreshing = false; lifeRefreshPromise = null; showSyncState(); }
  })();
  return lifeRefreshPromise;
}
