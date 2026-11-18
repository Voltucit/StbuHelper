'use strict';

document.addEventListener('click', async event => {
  const button = event.target.closest('[data-action],[data-route],[data-study],[data-course],[data-task],[data-device]'); if (!button) return;
  try { if (button.dataset.device) { await deviceDetail(button.dataset.device, button.dataset.kind); } else if (button.dataset.task) { const task = data.tasks.find(t => t.id === button.dataset.task); if (academic.learning && task?.url) await Native.call('openTask', { url: task.url }); else await openAccount('learning'); } else if (button.dataset.route) { await navigate(button.dataset.route); } else if (button.dataset.study) { studyTab = button.dataset.study; render(); } else if (button.dataset.course) courseEditor(button.dataset.course); else await action(button.dataset.action); } catch (error) { toast(error.message); }
});
$('close-sheet').innerHTML = icon('close'); $('close-sheet').onclick = closeSheet;
$('sheet').addEventListener('cancel', event => { event.preventDefault(); closeSheet(); });
$('sync-button').innerHTML = icon('sync'); $('sync-button').setAttribute('aria-label', '同步数据，也可在页面顶部下拉刷新'); $('sync-button').onclick = () => syncNow().catch(error => toast(error.message));
$('json-file').onchange = async event => { try { const file = event.target.files[0]; if (!file) return; if (file.size > 2 * 1024 * 1024) throw new Error('文件超过 2 MB'); const incoming = M.validate(JSON.parse(await file.text())); await Native.call('saveData', { data: incoming }); data = incoming; render(); toast('数据已导入'); } catch (error) { toast(error.message); } finally { event.target.value = ''; } };
window.onNativeBack = () => {
  if (receiptConfirmationRequired) return true;
  if ($('sheet').open) { closeSheet(); return true; }
  if (route === 'today') return false;
  route = route === 'water' ? 'life' : route === 'settings' ? 'me' : 'today';
  render();
  window.scrollTo(0, 0);
  return true;
};
window.onNativeResume = async () => { try { const wasUsing = !!life.active; applyBoot(await Native.call('bootstrap')); const returned = await refreshAfterRecharge(); if (!returned && wasUsing && !life.active && life.connected) { try { await lifeRefresh(true); } catch (_) { lifeSnapshot = null; render(); } } } catch (error) { toast(error.message); } };
window.onNotificationRoute = page => {
  if (!['week', 'tasks'].includes(page) || receiptConfirmationRequired) return false;
  if ($('sheet').open) closeSheet();
  if (page === 'week') goCurrentWeek();
  else { route = 'study'; studyTab = 'tasks'; render(); window.scrollTo(0, 0); }
  return true;
};
(async () => { try { applyBoot(await Native.call('bootstrap')); week = currentWeek(); render(); await refreshAfterRecharge(); } catch (error) { $('page-content').textContent = error.message; } })();

document.addEventListener('visibilitychange', () => { if (document.hidden) { clearTimeout(contentRefresh); clearTimeout(syncPoll); } else if (booted) { render(); if (syncState.running) pollSync(); } });
