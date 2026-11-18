'use strict';

const Motion = (() => {
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
  const active = new WeakMap();
  const ease = 'cubic-bezier(.16, 1, .3, 1)';

  function stop(element) {
    active.get(element)?.cancel();
    active.delete(element);
  }

  function play(element, frames, duration) {
    if (!element) return;
    stop(element);
    if (!element.animate || document.hidden || reduced.matches) return;
    const animation = element.animate(frames, { duration, easing: ease });
    if (!animation.finished) { animation.cancel(); return; }
    active.set(element, animation);
    const clean = () => { if (active.get(element) === animation) active.delete(element); };
    animation.finished.then(clean, clean);
    return animation;
  }

  function enter(element, x = 0, y = 5, duration = 180) {
    return play(element, [
      { opacity: .66, transform: 'translate(' + x + 'px, ' + y + 'px)' },
      { opacity: 1, transform: 'none' }
    ], duration);
  }

  function leave(element, y = 8, duration = 130) {
    return play(element, [
      { opacity: 1, transform: 'none' },
      { opacity: 0, transform: 'translateY(' + y + 'px)' }
    ], duration);
  }

  function busy(button, pending) {
    if (!button) return;
    button.disabled = pending;
    button.toggleAttribute('data-pending', pending);
    if (pending) button.setAttribute('aria-busy', 'true');
    else button.removeAttribute('aria-busy');
  }

  return { enter, leave, stop, busy };
})();
