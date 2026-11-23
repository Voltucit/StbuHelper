'use strict';

const agendaClock = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
const agendaDate = new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric', weekday: 'short' });

function agendaTime(date, clock) {
  const [hours, minutes] = clock.split(':').map(Number);
  const time = new Date(date);
  return time.setHours(hours, minutes, 0, 0);
}

function agendaCourses(now) {
  let periods = [];
  try { periods = M.periodTimes(data.settings, now); } catch (_) { /* Keep courses visible even without valid clock times. */ }

  return M.atDate(data.courses, data.settings, now).map(course => {
    const first = course.sections[0];
    const last = course.sections.at(-1);
    const start = periods[first - 1]?.start;
    const end = periods[last - 1]?.end;
    return {
      id: course.id, name: course.name, action: 'course',
      at: start ? agendaTime(now, start) : null,
      endAt: end ? agendaTime(now, end) : null,
      time: start || (first === last ? `第 ${first} 节` : `第 ${first}–${last} 节`),
      detail: [course.room || '地点未提供', course.teacher, end && `${end} 下课`].filter(Boolean).join(' · ')
    };
  });
}

function agendaRow(entry) {
  const type = entry.action === 'task' ? ' agenda-item-due' : '';
  const label = entry.at === null ? esc(entry.time) : `<time datetime="${new Date(entry.at).toISOString()}">${esc(entry.time)}</time>`;
  return `<li class="agenda-item${type}${entry.status === '下一节' ? ' agenda-item-next' : ''}">
    <button class="agenda-action" type="button" data-${entry.action}="${esc(entry.id)}">
      <span class="agenda-clock">${label}</span>
      <span class="agenda-copy"><b>${esc(entry.name)}</b><small>${esc(entry.detail)}</small></span>
      ${entry.status ? `<span class="agenda-badge">${entry.status}</span>` : ''}
    </button>
  </li>`;
}

function upcomingHomework(rows, tomorrowEnd, now) {
  return rows.slice(0, 3).map(({ task, at, date }) => {
    const year = date.getFullYear() === now.getFullYear() ? '' : date.getFullYear() + '年';
    const day = at < tomorrowEnd ? '明天' : year + agendaDate.format(date);
    return `<button class="menu-item task-link agenda-future-task" type="button" data-task="${esc(task.id)}">
      <span>${esc(task.name)}${task.course ? `<small>${esc(task.course)}</small>` : ''}</span>
      <time datetime="${date.toISOString()}">${esc(day)}<small>${esc(agendaClock.format(date))} 截止</small></time>
    </button>`;
  }).join('');
}

function todayView() {
  const now = new Date(), nowAt = now.getTime();
  const todayEnd = new Date(now).setHours(24, 0, 0, 0);
  const tomorrowEnd = new Date(now).setHours(48, 0, 0, 0);
  const courses = agendaCourses(now);
  const tasks = visibleTasks(nowAt).map(task => {
    const at = Number(task.deadlineAt);
    return { task, at, date: new Date(at) };
  }).filter(row => Number.isFinite(row.date.getTime()));

  const nextClassAt = Math.min(Infinity, ...courses.filter(course => course.at !== null && course.at > nowAt).map(course => course.at));
  const timedCourses = courses.filter(course => course.at !== null);
  for (const course of timedCourses) {
    if (course.at <= nowAt && course.endAt !== null && nowAt < course.endAt) course.status = '上课中';
    else if (course.at === nextClassAt) course.status = '下一节';
  }
  const todayTasks = tasks.filter(row => row.at < todayEnd).map(({ task, at, date }) => ({
    id: task.id, name: task.name, action: 'task', at, time: agendaClock.format(date),
    detail: [task.course, '作业截止'].filter(Boolean).join(' · '), status: '未交'
  }));
  const timeline = [...timedCourses, ...todayTasks].sort((a, b) => a.at - b.at);
  const withoutTime = courses.filter(course => course.at === null);
  const upcoming = tasks.filter(row => row.at >= todayEnd);

  const needsSchool = !academic.school && !data.courses.length;
  const courseNote = !courses.length && data.courses.length && !M.dateOnly(data.settings.semesterStart)
    ? '<div class="agenda-note"><p>请先设置开学日期，才能显示今天的课程。</p><button class="text-button" data-action="settings">学期与作息</button></div>'
    : !courses.length ? `<p class="agenda-note">${esc(Preferences.current().emptyDayMessage)}</p>` : '';

  return `<div class="day-agenda">
    <section aria-labelledby="agenda-title">
      <div class="section-row"><h2 id="agenda-title">日程与截止</h2><button class="text-button" data-action="week">整周课表</button></div>
      ${needsSchool ? empty('还没有课表', '连接学校教务账号后同步课程。', 'open-school', '连接学校教务账号') : courseNote}
      ${timeline.length ? `<ol class="agenda-timeline">${timeline.map(agendaRow).join('')}</ol>` : ''}
      ${withoutTime.length ? `<div class="agenda-untimed"><h3>上课时间待设置</h3><ul>${withoutTime.map(agendaRow).join('')}</ul></div>` : ''}
    </section>
    <section aria-labelledby="agenda-upcoming">
      <div class="section-row"><h2 id="agenda-upcoming">接下来</h2><button class="text-button" data-action="tasks">全部待办</button></div>
      ${upcoming.length ? `<div class="menu-list agenda-future">${upcomingHomework(upcoming, tomorrowEnd, now)}</div>` :
        `<p class="agenda-note">${!academic.learning && !data.tasks.length ? '连接学习通后可查看待办。' : todayTasks.length ? '暂无之后截止的作业。' : esc(Preferences.current().emptyTasksMessage)}</p>`}
    </section>
  </div>`;
}
