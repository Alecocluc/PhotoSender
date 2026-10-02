import { register } from '../router.js';
import { createMediaBrowser } from '../media-browser.js';
const query = {};
let view;
function render() {
  if (!document.querySelector('#photos-title')) { view?.destroy(); view = createMediaBrowser({ mode: 'photos', title: 'Photos', query }); }
  else view?.refresh();
}
register('photos', render, () => { view?.destroy(); view = null; });
export { render as renderPhotos };
