'use strict';

// Only a pull that starts at the document's top can own the vertical gesture.
const PullRefresh = (() => {
  const threshold = 76;
  let gesture = null, busy = false, pulling = false, suppressClick = false;
  const indicator = () => $('refresh-indicator');
  const top = () => (document.scrollingElement?.scrollTop || 0) <= 1;
  function available() {
    return booted && route !== 'settings' && !$('sheet').open && !InputCompatibility.active() && !document.hidden;
  }
  function label(text) { if ($('refresh-label').textContent !== text) $('refresh-label').textContent = text; }
  function reset() {
    gesture = null; pulling = false;
    indicator().classList.remove('is-pulling', 'is-ready');
    indicator().style.removeProperty('--pull-offset');
    if (!busy) { indicator().hidden = true; label('下拉刷新'); }
  }
  function setBusy(value) {
    busy = !!value;
    indicator().classList.toggle('is-running', busy);
    indicator().setAttribute('aria-busy', String(busy));
    if (busy) { reset(); indicator().hidden = false; label('正在同步'); }
    else if (!pulling) reset();
  }
  document.addEventListener('touchstart', event => {
    if (gesture) reset();
    if (busy || !available() || !top() || event.touches.length !== 1) return;
    if (event.target.closest('dialog,input,textarea,select,[contenteditable],.bottom-nav')) return;
    const touch = event.touches[0];
    gesture = { x: touch.clientX, y: touch.clientY, distance: 0, route };
  }, { passive: true });
  document.addEventListener('touchmove', event => {
    if (!gesture) return;
    if (busy || !available() || gesture.route !== route || event.touches.length !== 1 || !top()) { reset(); return; }
    const touch = event.touches[0], dy = touch.clientY - gesture.y, dx = Math.abs(touch.clientX - gesture.x);
    if (!pulling && (dx > 12 && dx > Math.abs(dy) || dy < -8)) { reset(); return; }
    if (dy <= 10 || dx > dy) { if (pulling) { event.preventDefault(); gesture.distance = 0; indicator().classList.remove('is-ready'); label('下拉刷新'); } return; }
    event.preventDefault();
    pulling = true; gesture.distance = dy;
    indicator().hidden = false;
    indicator().classList.add('is-pulling');
    indicator().classList.toggle('is-ready', dy >= threshold);
    indicator().style.setProperty('--pull-offset', Math.min(18, -62 + dy * 0.65) + 'px');
    label(dy >= threshold ? '松开立即同步' : '下拉刷新');
  }, { passive: false });
  document.addEventListener('touchend', () => {
    const trigger = pulling && gesture?.distance >= threshold && available() && !busy;
    if (pulling) { suppressClick = true; setTimeout(() => { suppressClick = false; }, 400); }
    reset();
    if (trigger) syncNow().catch(error => toast(error.message));
  }, { passive: true });
  document.addEventListener('touchcancel', reset, { passive: true });
  document.addEventListener('click', event => { if (suppressClick) { event.preventDefault(); event.stopImmediatePropagation(); suppressClick = false; } }, true);
  document.addEventListener('visibilitychange', () => { if (document.hidden) reset(); });
  return { setBusy };
})();
