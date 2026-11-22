'use strict';

function reminderSettings() {
  sheet('作业提醒', `<p>${Native.ios ? '在截止前 24 小时、3 小时分别提醒一次。' : '同步发现新作业时提醒，并在截止前 24 小时、3 小时分别提醒一次。'}仅提醒截止时间明确、尚未过期的未交作业。</p><p class="fine">距上次作业同步满 12 小时后，打开 App 会自动刷新作业${Native.ios ? '。' : '，后台也按这个间隔补充检查。'}截止提醒使用本机缓存，可能受系统省电影响。</p><p>${reminders.enabled ? '作业提醒已开启' : '作业提醒未开启'}${reminders.enabled && !reminders.permission ? '，系统通知权限尚未允许' : ''}</p><button class="primary wide" id="toggle-reminders">${reminders.enabled ? '关闭作业提醒' : '开启作业提醒'}</button>${!reminders.permission ? '<button class="text-button wide" id="notification-settings">打开系统通知设置</button>' : ''}`, () => {
    const toggle = $('toggle-reminders');
    toggle.onclick = async () => {
      Motion.busy(toggle, true);
      try {
        reminders = await Native.call('configureReminders', { enabled: !reminders.enabled });
        if (toggle.isConnected) reminderSettings();
      } catch (error) { toast(error.message); }
      finally { Motion.busy(toggle, false); }
    };
    if ($('notification-settings')) $('notification-settings').onclick = () => Native.call('notificationSettings').catch(error => toast(error.message));
  });
}
