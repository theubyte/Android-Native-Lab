// Service worker. Change VERSION with each release: the browser then installs the new list
// beside the old one, the page offers "Aktualisieren", and only then does it take over.
const VERSION = '1.0.0';
const CACHE = `schulranzen-${VERSION}`;

const FILES = [
  './',
  'index.html',
  'manifest.webmanifest',
  'css/app.css',
  'fonts/bricolage.woff2',
  'fonts/inter.woff2',
  'fonts/plexmono_regular.woff2',
  'fonts/plexmono_medium.woff2',
  'fonts/OFL-bricolagegrotesque.txt',
  'fonts/OFL-inter.txt',
  'fonts/OFL-ibmplexmono.txt',
  'icons/icon.svg',
  'icons/icon-192.png',
  'icons/icon-512.png',
  'icons/maskable-192.png',
  'icons/maskable-512.png',
  'icons/apple-touch-icon.png',
  'beispiele/app-kennenlernen.json',
  'js/theme-boot.js',
  'js/main.js',
  'js/app.js',
  'js/platform.js',
  'js/version.js',
  'js/core/answercheck.js',
  'js/core/backup.js',
  'js/core/cardcsv.js',
  'js/core/cardsuggester.js',
  'js/core/claudeapi.js',
  'js/core/contentpack.js',
  'js/core/dates.js',
  'js/core/examplan.js',
  'js/core/mockexam.js',
  'js/core/progress.js',
  'js/core/questions.js',
  'js/core/readiness.js',
  'js/core/repetition.js',
  'js/core/study.js',
  'js/core/text.js',
  'js/core/todayplan.js',
  'js/data/db.js',
  'js/data/repo.js',
  'js/ui/banners.js',
  'js/ui/cardview.js',
  'js/ui/decks.js',
  'js/ui/dom.js',
  'js/ui/errors.js',
  'js/ui/exams.js',
  'js/ui/explain.js',
  'js/ui/focus.js',
  'js/ui/mock.js',
  'js/ui/more.js',
  'js/ui/packs.js',
  'js/ui/session.js',
  'js/ui/stats.js',
  'js/ui/texts.js',
  'js/ui/today.js',
];

self.addEventListener('install', (event) => {
  // "reload" goes past the HTTP cache; a half-old set of modules would break the app. If one
  // file fails, the whole install fails and the old version simply stays.
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE);
    await cache.addAll(FILES.map((f) => new Request(f, { cache: 'reload' })));
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) {
      if (key.startsWith('schulranzen-') && key !== CACHE) await caches.delete(key);
    }
    await self.clients.claim();
  })());
});

self.addEventListener('message', (event) => {
  if (event.data === 'skipWaiting') self.skipWaiting();
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  // Only this app's own files; the optional Erklärhilfe talks to its API unhindered.
  if (url.origin !== self.location.origin) return;
  // The tests must always run the current code.
  if (url.pathname.includes('/tests/')) return;
  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    // Any address inside the app opens the app; real files such as the licences stay themselves.
    if (req.mode === 'navigate' && (!/\.[a-z0-9]+$/i.test(url.pathname) || url.pathname.endsWith('.html'))) {
      return (await cache.match('index.html')) ?? fetch(req);
    }
    return (await cache.match(req, { ignoreSearch: true })) ?? fetch(req);
  })());
});
