'use strict';

function currentWeek() { return Math.max(1, Math.min(60, M.currentWeek(data.settings.semesterStart) || 1)); }

function weekDate(day = 1) {
  const first = M.dateOnly(data.settings.semesterStart);
  if (!first) return new Date();
  first.setDate(first.getDate() - ((first.getDay() + 6) % 7) + (week - 1) * 7 + day - 1);
  return first;
}

function courseTime(course, date = new Date()) {
  let times = []; try { times = M.periodTimes(data.settings, date); } catch (_) {}
  const first = times[course.sections[0] - 1], last = times[course.sections.at(-1) - 1];
  return first && last ? [first.start, last.end] : [`${course.sections[0]}-${course.sections.at(-1)}节`, ''];
}

function timeLabel(course, date) { const [start, end] = courseTime(course, date); return end ? `${start}–${end}` : start; }

function visibleTasks(now = Date.now()) {
  const selected = new Set(tracking.selected || []);
  return M.pendingTasks(data.tasks, now).filter(task => !tracking.custom || selected.has(task.groupId));
}
