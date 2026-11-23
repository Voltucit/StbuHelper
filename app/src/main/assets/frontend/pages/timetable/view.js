'use strict';

function weekView() {
  const courses = data.courses.filter(c => c.weeks.includes(week));
  let periods = []; try { periods = M.periodTimes(data.settings, weekDate()); } catch (_) {}
  const last = Math.min(16, Math.max(periods.length, 12, ...courses.flatMap(c => c.sections)));
  const weekday = new Date().getDay() || 7, highlightToday = week === M.currentWeek(data.settings.semesterStart), skip = new Set();
  let table = '<table class="timetable"><thead><tr><th>节次 / 时间</th>' + ['一', '二', '三', '四', '五', '六', '日'].map((d, i) => `<th ${highlightToday && i + 1 === weekday ? 'class="today-col"' : ''}>周${d}${data.settings.semesterStart ? `<small>${weekDate(i + 1).getMonth() + 1}/${weekDate(i + 1).getDate()}</small>` : ''}</th>`).join('') + '</tr></thead><tbody>';
  const cellCourses = (day, section) => courses.filter(c => c.day === day && c.sections.includes(section));
  const key = records => records.map(c => c.id).sort().join('|');
  for (let section = 1; section <= last; section++) {
    const period = periods[section - 1];
    table += `<tr><td class="period"><b>${section}</b>${period ? `<time>${esc(period.start)}<br>${esc(period.end)}</time>` : ''}</td>`;
    for (let day = 1; day <= 7; day++) {
      if (skip.has(`${day}:${section}`)) continue;
      const records = cellCourses(day, section);
      let span = 1;
      if (records.length) while (section + span <= last && key(cellCourses(day, section + span)) === key(records)) { skip.add(`${day}:${section + span}`); span++; }
      table += `<td rowspan="${span}" ${highlightToday && day === weekday ? 'class="today-col"' : ''}>${records.length ? `<button class="tone-${Math.abs(data.courses.indexOf(records[0])) % 4}" data-course="${esc(records[0].id)}">${records.map(c => `${esc(c.name)}<time>${esc(timeLabel({ ...c, sections: Array.from({ length: span }, (_, offset) => section + offset) }, weekDate(day)))}</time>`).join('<br>')}<span>${esc(records.length > 1 ? '时间重叠，请核对' : records[0].room || '')}</span></button>` : ''}</td>`;
    }
    table += '</tr>';
  }
  table += '</tbody></table>';
  const legacy = !data.settings.schoolTerm && data.courses.some(c => c.source !== 'manual');
  let note = '';
  if (legacy) note = '<p class="fine">当前显示旧版缓存课表。<button class="text-button" data-action="import-courses">同步教务课表</button></p>';
  else if (data.settings.schoolTerm && !data.settings.semesterStart) note = '<p class="fine">教务系统暂未提供教学周。<button class="text-button" data-action="settings">设置开学日期</button></p>';
  let message = '';
  if (!courses.length) {
    if (data.settings.schoolTerm || data.courses.length) message = empty('这一周没有课程', '');
    else if (academic.school) message = empty('教务课表待同步', syncState.scheduleIssue || '', 'import-courses', '同步课表');
    else message = empty('还没有课表', '连接学校教务账号后同步个人课表。', 'open-school', '连接学校教务账号');
  }
  return `<div class="week-toolbar"><button class="icon-button" data-action="prev-week" aria-label="上一周">‹</button><select id="week-select" aria-label="选择教学周">${Array.from({ length: 60 }, (_, i) => `<option value="${i + 1}" ${i + 1 === week ? 'selected' : ''}>第 ${i + 1} 周</option>`).join('')}</select><button class="icon-button" data-action="next-week" aria-label="下一周">›</button><button class="secondary current-week" data-action="current-week">回到本周</button></div><div class="table-scroll" tabindex="0" role="region" aria-label="整周课表，可横向滚动">${table}</div><div class="section-row"><p class="fine">${courses.length} 条课程安排 · 点击课程查看详情</p><button class="text-button" data-action="add-course">添加课程</button></div>${note}${message}${!periods.length ? '<p class="fine">尚无上课时间，可在“我的 → 设置 → 学期与作息”中设置。</p>' : ''}${footer()}`;
}
