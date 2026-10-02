import { register } from '../router.js';
import { createMediaBrowser } from '../media-browser.js';
const query = {};
let view;
function render() {
  if (!document.querySelector('#history-title')) { view?.destroy(); view = createMediaBrowser({ mode: 'history', title: 'History', query }); }
  else view?.refresh();
}
register('history', render, () => { view?.destroy(); view = null; });
export { render as renderHistory };
