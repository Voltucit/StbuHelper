'use strict';

let settingsSaving = false;
let pendingAppearance = null;

function themeOptions(colors, selected) {
  return colors.map(({ id, name }) => `<label class="theme-choice" data-color="${id}">
    <input type="radio" name="theme-color" value="${id}" ${selected === id ? 'checked' : ''}>
    <span class="theme-dot" aria-hidden="true"></span><span>${name}</span>
  </label>`).join('');
}

function settingsView() {
  const p = Preferences.current();
  const { min, max, step } = Preferences.brightnessRange;
  return `<button class="text-button subpage-back" data-route="me">返回我的</button>
    <h2 class="settings-first-heading">外观</h2>
    <fieldset class="theme-options" id="theme-options"><legend>主题颜色</legend>
      <p class="fine theme-group-label" id="theme-agenda-label">蓝线日程配色</p>
      <div class="theme-choices" role="group" aria-labelledby="theme-agenda-label">${themeOptions(Preferences.colors.filter(color => color.agenda), p.color)}</div>
      <p class="fine theme-group-label" id="theme-classic-label">经典配色</p>
      <div class="theme-choices" role="group" aria-labelledby="theme-classic-label">${themeOptions(Preferences.colors.filter(color => !color.agenda), p.color)}</div>
    </fieldset>
    <label class="night-setting" for="night-mode"><span>夜间模式<small id="night-mode-description">${p.dark ? '已开启' : '已关闭'}</small></span><input id="night-mode" type="checkbox" role="switch" ${p.dark ? 'checked' : ''}><span class="switch-track" aria-hidden="true"></span></label>
    <p class="settings-save-status fine" id="appearance-status" role="status" aria-live="polite"></p>
    <h2>背景</h2><p class="fine">选择本机图片作为 App 背景，以淡色显示保证文字清晰。支持 JPG、PNG、WebP，最大 8 MB、1600 万像素。</p><div class="background-actions"><button class="secondary" id="choose-background">选择背景图片</button><button class="text-button" id="clear-background" ${p.backgroundUrl ? '' : 'disabled'}>恢复默认背景</button></div><p class="settings-save-status fine" id="background-status" role="status">${p.backgroundUrl ? '已使用本机背景图片' : '当前为默认背景'}</p>
    <div class="background-brightness" id="background-brightness-control" ${p.backgroundUrl ? '' : 'hidden'}>
      <div><label for="background-brightness">图片亮度</label><output for="background-brightness" id="background-brightness-value">${p.backgroundBrightness}%</output></div>
      <input type="range" id="background-brightness" min="${min}" max="${max}" step="${step}" value="${p.backgroundBrightness}">
    </div>
    ${Native.ios ? '' : `<h2>输入</h2><label class="night-setting" for="native-input"><span>输入法兼容模式<small id="native-input-description">${p.nativeInput ? '已开启 · 使用系统输入框' : '键盘自动收起时可开启'}</small></span><input id="native-input" type="checkbox" role="switch" ${p.nativeInput ? 'checked' : ''}><span class="switch-track" aria-hidden="true"></span></label>`}
    <h2>首页提示</h2><form id="settings-messages-form"><fieldset id="settings-message-fields" class="settings-message-fields"><label for="empty-day-message">今天没有课程时</label><textarea id="empty-day-message" maxlength="200" rows="2" placeholder="${esc(Preferences.defaults.emptyDayMessage)}">${esc(p.emptyDayMessage)}</textarea><label for="empty-tasks-message">没有未做作业时</label><textarea id="empty-tasks-message" maxlength="200" rows="2" placeholder="${esc(Preferences.defaults.emptyTasksMessage)}">${esc(p.emptyTasksMessage)}</textarea><p class="fine">每项最多 200 字，留空保存可恢复默认提示。</p><button class="primary wide" type="submit">保存首页提示</button></fieldset><p class="settings-save-status fine" id="messages-status" role="status" aria-live="polite"></p></form>
    <h2>学习与通知</h2><div class="menu-list">${menu('学期与作息', data.settings.semesterStart ? '开学日期 ' + data.settings.semesterStart : '调整开学日期、学分和上课时间', 'settings')}${menu('作业提醒', reminders.enabled ? (reminders.permission ? '已开启' : '已开启，需允许系统通知') : '新作业与截止前提醒', 'reminders')}</div>
    <h2>本机数据</h2><div class="menu-list">${menu('导入备份', '从本应用导出的 JSON 文件恢复学习数据', 'import-file')}${menu('导出备份', '包含学习数据和学期设置，不含账号密码', 'export-file')}${menu('清除学习数据', '删除课程、成绩、考试和待办', 'clear-data')}</div><p class="fine stats-note">外观和首页提示单独保存在本机，清除学习数据不会重置它们。</p>`;
}

function updateSettingsControls() {
  if (route !== 'settings' || !$('theme-options')) return;
  const p = { ...Preferences.current(), ...pendingAppearance };
  document.querySelectorAll('input[name="theme-color"]').forEach(input => { input.checked = input.value === p.color; });
  $('night-mode').checked = p.dark;
  $('night-mode-description').textContent = p.dark ? '已开启' : '已关闭';
  $('theme-options').disabled = settingsSaving;
  $('night-mode').disabled = settingsSaving;
  if ($('native-input')) {
    $('native-input').checked = p.nativeInput;
    $('native-input').disabled = settingsSaving;
    $('native-input-description').textContent = p.nativeInput ? '已开启 · 使用系统输入框' : '键盘自动收起时可开启';
  }
  $('settings-message-fields').disabled = settingsSaving;
  $('choose-background').disabled = settingsSaving;
  $('clear-background').disabled = settingsSaving || !p.backgroundUrl;
  const brightness = $('background-brightness');
  $('background-brightness-control').hidden = !p.backgroundUrl;
  $('background-brightness-value').textContent = p.backgroundBrightness + '%';
  brightness.value = p.backgroundBrightness;
  brightness.disabled = settingsSaving || !p.backgroundUrl;
  Preferences.previewBrightness(p.backgroundBrightness);
}

async function saveDisplayPreferences(changes, statusId) {
  if (settingsSaving) return false;
  settingsSaving = true;
  pendingAppearance = changes;
  updateSettingsControls();
  const status = $(statusId);
  if (status) { status.textContent = '正在保存'; status.classList.remove('form-error'); }
  try {
    await Preferences.save(changes);
    if (status?.isConnected) { status.textContent = '已保存'; Motion.enter(status, 0, 0, 145); }
    return true;
  } catch (error) {
    if (status?.isConnected) { status.textContent = error.message; status.classList.add('form-error'); }
    else toast(error.message);
    return false;
  } finally { settingsSaving = false; pendingAppearance = null; updateSettingsControls(); }
}

function bindSettingsPage() {
  updateSettingsControls();
  document.querySelectorAll('input[name="theme-color"]').forEach(input => { input.onchange = () => { if (input.checked) saveDisplayPreferences({ color: input.value }, 'appearance-status'); }; });
  $('night-mode').onchange = event => saveDisplayPreferences({ dark: event.target.checked }, 'appearance-status');
  if ($('native-input')) $('native-input').onchange = event => saveDisplayPreferences({ nativeInput: event.target.checked }, 'appearance-status');
  $('choose-background').onclick = () => changeAppBackground(false);
  $('clear-background').onclick = () => changeAppBackground(true);
  const brightness = $('background-brightness');
  brightness.oninput = () => {
    const value = brightness.valueAsNumber;
    Preferences.previewBrightness(value);
    $('background-brightness-value').textContent = value + '%';
  };
  brightness.onchange = () => saveDisplayPreferences({ backgroundBrightness: brightness.valueAsNumber }, 'background-status');
  const form = $('settings-messages-form');
  form.oninput = () => { form.dataset.dirty = 'true'; $('messages-status').textContent = ''; };
  form.onsubmit = async event => {
    event.preventDefault();
    const success = await saveDisplayPreferences({ emptyDayMessage: $('empty-day-message').value, emptyTasksMessage: $('empty-tasks-message').value }, 'messages-status');
    if (success && form.isConnected) {
      const p = Preferences.current(); $('empty-day-message').value = p.emptyDayMessage; $('empty-tasks-message').value = p.emptyTasksMessage; form.dataset.dirty = 'false';
    }
  };
}
