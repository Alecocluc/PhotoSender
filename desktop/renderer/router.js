import { state } from './state.js';

const _registry = {};
const _cleanup = {};

export function register(name, fn, cleanup) { _registry[name] = fn; _cleanup[name] = cleanup; }

export function navigate(view) {
  if (!_registry[view]) view = "receiver";
  const changed = state.view !== view;
  if (changed) _cleanup[state.view]?.();
  state.view = view;
  document.querySelectorAll(".nav-item").forEach((el) => {
    if (el.dataset.view === view) el.setAttribute("aria-current", "page");
    else el.removeAttribute("aria-current");
  });
  // "instant" overrides .main's smooth scroll-behavior: a new view starts at the top, it never glides there.
  if (changed) document.querySelector("#main")?.scrollTo({ top: 0, behavior: "instant" });
  _registry[view]?.();
}

export function rerender() { _registry[state.view]?.(); }
