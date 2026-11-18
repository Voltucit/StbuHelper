'use strict';

function courseEditor(id) {
  const course = data.courses.find(c => c.id === id);
  sheet(course ? '课程详情' : '添加课程', `<form id="course-form"><label for="course-name">课程名称</label><input id="course-name" required maxlength="150" value="${esc(course?.name || '')}" placeholder="例如：高等数学"><div class="form-grid"><div><label for="course-teacher">教师</label><input id="course-teacher" value="${esc(course?.teacher || '')}"></div><div><label for="course-room">教室</label><input id="course-room" value="${esc(course?.room || '')}"></div></div><div class="form-grid"><div><label for="course-day">星期</label><select id="course-day">${['一', '二', '三', '四', '五', '六', '日'].map((d, i) => `<option value="${i + 1}" ${course?.day === i + 1 ? 'selected' : ''}>星期${d}</option>`).join('')}</select></div><div><label for="course-sections">节次</label><input id="course-sections" required value="${esc(course?.sections.join(',') || '1-2')}" placeholder="例如：1-2"></div></div><label for="course-weeks">周次</label><input id="course-weeks" required value="${esc(course?.weeks.join(',') || '1-16')}" placeholder="例如：1-16 或 1-16单周"><p class="form-error" id="course-error" role="alert"></p><div class="form-actions">${course ? '<button class="danger" type="button" id="delete-course">删除课程</button>' : ''}<button class="primary" type="submit">保存课程</button></div></form>`, () => {
    $('course-form').onsubmit = async event => { event.preventDefault(); try {
      const item = { id: course?.id || 'manual:' + Date.now(), name: $('course-name').value.trim(), teacher: $('course-teacher').value.trim(), room: $('course-room').value.trim(), day: +$('course-day').value, sections: M.range($('course-sections').value, 16), weeks: M.range($('course-weeks').value), source: 'manual' };
      if (!item.name) throw new Error('请填写课程名称');
      data = M.validate(await Native.call('saveCourse', { course: item }));
      render(); closeSheet(); toast('课程已保存');
    } catch (error) { $('course-error').textContent = error.message; } };
    if (course) $('delete-course').onclick = () => confirmAction('删除这门课程？', '只删除本机这条课程记录，自动同步的课程下次同步时可能恢复。', async () => { data = M.validate(await Native.call('deleteCourse', { id: course.id })); render(); });
  });
}

function goCurrentWeek() {
  week = currentWeek(); route = 'week'; render(); window.scrollTo(0, 0);
  const table = document.querySelector('.table-scroll'); if (table) table.scrollLeft = 0;
}
