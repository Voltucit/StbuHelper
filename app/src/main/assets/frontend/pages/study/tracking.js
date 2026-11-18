'use strict';

function trackingSettings(draft = null) {
  if (!academic.learning) return openAccount('learning');
  if (tracking.unavailable) {
    sheet('课程追踪', '<p>暂时无法读取本机追踪设置，请稍后重试。</p><button class="secondary wide" id="retry-tracking">重试</button>', () => {
      const retry = $('retry-tracking'); retry.onclick = async () => { Motion.busy(retry, true); try { tracking = await Native.call('homeworkTracking'); if (retry.isConnected) trackingSettings(); } catch (error) { toast(error.message); } finally { Motion.busy(retry, false); } };
    });
    return;
  }
  const account = tracking.account;
  const hasDraft = !!draft && draft.account === account;
  const custom = hasDraft ? draft.custom : !!tracking.custom;
  const selected = new Set(hasDraft ? draft.selected : tracking.selected || []);
  const courses = [...(tracking.courses || [])].sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));
  sheet('课程追踪', `<p class="fine">选择需要读取作业的课程。未追踪课程不参与同步、待办或提醒；全部取消时停止读取作业。</p>
    <fieldset class="tracking-mode"><legend>追踪范围</legend><label><input type="radio" name="tracking-mode" value="all" ${!custom ? 'checked' : ''}>全部课程（包括新课程）</label><label><input type="radio" name="tracking-mode" value="selected" ${custom ? 'checked' : ''}>只追踪所选课程</label></fieldset>
    <div class="section-row tracking-tools"><button class="text-button" type="button" id="select-tracking">全选列表</button><button class="text-button" type="button" id="clear-tracking">清空</button><button class="text-button" type="button" id="refresh-tracking">刷新课程列表</button></div>
    <div id="tracking-list">${courses.length ? courses.map(course => `<label class="tracking-row"><input type="checkbox" data-tracking-course="${esc(course.id)}" ${!custom || selected.has(course.id) ? 'checked' : ''} ${!custom ? 'disabled' : ''}><span><b class="tracking-course-name">${esc(course.name)}</b><small class="tracking-teacher">${course.teacher ? '授课教师：' + esc(course.teacher) : '教师未提供'}</small></span></label>`).join('') : '<p class="fine">课程列表为空，可刷新获取。</p>'}</div>
    <p class="form-error" id="tracking-error" role="alert"></p><button class="primary wide" id="save-tracking">保存并同步作业</button>`, () => {
    const errorBox = $('tracking-error'), refresh = $('refresh-tracking'), save = $('save-tracking');
    const radios = [...$('sheet-body').querySelectorAll('input[name="tracking-mode"]')];
    const boxes = [...$('tracking-list').querySelectorAll('[data-tracking-course]')];
    const readDraft = () => ({ account, custom: radios.find(radio => radio.checked)?.value === 'selected', selected: boxes.filter(box => box.checked).map(box => box.dataset.trackingCourse) });
    const setMode = value => { radios.forEach(radio => radio.checked = radio.value === value); boxes.forEach(box => { box.disabled = value === 'all'; if (value === 'all') box.checked = true; }); };
    radios.forEach(radio => radio.onchange = () => setMode(radio.value));
    $('select-tracking').onclick = () => { setMode('selected'); boxes.forEach(box => box.checked = true); };
    $('clear-tracking').onclick = () => { setMode('selected'); boxes.forEach(box => box.checked = false); };
    refresh.onclick = async () => {
      Motion.busy(refresh, true); save.disabled = true;
      try {
        const fresh = await Native.call('refreshHomeworkCourses');
        if (refresh.isConnected) { const currentDraft = readDraft(); tracking = fresh; trackingSettings(currentDraft); toast('同步成功'); }
      } catch (error) { if (errorBox.isConnected) errorBox.textContent = error.message; else toast(error.message); }
      finally { Motion.busy(refresh, false); save.disabled = false; }
    };
    save.onclick = async () => {
      Motion.busy(save, true); refresh.disabled = true;
      try {
        tracking = await Native.call('saveHomeworkTracking', readDraft());
        if (save.isConnected) closeSheet(); render();
        syncState = await Native.call('academicState'); manualSyncId = syncState.id || ''; showSyncState(); pollSync();
      } catch (error) { if (errorBox.isConnected) errorBox.textContent = error.message; else toast(error.message); }
      finally { Motion.busy(save, false); refresh.disabled = false; }
    };
    if ((!courses.length || !tracking.teacherMetadataRead) && !draft) refresh.click();
  });
}
