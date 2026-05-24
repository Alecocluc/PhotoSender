import { state } from './state.js';

const _registry = {};
let _closeSidebar = () => {};

export function register(name, fn) { _registry[name] = fn; }
export function initSidebarHelper(fn) { _closeSidebar = fn; }

export function navigate(view) {
  state.view = view;
  document.querySelectorAll(".nav-item").forEach((el) =>
    el.classList.toggle("active", el.dataset.view === view)
  );
  if (window.innerWidth <= 900) _closeSidebar();
  _registry[view]?.();
}

export function rerender() { _registry[state.view]?.(); }
