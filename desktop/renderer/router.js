import { state } from './state.js';

const _registry = {};
let _closeSidebar = () => {};

export function register(name, fn) { _registry[name] = fn; }
export function initSidebarHelper(fn) { _closeSidebar = fn; }

export function navigate(view) {
  state.view = view;
  document.querySelectorAll(".nav-item").forEach((el) => {
    const active = el.dataset.view === view;
    el.classList.toggle("active", active);
    if (active) el.setAttribute("aria-current", "page");
    else el.removeAttribute("aria-current");
  });
  if (window.innerWidth <= 900) _closeSidebar();
  _registry[view]?.();
}

export function rerender() { _registry[state.view]?.(); }
