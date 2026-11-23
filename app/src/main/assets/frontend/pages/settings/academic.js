'use strict';

function academicSettings() {
  const s = data.settings;
  sheet('学期与作息', `<form id="settings-form"><label for="semester-start">本学期开学日期</label><input id="semester-start" type="date" required value="${esc(s.semesterStart || '')}"><label for="required-credits">毕业所需学分</label><input id="required-credits" type="number" min="0" max="500" step="0.5" value="${esc(s.requiredCredits || '')}" placeholder="按你的培养方案填写"><div class="form-grid"><div><label for="summer-from">夏季作息开始（月-日）</label><input id="summer-from" required pattern="[0-9]{2}-[0-9]{2}" value="${esc(s.summerFrom || '05-01')}"></div><div><label for="summer-to">夏季作息结束（月-日）</label><input id="summer-to" required pattern="[0-9]{2}-[0-9]{2}" value="${esc(s.summerTo || '09-30')}"></div></div><p class="fine">日期为可调整的初始设置，请按学校通知核对。</p><details><summary>设置冬夏上课时间</summary><p class="fine">每行一节，例如 08:30-09:15。留空时使用教务系统的节次时间。</p><label for="winter-times">冬季作息</label><textarea id="winter-times" placeholder="08:30-09:15&#10;09:25-10:10">${esc(s.winterTimes || '')}</textarea><label for="summer-times">夏季作息</label><textarea id="summer-times" placeholder="08:30-09:15&#10;09:25-10:10">${esc(s.summerTimes || '')}</textarea></details><p class="form-error" id="settings-error" role="alert"></p><button class="primary wide">保存设置</button></form>`, () => {
    $('settings-form').onsubmit = async event => { event.preventDefault(); try {
      const next = { semesterStart: $('semester-start').value, requiredCredits: +$('required-credits').value || 0, summerFrom: $('summer-from').value, summerTo: $('summer-to').value, winterTimes: $('winter-times').value.trim(), summerTimes: $('summer-times').value.trim() };
      if (!M.dateOnly(next.semesterStart)) throw new Error('开学日期不正确');
      if (![next.summerFrom, next.summerTo].every(x => M.dateOnly('2024-' + x))) throw new Error('请填写有效的月-日日期');
      M.periodTimes({ ...next, summerFrom: '01-01', summerTo: '12-31' }); M.periodTimes({ ...next, summerFrom: '12-31', summerTo: '12-31' }, new Date(2026, 5, 1));
      data = M.validate(await Native.call('saveSettings', { settings: { ...next, customTimetable: true } }));
      week = Math.max(1, Math.min(60, M.currentWeek(next.semesterStart) || 1)); render(); closeSheet(); toast('设置已保存');
    } catch (error) { $('settings-error').textContent = error.message; } };
  });
}
