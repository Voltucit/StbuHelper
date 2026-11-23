const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const script = readFileSync(require.resolve('../app/src/main/assets/frontend/shared/sync.js'), 'utf8');

async function refresh(route, accounts, studyTab, kind) {
  const calls = [];
  const page = vm.createContext({
    route, studyTab, academic: accounts, syncState: {}, manualSyncStarting: false, manualSyncId: '', lifeRefreshing: false,
    openAccount: provider => calls.push(['login', provider]), connect: () => calls.push(['accounts']),
    Native: { call: async (name, args) => { calls.push([name, args.provider, args.scope]); return { id: 'run' }; } }
  });
  vm.runInContext(script, page);
  page.pollSync = page.showSyncState = () => {};
  await page.syncNow(kind === 'learning' || kind === 'school' ? kind : 'all', kind);
  return calls;
}

test('课表页只使用教务账号，学习通登录不影响入口', async () => {
  assert.deepEqual(await refresh('week', { school: true }), [['syncAcademics', 'school', 'courses']]);
  assert.deepEqual(await refresh('week', { learning: true }), [['login', 'school']]);
});

test('首页同步已连接的账号，待办、成绩和学分保留各自的同步范围', async () => {
  for (const accounts of [{ school: true }, { learning: true }, { school: true, learning: true }]) {
    assert.deepEqual(await refresh('today', accounts), [['syncAcademics', 'all', 'home']]);
  }
  assert.deepEqual(await refresh('today', {}), [['accounts']]);
  assert.deepEqual(await refresh('study', { learning: true }, 'tasks'), [['syncAcademics', 'learning', 'homework']]);
  for (const tab of ['grades', 'exams', 'credits']) {
    assert.deepEqual(await refresh('study', { school: true }, tab), [['syncAcademics', 'school', 'records']]);
  }
});

test('账号面板的同步按钮按所选账号工作，不受底下的页面影响', async () => {
  assert.deepEqual(await refresh('week', { learning: true }, null, 'learning'), [['syncAcademics', 'learning', 'all']]);
  assert.deepEqual(await refresh('study', { school: true }, 'tasks', 'school'), [['syncAcademics', 'school', 'all']]);
});
