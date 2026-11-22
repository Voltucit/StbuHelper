'use strict';

const studyTabs = [['exams', '考试'], ['grades', '成绩'], ['tasks', '待办'], ['credits', '学分']];

function studyView() {
  let html = '';
  if (studyTab === 'credits') {
    const summary = M.creditSummary(data.grades), required = Number(data.settings.requiredCredits) || 0;
    html += `<div class="progress-copy"><span>已获得学分</span><strong>${summary.credits.toFixed(1)}${required ? `<span class="fine"> / ${required}</span>` : ''}</strong></div>${required ? `<div class="progress-track"><div style="width:${Math.min(100, summary.credits / required * 100)}%"></div></div><p class="fine">距离设置的毕业学分还差 ${Math.max(0, required - summary.credits).toFixed(1)} 学分</p>` : '<p class="fine">在学期设置中填写毕业所需学分，查看进度。</p>'}<p class="fine stats-note">按已同步的通过成绩汇总，同一课程只统计一次。是否满足培养方案，请以学校审核为准。</p><button class="secondary" data-action="settings">学期与学分设置</button>`;
  } else {
    const records = studyTab === 'tasks' ? visibleTasks() : data[studyTab];
    html += `<div class="section-row study-tools"><span class="fine">${records.length} 条记录</span>${studyTab === 'tasks' ? '<button class="text-button" data-action="tracking">课程追踪</button><button class="text-button" data-action="reminders">作业提醒</button>' : ''}</div>`;
    if (studyTab === 'tasks' && academic.learning) html += `<p class="fine">${tracking.unavailable ? '课程追踪设置暂时无法读取' : tracking.custom ? '已选择追踪 ' + (tracking.selected || []).length + ' 门课程' : '追踪全部课程'}</p>`;
    if (studyTab === 'grades') html += '<input class="search-input" id="grade-search" type="search" placeholder="搜索课程名称" aria-label="搜索成绩">';
    const connected = academic[studyTab === 'tasks' ? 'learning' : 'school'];
    const needsVerification = studyTab === 'tasks' && academic.learningVerificationRequired;
    const incompleteHomework = studyTab === 'tasks' && connected && syncState.homeworkComplete === false;
    const homeworkIssue = syncState.homeworkIssue || '部分课程或截止时间暂未读取。';
    if (needsVerification) html += empty('学习通要求人工验证', '请在官方页面完成验证码，作业会继续保留在本机。', 'official-learning', '打开官方验证页');
    if (records.length) {
      if (incompleteHomework && !needsVerification) html += `<p class="fine" role="status">${esc(homeworkIssue)}</p>`;
      html += `<div id="records">${recordRows(records, studyTab)}</div>`;
    }
    else if (needsVerification) return html + footer();
    else if (studyTab === 'tasks' && connected && tracking.custom && !tracking.unavailable && !tracking.selected?.length) {
      html += empty('尚未选择追踪课程', '选择课程后即可同步这些课程的作业。', 'tracking', '选择课程');
    } else if (incompleteHomework) {
      html += empty('作业同步未完成', homeworkIssue + ' 已保留未更新的本机记录。', 'import-tasks', '重新同步');
    } else {
      html += empty(studyTab === 'exams' ? '暂无考试安排' : studyTab === 'grades' ? '暂无成绩记录' : '暂无待完成的作业', connected ? '' : studyTab === 'tasks' ? '连接学习通后同步未交作业。' : '连接学校教务账号后同步。', connected ? '' : `import-${studyTab}`, '连接账号');
    }
  }
  return html + footer();
}
