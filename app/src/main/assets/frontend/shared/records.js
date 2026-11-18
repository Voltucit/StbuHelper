'use strict';

function recordRows(records, kind) {
  if (kind === 'tasks') return records.map(item => `<button class="menu-item task-link" data-task="${esc(item.id)}"><span>${esc(item.name)}<small>${esc([item.course, item.deadline, item.status].filter(Boolean).join(' · '))}</small></span><span class="arrow" aria-hidden="true">›</span></button>`).join('');
  return records.map(item => `<article class="record-row"><div class="name-wrap"><h3>${esc(item.name)}</h3><p>${esc(kind === 'grades' ? [item.term, item.credits ? item.credits + ' 学分' : '', item.category].filter(Boolean).join(' · ') : [item.date, item.time, item.room].filter(Boolean).join(' · '))}</p></div>${kind === 'grades' ? `<strong class="record-value">${esc(item.score)}</strong>` : ''}</article>`).join('');
}
