'use strict';

// This file loads before CSS to restore appearance without a light flash. It stores no account data.
const Preferences = (() => {
  const brightnessRange = { min: 25, max: 150, step: 5 };
  const defaults = { color: 'blue', dark: false, nativeInput: false, backgroundUrl: '', backgroundBrightness: 100, emptyDayMessage: '今天没有课程哦，可以好好休息', emptyTasksMessage: '好厉害，作业全部都做完了！' };
  const colors = [
    { id: 'blue', name: '晴空蓝', agenda: true },
    { id: 'mist', name: '雨雾蓝', agenda: true },
    { id: 'indigo', name: '暮蓝紫', agenda: true },
    { id: 'teal', name: '海湾青', agenda: true },
    { id: 'cobalt', name: '钴蓝', agenda: true },
    { id: 'deepsea', name: '墨海蓝', agenda: true },
    { id: 'jade', name: '松石绿', agenda: true },
    { id: 'berry', name: '莓紫', agenda: true },
    { id: 'green', name: '绿色' }, { id: 'purple', name: '紫色' },
    { id: 'orange', name: '橙色' }, { id: 'rose', name: '玫红' }
  ];
  let current = { ...defaults };
  function backgroundUrl(value) {
    try {
      const url = new URL(value);
      const validPath = /^\/background\/background-[a-f0-9-]+\.(jpg|png|webp)$/.test(url.pathname);
      const iosLocal = location.protocol === 'stbu-app:' && url.protocol === 'stbu-app:' && url.host === 'local';
      if (!validPath || url.username || url.password || url.search || url.hash) return '';
      if (iosLocal) return 'stbu-app://local' + url.pathname;
      return url.origin !== 'null' && url.origin === location.origin ? url.origin + url.pathname : '';
    } catch (_) { return ''; }
  }
  function normalize(value = {}) {
    return {
      color: colors.some(color => color.id === value.color) ? value.color : defaults.color,
      dark: value.dark === true,
      nativeInput: value.nativeInput === true,
      backgroundUrl: backgroundUrl(value.backgroundUrl),
      backgroundBrightness: normalizeBrightness(value.backgroundBrightness),
      emptyDayMessage: typeof value.emptyDayMessage === 'string' && value.emptyDayMessage.trim() ? value.emptyDayMessage.trim().slice(0, 200) : defaults.emptyDayMessage,
      emptyTasksMessage: typeof value.emptyTasksMessage === 'string' && value.emptyTasksMessage.trim() ? value.emptyTasksMessage.trim().slice(0, 200) : defaults.emptyTasksMessage
    };
  }
  function normalizeBrightness(value) {
    if (!Number.isFinite(value)) return defaults.backgroundBrightness;
    return Math.min(brightnessRange.max, Math.max(brightnessRange.min, Math.round(value)));
  }
  function previewBrightness(value) {
    document.documentElement.style.setProperty('--app-background-brightness', String(normalizeBrightness(value) / 100));
  }
  function apply(value) {
    current = normalize(value);
    document.documentElement.dataset.accent = current.color;
    document.documentElement.dataset.mode = current.dark ? 'dark' : 'light';
    document.documentElement.dataset.background = current.backgroundUrl ? 'image' : 'none';
    previewBrightness(current.backgroundBrightness);
    document.documentElement.style.setProperty('--app-background', current.backgroundUrl ? `url("${current.backgroundUrl}")` : 'none');
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.content = current.dark ? '#151b18' : '#f7f8f5';
    try { localStorage.setItem('stbu.preferences.v1', JSON.stringify(current)); } catch (_) { /* Native preferences remain authoritative. */ }
    return { ...current };
  }
  try { current = normalize(JSON.parse(localStorage.getItem('stbu.preferences.v1') || '{}')); } catch (_) {}
  apply(current);
  return {
    colors, defaults, brightnessRange, previewBrightness, current: () => ({ ...current }), apply,
    async save(changes) { return apply(await Native.call('saveAppPreferences', changes)); }
  };
})();
