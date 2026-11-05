// The shell: masthead, registers, routing and the current data snapshot.
import { h, clear, icon, key, toast, btn } from './ui/dom.js';
import { loadState, setSetting } from './data/repo.js';
import { toEpochDay, weekday, dateLong } from './core/dates.js';
import { applyUpdate, onUpdateReady, askPersist, onPlatformChange } from './platform.js';
import { renderToday } from './ui/today.js';
import { renderDecks, renderDeck } from './ui/decks.js';
import { renderExams, renderExam } from './ui/exams.js';
import { renderSession } from './ui/session.js';
import { renderMock } from './ui/mock.js';
import { renderErrors } from './ui/errors.js';
import { renderFocus } from './ui/focus.js';
import { renderStats } from './ui/stats.js';
import { renderMore } from './ui/more.js';
import { renderPacks } from './ui/packs.js';

export const app = {
  state: null,
  today: toEpochDay(),
  async reload() { this.state = await loadState(); this.today = toEpochDay(); return this.state; },
  setting(key_, fallback = null) { return this.state.settings.get(key_) ?? fallback; },
  async set(key_, value) {
    await setSetting(key_, value);
    if (value === null || value === undefined) this.state.settings.delete(key_);
    else this.state.settings.set(key_, value);
  },
  /** Draws the current screen again from fresh data. */
  async refresh() { await draw(true); },
  go(path) { location.hash = path; },
};

const TABS = [
  ['heute', 'Heute', 'home', true],
  ['stapel', 'Stapel', 'cards', true],
  ['pruefungen', 'Prüfungen', 'calendar', true],
  ['fehler', 'Fehlerheft', 'error', false],
  ['fokus', 'Fokus', 'timer', true],
  ['statistik', 'Statistik', 'chart', false],
  ['mehr', 'Mehr', 'more', true],
];

const ROUTES = {
  heute: renderToday,
  stapel: (root, [id]) => (id ? renderDeck(root, id) : renderDecks(root)),
  pruefungen: (root, [id]) => (id ? renderExam(root, id) : renderExams(root)),
  lernen: (root, args) => renderSession(root, args),
  probe: (root, args) => renderMock(root, args),
  fehler: renderErrors,
  fokus: renderFocus,
  statistik: renderStats,
  mehr: renderMore,
  pakete: renderPacks,
};

/** Which tab lights up for a route. */
const TAB_OF = { lernen: 'heute', probe: 'pruefungen', pakete: 'mehr' };
/** Full-attention screens hide the navigation. */
const IMMERSIVE = new Set(['lernen', 'probe']);

export function applyTheme(mode) {
  const root = document.documentElement;
  if (mode === 'light' || mode === 'dark') root.setAttribute('data-theme', mode);
  else root.removeAttribute('data-theme');
  try {
    if (mode === 'light' || mode === 'dark') localStorage.setItem('schulranzen-theme', mode);
    else localStorage.removeItem('schulranzen-theme');
  } catch { /* the setting in the database still holds */ }
  syncThemeColor();
}

function isDarkNow() {
  const set = document.documentElement.getAttribute('data-theme');
  if (set) return set === 'dark';
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}

function syncThemeColor() {
  document.getElementById('theme-color')?.setAttribute('content', isDarkNow() ? '#181715' : '#EDE8DE');
  themeKey?.replaceChildren(icon(isDarkNow() ? 'sun' : 'moon'));
}

async function toggleTheme() {
  const next = isDarkNow() ? 'light' : 'dark';
  applyTheme(next);
  await app.set('theme', next);
}

let themeKey = null;
let cleanup = null;
let screenHost = null;
let clockEl = null;
let dateEl = null;
let lastRoute = null;
let lastKey = '';
let drawing = 0;

function shell() {
  const root = document.getElementById('app');
  clear(root);
  clockEl = h('div.clock', { 'aria-hidden': 'true' }, '');
  dateEl = h('span', {}, '');
  themeKey = key(isDarkNow() ? 'sun' : 'moon', 'Hell oder dunkel', toggleTheme);
  const masthead = h('header.masthead', { id: 'masthead' },
    h('div.masthead-title', {}, h('div.brand', {}, 'Schulranzen'), h('div.dateline', {}, h('span.lamp'), dateEl)),
    clockEl, themeKey);
  const registers = h('nav.registers', { 'aria-label': 'Bereiche', id: 'registers' },
    TABS.map(([id, label]) => h('a.register', { href: `#/${id}`, 'data-tab': id }, label)));
  screenHost = h('main.sheet-page', { id: 'main', tabindex: '-1' });
  const tabbar = h('nav.tabbar', { 'aria-label': 'Bereiche', id: 'tabbar' },
    TABS.filter((t) => t[3]).map(([id, label, ic]) => h('a', { href: `#/${id}`, 'data-tab': id }, icon(ic), label)));
  root.append(h('div.frame', {}, masthead, registers, screenHost), tabbar);
  tick();
  setInterval(tick, 15000);
}

function tick() {
  const now = new Date();
  const today = toEpochDay(now);
  clockEl.textContent = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
  dateEl.textContent = `${weekday(today)} · ${dateLong(today)}`;
  // A screen left open over midnight must show the new day, but no running session is torn down.
  if (today !== app.today && !IMMERSIVE.has(lastRoute)) app.refresh();
}

function parseHash() {
  const raw = location.hash.replace(/^#\/?/, '');
  const parts = raw.split('/').filter(Boolean).map(decodeURIComponent);
  return { name: ROUTES[parts[0]] ? parts[0] : 'heute', args: parts.slice(1) };
}

async function draw(keepScroll = false) {
  const ticket = ++drawing;
  const { name, args } = parseHash();
  const y = window.scrollY;
  if (cleanup) { try { cleanup(); } catch { /* a screen's cleanup must not stop navigation */ } cleanup = null; }
  await app.reload();
  if (ticket !== drawing) return;
  // Durable storage is asked for once there is something worth keeping.
  if (!app.setting('persistAsked') && app.state.cards.length > 0) {
    app.set('persistAsked', true);
    askPersist();
  }
  const fresh = h('div');
  let result;
  try {
    result = await ROUTES[name](fresh, args);
  } catch (e) {
    console.error(e);
    clear(fresh);
    fresh.append(h('div.empty', {}, h('h1.title', {}, 'Das ging schief'), h('p', {}, e.message || 'Unbekannter Fehler.'), btn('Zur Übersicht', () => app.go('/heute'))));
  }
  if (ticket !== drawing) { if (typeof result === 'function') result(); return; }
  cleanup = typeof result === 'function' ? result : null;
  screenHost.replaceChildren(fresh);
  const tab = TAB_OF[name] ?? name;
  document.querySelectorAll('[data-tab]').forEach((a) => {
    if (a.dataset.tab === tab) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  });
  const immersive = IMMERSIVE.has(name);
  document.getElementById('registers').hidden = immersive;
  document.getElementById('tabbar').hidden = immersive;
  document.getElementById('masthead').hidden = immersive;
  const routeKey = `${name}/${args.join('/')}`;
  const changed = lastKey !== routeKey;
  lastRoute = name;
  lastKey = routeKey;
  if (keepScroll && !changed) window.scrollTo(0, y);
  else window.scrollTo(0, 0);
  if (!keepScroll) {
    const head = fresh.querySelector('h1');
    if (head) { head.setAttribute('tabindex', '-1'); head.focus({ preventScroll: true }); document.title = `${head.textContent} · Schulranzen`; } else document.title = 'Schulranzen';
  }
}

export async function startApp() {
  await app.reload();
  applyTheme(app.setting('theme', 'auto'));
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener?.('change', syncThemeColor);
  shell();
  window.addEventListener('hashchange', () => draw(false));
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && toEpochDay() !== app.today && !IMMERSIVE.has(lastRoute)) app.refresh();
  });
  await draw(false);
  // The browser offers installing some time after load; quiet screens can show it then.
  onPlatformChange(() => { if (lastRoute === 'heute' || lastRoute === 'mehr') app.refresh(); });
  onUpdateReady(() => {
    toast('Neue Version bereit.', { ms: 600000, action: { label: 'Aktualisieren', run: applyUpdate } });
  });
}

