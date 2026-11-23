'use strict';

function scheduleContentRefresh() {
  clearTimeout(contentRefresh);
  if (document.hidden) return;
  if (route === 'life' || route === 'water') { contentRefresh = setTimeout(pollLifeState, 3000); return; }
  const now = Date.now(); let due;
  if (route === 'today' || route === 'study' && studyTab === 'tasks') due = Math.min((Math.floor(now / 60000) + 1) * 60000, ...visibleTasks().map(task => Number(task.deadlineAt) + 25));
  else if (route === 'week') { const tomorrow = new Date(); tomorrow.setHours(24, 0, 0, 0); due = +tomorrow; }
  if (due) contentRefresh = setTimeout(render, Math.max(100, due - now));
}

function applyBoot(boot) {
  if (boot.preferences) Preferences.apply(boot.preferences);
  if (boot.app) appInfo = boot.app;
  document.title = appInfo.name;
  const previousSemester = data.settings.semesterStart;
  data = M.validate(boot.data);
  if (data.settings.semesterStart !== previousSemester) week = currentWeek();
  credential = boot.credential; life = boot.life || { connected: false };
  academic = boot.academic || { learning: false, school: false }; reminders = boot.reminders || { enabled: false, permission: false }; syncState = boot.sync || { running: false };
  tracking = boot.tracking || { custom: false, selected: [], courses: [] };
  if (boot.launchPage === 'tasks') { route = 'study'; studyTab = 'tasks'; }
  if (boot.launchPage === 'week') { route = 'week'; week = currentWeek(); }
  if (!booted && !syncState.running) lastSyncNotice = syncState.id || '';
  render(); if (syncState.running) pollSync(); else if (booted) notifySync(syncState);
  booted = true;
  maybeShowReceipt();
}

async function syncNow(provider = 'all', kind) {
  if (manualSyncStarting || syncState.running || lifeRefreshing) return;
  if (route === 'life' || route === 'water') return life.connected ? lifeRefresh() : lifeLogin();
  const section = kind || (route === 'study' ? studyTab : route);
  let scope = 'all';
  if (section === 'week' || section === 'courses') { scope = 'courses'; provider = 'school'; }
  else if (section === 'tasks') { scope = 'homework'; provider = 'learning'; }
  else if (['exams', 'grades', 'credits'].includes(section)) { scope = 'records'; provider = 'school'; }
  else if (section === 'today') scope = 'home';
  if (provider !== 'all' && !academic[provider]) return openAccount(provider);
  if (!academic.learning && !academic.school) return connect();
  manualSyncStarting = true; showSyncState();
  try {
    syncState = await Native.call('syncAcademics', { provider, scope });
    manualSyncId = syncState.id || ''; showSyncState(); pollSync();
  } finally { manualSyncStarting = false; showSyncState(); }
}

function showSyncState() {
  const busy = !!syncState.running || manualSyncStarting || lifeRefreshing;
  $('sync-button').disabled = busy;
  document.querySelectorAll('button[data-action="life-refresh"]').forEach(button => Motion.busy(button, lifeRefreshing));
  $('sync-button').setAttribute('aria-busy', String(busy));
  $('sync-button').setAttribute('aria-label', busy ? '正在同步' : '同步数据，也可在页面顶部下拉刷新');
  PullRefresh.setBusy(busy);
}

function notifySync(state) {
  if (state.running || !state.id || state.id === lastSyncNotice) return;
  lastSyncNotice = state.id;
  if (state.success) toast('同步成功');
  else if (state.id === manualSyncId) toast(state.needLogin?.length ? '账号登录已失效，请重新连接' :
    state.scheduleIssue || (state.homeworkComplete === false && state.homeworkIssue ? state.homeworkIssue : '同步未完成，已保留本机数据'));
}

function pollSync(delay = 1500) {
  clearTimeout(syncPoll);
  if (document.hidden) return;
  syncPoll = setTimeout(async () => {
    try {
      syncState = await Native.call('academicState'); showSyncState();
      if (syncState.running) { if (syncState.updatedAt && syncState.updatedAt !== data.updatedAt) applyBoot(await Native.call('bootstrap')); else pollSync(); } else { const completed = syncState; applyBoot(await Native.call('bootstrap')); notifySync(completed); }
    } catch (_) { pollSync(3000); }
  }, delay);
}
