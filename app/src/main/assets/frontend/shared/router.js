'use strict';

let renderedView = null;
let renderedMarkup = '';

function selectedRoute(page) {
  return page === 'water' ? 'life' : page === 'settings' ? 'me' : page;
}

function updateNavigation() {
  const items = document.querySelector('.nav-items');
  if (!items.firstElementChild) {
    items.innerHTML = '<span class="nav-indicator" aria-hidden="true"></span>' + nav.map(([id, label]) =>
      `<button type="button" data-route="${id}"><span class="nav-icon">${icon(id)}</span><span>${label}</span></button>`
    ).join('');
  }
  const active = selectedRoute(route);
  items.style.setProperty('--nav-index', nav.findIndex(([id]) => id === active));
  for (const button of items.querySelectorAll('button')) {
    if (button.dataset.route === active) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  }
}

function updateStudyTabs() {
  const tabs = $('study-tabs');
  tabs.hidden = route !== 'study';
  if (tabs.hidden) return;
  if (!tabs.firstElementChild) {
    tabs.innerHTML = '<span class="segment-indicator" aria-hidden="true"></span>' + studyTabs.map(([id, label]) =>
      `<button type="button" data-study="${id}" aria-pressed="false">${label}</button>`
    ).join('');
  }
  tabs.style.setProperty('--tab-index', studyTabs.findIndex(([id]) => id === studyTab));
  for (const button of tabs.querySelectorAll('button')) {
    button.setAttribute('aria-pressed', String(button.dataset.study === studyTab));
  }
}

function animatePage(previous, next, content) {
  if (!previous) { Motion.enter(content, 0, 4); return; }
  if (previous.route !== next.route) {
    const index = page => nav.findIndex(([id]) => id === selectedRoute(page));
    const offset = Math.sign(index(next.route) - index(previous.route)) * 9;
    Motion.enter(content, offset, offset ? 0 : 7);
    return;
  }
  if (next.route === 'week' && previous.week !== next.week) {
    Motion.enter(content.querySelector('.table-scroll'), Math.sign(next.week - previous.week) * 8, 0, 165);
  } else if (next.route === 'study' && previous.studyTab !== next.studyTab) {
    Motion.enter(content, 0, 5, 165);
  } else if (next.route === 'water' && previous.waterKind !== next.waterKind) {
    Motion.enter(content, 0, 5, 165);
  }
}

function render() {
  // A background refresh must not replace a dialog or a settings form the user is editing.
  const focused = document.activeElement;
  const editing = focused?.matches('input:not([type=checkbox]):not([type=radio]),textarea');
  const dirty = $('settings-messages-form')?.dataset.dirty === 'true';
  if (InputCompatibility.active() || $('sheet').open || route === 'settings' && (dirty || editing)) {
    renderPending = true;
    showSyncState();
    return;
  }
  renderPending = false;
  $('sync-button').hidden = route === 'settings';
  const names = { today: '今天', week: '课表', study: '学习', life: '生活', me: '我的', settings: '设置', water: waterName(waterKind) };
  $('page-title').textContent = names[route] || '今天';
  $('page-subtitle').textContent = (route === 'today' ? new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }) + ' · ' : '') + appInfo.name;
  updateNavigation();
  updateStudyTabs();
  showSyncState();

  const content = $('page-content');
  const views = { today: todayView, week: weekView, study: studyView, life: lifeView, water: waterView, settings: settingsView, me: meView };
  const markup = (views[route] || todayView)();
  const previous = renderedView;
  const next = { route, week, studyTab, waterKind };
  renderedView = next;
  scheduleContentRefresh();
  if (previous?.route === route && markup === renderedMarkup) return;

  const oldScroll = content.querySelector('.table-scroll')?.scrollLeft || 0;
  const gradeFilter = $('grade-search')?.value || '';
  const focusId = previous?.route === route && content.contains(focused) ? focused.id : '';
  renderedMarkup = markup;
  content.innerHTML = markup;

  const table = content.querySelector('.table-scroll');
  if (table && previous?.route === 'week') table.scrollLeft = oldScroll;
  if (route === 'settings') bindSettingsPage();

  const weekSelect = $('week-select');
  if (weekSelect) weekSelect.onchange = event => { week = Number(event.target.value); render(); };

  const search = $('grade-search');
  const results = $('records');
  if (search && results) {
    const filter = () => {
      const query = search.value.toLowerCase();
      const matching = data.grades.filter(grade => grade.name.toLowerCase().includes(query));
      results.innerHTML = matching.length ? recordRows(matching, 'grades') : empty('没有匹配的课程', '');
    };
    search.value = gradeFilter;
    if (gradeFilter) filter();
    search.oninput = () => { filter(); Motion.enter(results, 0, 0, 130); };
  }
  if (focusId === 'week-select') weekSelect?.focus({ preventScroll: true });
  animatePage(previous, next, content);
}
async function navigate(page) {
  if (page === 'week' && route === 'today') return goCurrentWeek();
  route = page; render(); window.scrollTo(0, 0);
  if (page === 'life' && life.connected && !lifeSnapshot) try { await lifeRefresh(true); } catch (_) {}
}

async function action(name) {
  if (name === 'week') return goCurrentWeek();
  if (name === 'water-shower' || name === 'water-drinking') { waterKind = name.slice(6); return navigate('water'); }
  if (name === 'scan-shower' || name === 'scan-drinking') return scanAndBind(name.slice(5));
  if (name === 'tasks') { studyTab = 'tasks'; return navigate('study'); }
  if (nav.some(([id]) => id === name)) return navigate(name);
  if (name.startsWith('import-') && !['import-file'].includes(name)) {
    const kind = name.slice(7), provider = ['courses', 'grades', 'exams', 'portal', 'school'].includes(kind) ? 'school' : 'learning';
    if (kind === 'portal') return openAccount(provider);
    if (!academic[provider]) return openAccount(provider);
    closeSheet(); return syncNow(provider, kind);
  }
  const actions = {
    connect, settings: academicSettings, 'app-settings': () => navigate('settings'), 'add-course': () => courseEditor(), 'school-account': schoolAccount,
    'open-learning': () => openAccount('learning'), 'open-school': () => openAccount('school'),
    'official-learning': () => openOfficialAccount('learning'), 'official-school': () => openOfficialAccount('school'),
    'logout-learning': () => logoutAccount('learning'), 'logout-school': () => logoutAccount('school'), 'sync-now': () => syncNow(), 'reminders': reminderSettings, 'tracking': () => trackingSettings(),
    'current-week': goCurrentWeek,
    'prev-week': () => { week = Math.max(1, week - 1); render(); }, 'next-week': () => { week = Math.min(60, week + 1); render(); },
    'import-file': () => Native.call('importFile'), 'export-file': () => Native.call('exportFile'),
    'clear-data': () => confirmAction('清除本机学习数据？', '课程、成绩、考试和待办将被删除。建议先导出备份。', async () => { data = M.validate(await Native.call('clearAcademicData')); render(); }),
    'forget-account': () => confirmAction('删除学校凭据？', '删除本机加密保存的学号密码，并清除网页登录会话。学习数据继续保留。', async () => { credential = await Native.call('clearCredentials'); academic.school = false; }),
    'life-login': lifeLogin, 'life-recharge': lifeRecharge, 'life-refresh': () => life.connected ? lifeRefresh() : lifeLogin(), 'life-bills': lifeBills, 'bind-shower': () => lifeDevices('shower'), 'bind-drinking': () => lifeDevices('drinking'),
    'life-end-active': () => finishWater(), 'life-review-rate': reviewRateOrder,
    'life-logout': () => confirmAction('退出趣智校园？', '请先结束正在使用的水表并完成结算。', async () => { life = await Native.call('lifeLogout'); clearRechargeReturn(); lifeSnapshot = null; lastSettlement = null; }),
    privacy: () => sheet('隐私与数据', '<div class="policy"><p>账号凭据或会话只使用系统安全存储。Android 使用 Keystore，iOS 使用本机钥匙串。</p><p>学校与学习通登录、数据同步直接访问各自官方服务。趣智校园使用自己的账号，手机直接访问其官方接口；学校密码不会发给趣智校园。</p><p>趣智校园密码只在登录请求期间使用，登录令牌另行加密保存在本机。学习数据保存在应用私有目录，应用禁止云备份和设备迁移。</p><p>本应用不包含广告、使用统计或开发者代理服务器。备份文件不包含账号密码、会话 Cookie、趣智校园令牌或带签名的作业链接。</p><p>学校官方页面可能自行加载第三方资源。登录页与本地应用界面分开，官方网页没有调用本机密钥库的桥接权限。</p></div>'),
    credits: () => sheet('开源项目', '<div class="policy"><p>趣智校园协议参考 LinYu（MIT）：https://github.com/yehu-imei/linyu。</p><p>学习通作业查询参考 xuexitong-cli（MIT）。课表、成绩和考试来自学校正方教务系统。</p><p>产品功能参考航院通（MIT）。本项目没有使用其服务器或遥测功能。</p><p>完整许可与来源见项目 THIRD_PARTY_NOTICES.md。</p></div>')
  };
  return actions[name]?.();
}
