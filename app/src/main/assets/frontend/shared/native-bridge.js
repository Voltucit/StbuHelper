'use strict';
const Native = window.Native = {
  serial: 0, pending: new Map(), android: !!window.StbuNative, ios: !!window.webkit?.messageHandlers?.stbu,
  call(method, args = {}) {
    if (this.ios) return window.webkit.messageHandlers.stbu.postMessage({ method, args });
    if (!this.android) return this.preview(method, args);
    return new Promise((resolve, reject) => {
      const id = ++this.serial;
      const timeout = ['editLocalText', 'lifeRecharge', 'chooseAppBackground'].includes(method) ? null : setTimeout(() => { this.pending.delete(id); reject(new Error('操作仍未确认完成，请先核对当前状态再重试')); }, method === 'lifeScanCode' ? 180000 : method.startsWith('life') ? 90000 : method === 'refreshHomeworkCourses' ? 45000 : 20000);
      this.pending.set(id, { resolve, reject, timeout });
      window.StbuNative.call(id, method, JSON.stringify(args));
    });
  },
  resolve(id, response) {
    const pending = this.pending.get(id); if (!pending) return;
    clearTimeout(pending.timeout); this.pending.delete(id);
    response.ok ? pending.resolve(response.data) : pending.reject(new Error(response.error));
  },
  async preview(method, args) {
    if (method === 'bootstrap') { const saved = JSON.parse(localStorage.getItem('stbu.preview') || 'null'); return { data: saved && !saved.demo ? saved : M.empty(), credential: { saved: false }, life: { connected: false } }; }
    if (method === 'saveAppPreferences') return { ...Preferences.current(), ...args };
    if (method === 'saveData') { localStorage.setItem('stbu.preview', JSON.stringify(M.validate(args.data))); return {}; }
    if (['saveCourse', 'deleteCourse', 'saveSettings', 'clearAcademicData'].includes(method)) {
      let latest = JSON.parse(localStorage.getItem('stbu.preview') || 'null') || M.empty();
      if (method === 'saveCourse') latest.courses = [...latest.courses.filter(c => c.id !== args.course.id), args.course];
      if (method === 'deleteCourse') latest.courses = latest.courses.filter(c => c.id !== args.id);
      if (method === 'saveSettings') latest.settings = { ...latest.settings, ...args.settings };
      if (method === 'clearAcademicData') latest = M.empty();
      localStorage.setItem('stbu.preview', JSON.stringify(M.validate(latest))); return latest;
    }
    if (method === 'importFile') { $('json-file').click(); return {}; }
    if (method === 'exportFile') {
      const exported = structuredClone(M.validate(data)); exported.tasks.forEach(task => delete task.url);
      const blob = new Blob([JSON.stringify(exported, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob), link = document.createElement('a'); link.href = url; link.download = 'StbuHelper.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); return {};
    }
    if (method === 'clearCredentials') return { saved: false };
    throw new Error('请在手机 App 中使用此功能，浏览器预览不会保存或发送密码');
  }
};
