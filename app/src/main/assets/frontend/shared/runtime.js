'use strict';
const M = CampusModel;
const $ = id => document.getElementById(id);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const paths = {
  today: '<path d="M3 10l9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z"/>',
  week: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 3v4m10-4v4M3 11h18m-12 0v10m6-10v10"/>',
  study: '<path d="M3 4h6a4 4 0 0 1 3 2 4 4 0 0 1 3-2h6v15h-6a4 4 0 0 0-3 2 4 4 0 0 0-3-2H3zM12 6v15"/>',
  shower: '<path d="M5 10V6a3 3 0 0 1 6 0v1m-3 3a4 4 0 0 1 8 0H8zm1 4v2m4-2v2m4-2v2M9 19v2m4-2v2m4-2v2"/>',
  drinking: '<path d="M5 9h12l-1 12H6L5 9zm12 2h2a2 2 0 0 1 0 4h-2M9 3v3m4-3v3"/>',
  life: '<path d="M4 11h16v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2zM8 11V7a4 4 0 0 1 8 0v4m-8 4h8"/>',
  me: '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',
  sync: '<path d="M20 7v5h-5M4 17v-5h5m-4-3a7 7 0 0 1 12-4l3 3M4 16l3 3a7 7 0 0 0 12-4"/>',
  close: '<path d="M6 6l12 12M6 18L18 6"/>',
  next: '<path d="m9 5 7 7-7 7"/>',
  previous: '<path d="m15 5-7 7 7 7"/>'
};
const icon = key => `<svg viewBox="0 0 24 24" aria-hidden="true">${paths[key]}</svg>`;
const nav = [['today', '今天'], ['week', '课表'], ['study', '学习'], ['life', '生活'], ['me', '我的']];
let appInfo = { name: '校园助手', versionName: '' };
let data = M.empty(), credential = { saved: false }, route = 'today', studyTab = 'tasks', week = 1;
let life = { connected: false }, lifeSnapshot = null, timer;
let waterKind = 'shower', lastSettlement = null, sheetSerial = 0, contentRefresh;
let waterEnding = false;
let renderPending = false;
let lifePolling = false;
let manualSyncStarting = false, lifeRefreshing = false, lifeRefreshPromise = null;
let academic = { learning: false, school: false }, reminders = { enabled: false, permission: false }, syncState = { running: false }, syncPoll, booted = false, lastSyncNotice = '', manualSyncId = '';
let tracking = { custom: false, selected: [], courses: [] };
