'use strict';

const accountLogin = { active: false, serial: 0, timer: null, id: null, stage: '' };

function cancelAccountLogin() {
  accountLogin.serial++; clearTimeout(accountLogin.timer);
  if (accountLogin.active) { accountLogin.active = false; Native.call('cancelLogin').catch(() => {}); }
  accountLogin.stage = ''; accountLogin.id = null;
}

function schoolAccount() { return openAccount('school'); }

function connect() {
  sheet('连接校园账号', `<p class="fine">在 App 内输入账号登录。会话有效时自动复用；只有主动选择时才打开官方网页。</p><div class="menu-list">${menu('学习通', academic.learning ? '已连接 · 课程作业' : '使用手机号或超星号登录', 'open-learning')}${menu('学校教务系统', academic.school ? '已连接 · 课表、考试和成绩' : '使用四川工商学院学号登录', 'open-school')}</div>`);
}

function logoutAccount(provider) { confirmAction('退出这个账号？', '删除本机保存的对应登录会话，学习数据继续保留。', async () => { academic = await Native.call('academicLogout', { provider }); }); }

async function openOfficialAccount(provider) { closeSheet(); await Native.call('openPortal', { kind: provider }); }

async function openAccount(provider) {
  const title = provider === 'school' ? '学校教务账号' : '学习通账号';
  if (academic[provider]) {
    sheet(title, `<p>账号已连接，会话有效时无需再次登录。</p><button class="primary wide" data-action="import-${provider}">同步数据</button><button class="secondary wide" data-action="official-${provider}">打开官方网页</button><button class="text-button wide" data-action="logout-${provider}">退出本机账号</button>`); return;
  }
  if (Native.ios) {
    sheet(title, '<p class="fine">在 App 内打开官方登录页。会话由 iOS 钥匙串保存在本机。</p><button class="primary wide" data-action="official-' + provider + '">登录账号</button>');
    return;
  }
  sheet(title, `<p class="fine">账号仅发送到${provider === 'school' ? '学校' : '学习通'}官方服务。图片验证码、学校短信验证可在这里完成。</p><form id="account-form"><label for="account-username">${provider === 'school' ? '学号' : '手机号 / 超星号'}</label><input id="account-username" autocomplete="off" required maxlength="128" placeholder="${provider === 'school' ? '请输入学号' : '请输入手机号或超星号'}"><label for="account-password">密码</label><input id="account-password" type="password" autocomplete="off" maxlength="1024" placeholder="请输入${provider === 'school' ? '统一身份认证' : '学习通'}密码"><label class="account-remember"><input id="account-remember" type="checkbox" checked>在本机加密保存账号密码</label><p class="form-error" id="account-error" role="alert"></p><p class="fine" id="account-status" role="status" aria-live="polite"></p><button class="primary wide" type="submit" id="account-submit">登录</button></form><div id="account-challenge"></div><button class="text-button wide" data-action="official-${provider}">打开官方网页（微信 / QQ / 其他验证）</button><p class="fine">账号密码通过 Android Keystore 加密保存。取消勾选后仍保存有效会话，退出账号可清除会话。</p>`);
  const opened = sheetSerial;
  try {
    const profile = await Native.call('loginProfile', { provider });
    if (opened !== sheetSerial || !$('account-form')) return;
    if (!$('account-username').value) $('account-username').value = profile.username || '';
    if (profile.saved) $('account-password').placeholder = '已加密保存；留空可使用原密码';
  } catch (error) { if (opened === sheetSerial && $('account-error')) $('account-error').textContent = error.message; }
  if (opened !== sheetSerial || !$('account-form')) return;
  $('account-form').onsubmit = async event => {
    event.preventDefault(); if (accountLogin.active) return;
    const serial = ++accountLogin.serial;
    accountLogin.active = true; accountLogin.stage = ''; accountLogin.id = null;
    $('account-error').textContent = ''; $('account-challenge').replaceChildren();
    Motion.busy($('account-submit'), true); $('account-username').disabled = true; $('account-password').disabled = true; $('account-remember').disabled = true;
    $('account-status').textContent = '正在登录';
    const args = { provider, username: $('account-username').value, password: $('account-password').value, remember: $('account-remember').checked };
    const pending = Native.call('beginLogin', args); args.password = ''; $('account-password').value = '';
    try {
      const state = await pending;
      if (!accountLogin.active || serial !== accountLogin.serial) return;
      accountLogin.id = state.id; paintAccountLogin(state); pollAccountLogin(serial);
    } catch (error) { if (serial === accountLogin.serial) finishAccountLogin(error.message); }
  };
}

function finishAccountLogin(message) {
  accountLogin.active = false; clearTimeout(accountLogin.timer);
  if (!$('account-form')) return;
  $('account-error').textContent = message; $('account-status').textContent = '';
  Motion.busy($('account-submit'), false); $('account-username').disabled = false; $('account-password').disabled = false; $('account-remember').disabled = false;
  $('account-challenge').replaceChildren(); accountLogin.stage = '';
}

function paintAccountLogin(state) {
  if (!$('account-form')) return;
  $('account-status').textContent = ['captcha', 'sms'].includes(state.stage) ? '' : state.message || '正在登录';
  if (!['captcha', 'sms'].includes(state.stage)) { $('account-challenge').replaceChildren(); accountLogin.stage = state.stage; return; }
  if (accountLogin.stage !== state.stage) {
    $('account-challenge').innerHTML = `<form id="account-code-form"><label for="account-code">${state.stage === 'sms' ? '短信验证码' : '图片验证码'}</label>${state.stage === 'captcha' ? '<div class="account-captcha"><img id="account-captcha-image" alt="官方登录验证码"><button class="secondary" type="button" id="account-refresh-captcha">换一张</button></div>' : '<p class="fine" id="account-phone"></p><button class="secondary" type="button" id="account-send-sms">获取短信验证码</button>'}<input id="account-code" inputmode="numeric" autocomplete="one-time-code" required maxlength="32"><p class="form-error" id="account-code-error" role="alert"></p><button class="primary wide" type="submit">验证并登录</button></form>`;
    $('account-code-form').onsubmit = async event => {
      event.preventDefault(); const button = event.submitter; Motion.busy(button, true);
      try { paintAccountLogin(await Native.call('submitLoginChallenge', { code: $('account-code').value.trim() })); }
      catch (error) { if ($('account-code-error')) $('account-code-error').textContent = error.message; }
      finally { if (button.isConnected) Motion.busy(button, false); }
    };
    if (state.stage === 'captcha') $('account-refresh-captcha').onclick = async () => { try { paintAccountLogin(await Native.call('refreshLoginCaptcha')); } catch (error) { toast(error.message); } };
    else $('account-send-sms').onclick = async () => { const button = $('account-send-sms'); Motion.busy(button, true); try { paintAccountLogin(await Native.call('sendLoginSms')); } catch (error) { toast(error.message); Motion.busy(button, false); } };
  }
  accountLogin.stage = state.stage; $('account-code-error').textContent = state.message || '';
  if (state.stage === 'captcha' && /^data:image\/png;base64,/.test(state.image || '')) { if ($('account-captcha-image').src !== state.image) $('account-captcha-image').src = state.image; }
  if (state.stage === 'sms') { $('account-phone').textContent = state.phone ? `验证码将发送至${state.phone}的手机` : '请获取学校短信验证码'; $('account-send-sms').disabled = !state.canSend; $('account-send-sms').textContent = state.canSend ? '获取短信验证码' : '请稍后重新获取'; }
}

async function pollAccountLogin(serial) {
  if (!accountLogin.active || serial !== accountLogin.serial) return;
  try {
    const state = await Native.call('loginStatus');
    if (!accountLogin.active || serial !== accountLogin.serial || state.id !== accountLogin.id) return;
    if (state.stage === 'success') {
      accountLogin.active = false; clearTimeout(accountLogin.timer);
      const boot = await Native.call('bootstrap');
      if (serial !== accountLogin.serial || !$('account-form')) return;
      applyBoot(boot); closeSheet(); render(); toast('登录成功'); return;
    }
    if (['failed', 'webRequired', 'cancelled'].includes(state.stage)) { finishAccountLogin(state.message || '登录已取消'); return; }
    paintAccountLogin(state);
  } catch (error) { if (serial === accountLogin.serial) { Native.call('cancelLogin').catch(() => {}); finishAccountLogin(error.message); } return; }
  if (accountLogin.active && serial === accountLogin.serial) accountLogin.timer = setTimeout(() => pollAccountLogin(serial), 800);
}
