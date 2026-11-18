'use strict';

const InputCompatibility = (() => {
  let activeField = null;
  const kinds = new Set(['text', 'password', 'tel', 'number', 'email', 'search', 'url']);
  function editable(target) {
    const field = target instanceof Element ? target.closest('input,textarea') || target.closest('label[for]')?.control : null;
    return field && !field.disabled && !field.readOnly && !field.closest('fieldset:disabled') && (field.tagName === 'TEXTAREA' || kinds.has(field.type)) ? field : null;
  }
  function enabled() { return Native.android && Preferences.current().nativeInput; }
  async function edit(field) {
    if (activeField) return;
    activeField = field;
    field.blur();
    const label = field.labels?.[0]?.textContent?.trim() || field.getAttribute('aria-label') || field.placeholder || '输入内容';
    try {
      const result = await Native.call('editLocalText', {
        title: label.slice(0, 80), value: field.value, type: field.tagName === 'TEXTAREA' ? 'text' : field.type,
        multiline: field.tagName === 'TEXTAREA', maxLength: field.maxLength > 0 ? field.maxLength : 8192,
        inputMode: field.inputMode || '', placeholder: field.placeholder || ''
      });
      if (!result.cancelled && field.isConnected) {
        field.value = result.value;
        field.dispatchEvent(new Event('input', { bubbles: true }));
        field.dispatchEvent(new Event('change', { bubbles: true }));
      }
    } catch (error) { toast(error.message); }
    finally { activeField = null; if (renderPending) render(); maybeShowReceipt(); }
  }
  function onPointer(event) {
    if (!enabled()) return;
    const field = editable(event.target);
    if (!field) return;
    event.preventDefault(); event.stopPropagation(); edit(field);
  }
  document.addEventListener('pointerdown', onPointer, true);
  // Handles accessibility clicks that do not have a preceding pointer event.
  document.addEventListener('click', onPointer, true);
  document.addEventListener('focusin', event => {
    if (!enabled()) return;
    const field = editable(event.target);
    if (field) field.blur(); // A dialog's automatic focus must not reopen the WebView IME.
  }, true);
  return { active: () => activeField !== null };
})();
