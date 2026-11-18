(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  root.CampusModel = api;
})(typeof globalThis === 'undefined' ? this : globalThis, function () {
  'use strict';
  const empty = () => ({ schemaVersion: 1, settings: {}, courses: [], grades: [], exams: [], tasks: [], updatedAt: '', demo: false });
  function range(text, maximum = 60) {
    const result = new Set();
    String(text).replace(/[，、]/g, ',').replace(/[~～—至]/g, '-').replace(/[()（）单双周节第\s]/g, '').split(',').forEach(part => {
      const match = /^(\d+)(?:-(\d+))?$/.exec(part);
      if (!match) throw new Error('请使用 1-16 或 1,3,5 这样的周次和节次');
      const from = Number(match[1]), to = Number(match[2] || match[1]);
      if (from < 1 || to > maximum || to < from) throw new Error('周次或节次超出范围');
      for (let n = from; n <= to; n++) result.add(n);
    });
    let values = [...result].sort((a, b) => a - b);
    if (/单/.test(text)) values = values.filter(n => n % 2 === 1);
    if (/双/.test(text)) values = values.filter(n => n % 2 === 0);
    if (!values.length) throw new Error('周次或节次不能为空');
    return values;
  }
  function dateOnly(value) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value || '')) return null;
    const date = new Date(value + 'T12:00:00');
    return Number.isFinite(+date) && date.getFullYear() === +value.slice(0, 4) && date.getMonth() + 1 === +value.slice(5, 7) && date.getDate() === +value.slice(8, 10) ? date : null;
  }
  function currentWeek(start, today = new Date()) {
    const first = dateOnly(start);
    if (!first) return null;
    first.setDate(first.getDate() - ((first.getDay() + 6) % 7));
    const now = new Date(today.getFullYear(), today.getMonth(), today.getDate(), 12);
    return Math.floor((now - first) / 604800000) + 1;
  }
  function validate(data) {
    if (data?.demo) throw new Error('文件不包含实际学习数据');
    if (!data || data.schemaVersion !== 1 || !data.settings || typeof data.settings !== 'object' || Array.isArray(data.settings)) throw new Error('请选择 StbuHelper 导出的 JSON 文件');
    for (const kind of ['courses', 'grades', 'exams', 'tasks']) {
      if (!Array.isArray(data[kind]) || data[kind].length > 3000) throw new Error('文件记录格式不正确');
      for (const item of data[kind]) {
        if (!item || typeof item.name !== 'string' || !item.name.trim() || item.name.length > 500) throw new Error('记录缺少名称');
        if (kind === 'courses') {
          if (!Number.isInteger(item.day) || item.day < 1 || item.day > 7) throw new Error('课程星期超出范围');
          for (const [field, max] of [['weeks', 60], ['sections', 16]]) {
            if (!Array.isArray(item[field]) || !item[field].length || item[field].length > max || item[field].some(n => !Number.isInteger(n) || n < 1 || n > max)) throw new Error('课程周次或节次不正确');
          }
        }
      }
    }
    // Export only academic data and settings; credentials are never part of this format.
    return { schemaVersion: 1, settings: data.settings, courses: data.courses, grades: data.grades, exams: data.exams, tasks: data.tasks, updatedAt: String(data.updatedAt || ''), demo: false };
  }
  function season(settings, date = new Date()) {
    const mmdd = String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0');
    const from = settings.summerFrom || '05-01', to = settings.summerTo || '09-30';
    return (from <= to ? mmdd >= from && mmdd <= to : mmdd >= from || mmdd <= to) ? 'summer' : 'winter';
  }
  function periodTimes(settings, date = new Date()) {
    const raw = settings[season(settings, date) + 'Times'] || settings.officialTimes;
    if (!raw) return [];
    return raw.split(/[\n,，]+/).map(line => line.trim()).filter(Boolean).map((line, index) => {
      if (!/^([01]\d|2[0-3]):[0-5]\d-([01]\d|2[0-3]):[0-5]\d$/.test(line) || line.slice(0, 5) >= line.slice(6)) throw new Error('作息请每行填写 08:30-09:15 这样的时间');
      return { section: index + 1, start: line.slice(0, 5), end: line.slice(6) };
    });
  }
  function atDate(courses, settings, date = new Date()) {
    const week = currentWeek(settings.semesterStart, date), day = date.getDay() || 7;
    if (!week) return [];
    return courses.filter(c => c.day === day && c.weeks.includes(week)).sort((a, b) => a.sections[0] - b.sections[0]);
  }
  function creditSummary(grades) {
    const passed = new Map();
    grades.forEach(g => {
      const score = String(g.score || '').trim();
      const number = /^\d+(\.\d+)?$/.test(score) ? Number(score) : NaN;
      const ok = Number.isFinite(number) ? number >= 60 : ['及格', '合格', '通过', '优秀', '良好', '中等'].includes(score);
      const credits = Number(g.credits);
      if (ok && Number.isFinite(credits) && credits > 0) passed.set(g.code || g.name, Math.max(passed.get(g.code || g.name) || 0, credits));
    });
    return { credits: [...passed.values()].reduce((a, b) => a + b, 0), count: passed.size };
  }
  const isTaskPending = status => !/已完成|已提交|已交|待批|已批|过期|逾期|已截止|已过截止|已结束|已作答|截止时间未提供|截止时间未知|无截止时间/.test(status || '') && /未交|未提交|未完成|待完成|待提交|未作答|未开始|进行中|待做/.test(status || '');
  const actionableTask = (task, now = Date.now()) => isTaskPending(task.status) && Number.isFinite(Number(task.deadlineAt)) && Number(task.deadlineAt) > now;
  const pendingTasks = (tasks, now = Date.now()) => tasks.filter(task => actionableTask(task, now)).sort((a, b) => a.deadlineAt - b.deadlineAt);
  return { empty, range, currentWeek, validate, season, periodTimes, atDate, creditSummary, dateOnly, isTaskPending, actionableTask, pendingTasks };
});
