(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  root.PortalParser = api;
})(typeof globalThis === 'undefined' ? this : globalThis, function () {
  'use strict';
  // Original parser. The historical AiSchedule STBU adapter informed the legacy table layout;
  // it is not proof that the current authenticated portal still uses those columns.
  function numbers(text, max) {
    const result = new Set();
    String(text).replace(/[，、]/g, ',').replace(/[~～至]/g, '-').replace(/[()（）单双周节第\s]/g, '').split(',').forEach(piece => {
      const match = /^(\d+)(?:-(\d+))?$/.exec(piece);
      if (!match) return;
      const a = +match[1], b = +(match[2] || match[1]);
      if (a < 1 || b > max || a > b) return;
      for (let n = a; n <= b; n++) if (!/单/.test(text) && !/双/.test(text) || /单/.test(text) && n % 2 || /双/.test(text) && n % 2 === 0) result.add(n);
    });
    return [...result].sort((a, b) => a - b);
  }
  function slots(text) {
    const pattern = /([\d \t,，、\-~～至()（）单双]+)周\s*(?:星期|周)([一二三四五六日天1-7])\s*第?([\d \t,，、\-~～至]+)节/g;
    return [...String(text).matchAll(pattern)].map(m => ({
      weeks: numbers(m[1], 60), day: /\d/.test(m[2]) ? +m[2] : '一二三四五六日'.indexOf(m[2].replace('天', '日')) + 1,
      sections: numbers(m[3], 16), end: m.index + m[0].length
    })).filter(x => x.weeks.length && x.sections.length && x.day);
  }
  const clean = value => String(value || '').replace(/\u00a0/g, ' ').replace(/[ \t]+/g, ' ').trim();
  function id(kind, item) { return kind + ':' + [item.name, item.day, item.sections, item.weeks, item.date, item.score, item.term].join('|'); }
  function parseRows(rows, kind) {
    const records = [];
    let headers = [];
    for (const raw of rows) {
      const cells = raw.map(clean);
      if (cells.some(x => /课程名称|课程名|活动名称|作业名称/.test(x)) && cells.some(x => /时间|成绩|学分|教师|任课|活动|作业/.test(x))) { headers = cells; continue; }
      const col = (pattern, fallback = -1) => { const at = headers.findIndex(x => pattern.test(x)); return cells[at < 0 ? fallback : at] || ''; };
      if (kind === 'courses') {
        const legacy = !headers.length && cells.length >= 11;
        const name = col(/课程名称|课程名/, legacy ? 4 : -1);
        const time = col(/上课时间|授课时间|时间地点|周次.*节次/, legacy ? 10 : -1);
        if (!name || !time) continue;
        for (const slot of slots(time)) {
          const tail = time.slice(slot.end).split(/\n/).map(clean).filter(Boolean)[0] || '';
          const room = col(/上课地点|教室|教学地点/) || (/周|节/.test(tail) ? '' : tail);
          const item = { name, teacher: col(/任课教师|授课教师|教师|老师/, legacy ? 7 : -1), room, day: slot.day, weeks: slot.weeks, sections: slot.sections, source: 'school' };
          records.push({ ...item, id: id(kind, item) });
        }
      } else if (kind === 'grades') {
        const name = col(/课程名称|课程名/), score = col(/总评成绩|最终成绩|课程成绩|^成绩$/);
        if (!name || !score) continue;
        const item = { name, score, credits: col(/^学分$/), term: col(/学年学期|开课学期|学期/), code: col(/课程代码|课程编号/), category: col(/课程性质|课程类别/), source: 'school' };
        records.push({ ...item, id: id(kind, item) });
      } else if (kind === 'exams') {
        const name = col(/课程名称|课程名/), when = col(/考试时间|考试日期|日期/);
        if (!name || !when) continue;
        const date = /\d{4}[-/]\d{1,2}[-/]\d{1,2}/.exec(when)?.[0]?.replace(/\//g, '-') || '';
        const item = { name, date, time: clean(when.replace(date, '')), room: col(/考试地点|考场|教室/), source: 'school' };
        records.push({ ...item, id: id(kind, item) });
      } else if (kind === 'tasks') {
        const name = col(/作业名称|活动名称/);
        if (!name) continue;
        const item = { name, deadline: col(/截止时间|截止日期/), status: col(/状态|完成情况/), source: 'school' };
        records.push({ ...item, id: id(kind, item) });
      }
    }
    return [...new Map(records.map(record => [record.id, record])).values()];
  }
  function extract(document, kind) {
    const documents = [document];
    for (const frame of document.querySelectorAll('iframe')) { try { if (frame.contentDocument) documents.push(frame.contentDocument); } catch (_) { /* cross-origin frames stay isolated */ } }
    const records = documents.flatMap(doc => [...doc.querySelectorAll('table')].flatMap(table => parseRows([...table.querySelectorAll('tr')].map(row => [...row.children].filter(cell => /^(TD|TH)$/.test(cell.tagName)).map(cell => cell.innerText || cell.textContent || '')), kind)));
    return { records: [...new Map(records.map(record => [record.id, record])).values()] };
  }
  return { numbers, slots, parseRows, extract };
});
