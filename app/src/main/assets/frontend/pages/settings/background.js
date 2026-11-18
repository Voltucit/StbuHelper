'use strict';

async function changeAppBackground(reset = false) {
  if (settingsSaving) return;
  settingsSaving = true; updateSettingsControls();
  const status = $('background-status');
  if (status) status.textContent = reset ? '正在恢复默认背景' : '请选择本机图片';
  try {
    const result = await Native.call(reset ? 'clearAppBackground' : 'chooseAppBackground');
    if (!result.cancelled) Preferences.apply(result.preferences);
    if (status?.isConnected) {
      status.textContent = result.cancelled ? '未更改背景' : reset ? '已恢复默认背景' : '背景已保存到本机';
      Motion.enter(status, 0, 0, 145);
    }
  } catch (error) { if (status?.isConnected) status.textContent = error.message; else toast(error.message); }
  finally { settingsSaving = false; updateSettingsControls(); }
}
