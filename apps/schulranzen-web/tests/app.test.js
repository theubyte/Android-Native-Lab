// Checks on the app as delivered: offline list, sample pack, design contrast, no remote code.
import { suite, test, eq, ok, no, isNull } from './harness.js';
import { parsePack } from '../js/core/contentpack.js';
import { requestFor, describeStatus, readReply, MODEL, MODEL_THRIFTY } from '../js/core/claudeapi.js';
import { toEpochDay, epochDayOf, isoOf, parseIso, countdown } from '../js/core/dates.js';
import * as Study from '../js/core/study.js';
import { Rating } from '../js/core/repetition.js';

const text = async (path) => {
  const res = await fetch(`../${path}`, { cache: 'no-store' });
  if (!res.ok) throw new Error(`${path}: ${res.status}`);
  return res.text();
};

async function listedFiles() {
  const sw = await text('sw.js');
  return [...sw.match(/const FILES = \[([\s\S]*?)\];/)[1].matchAll(/'([^']+)'/g)].map((m) => m[1]);
}

suite('Auslieferung');

test('every file on the offline list exists', async () => {
  for (const f of await listedFiles()) {
    if (f === './') continue;
    await text(f);
  }
});

test('every module the app imports is on the offline list', async () => {
  const listed = new Set(await listedFiles());
  const seen = new Set();
  const walk = async (path) => {
    if (seen.has(path)) return;
    seen.add(path);
    const src = await text(path);
    const dir = path.slice(0, path.lastIndexOf('/') + 1);
    for (const m of src.matchAll(/from '(\.[^']+)'/g)) {
      const url = new URL(m[1], `http://x/${dir}`).pathname.slice(1);
      await walk(url);
    }
  };
  await walk('js/main.js');
  ok(seen.size > 20, 'module graph looks too small');
  for (const f of seen) ok(listed.has(f), `fehlt in sw.js: ${f}`);
});

test('version in app and worker match', async () => {
  const app = (await text('js/version.js')).match(/'([^']+)'/)[1];
  const sw = (await text('sw.js')).match(/VERSION = '([^']+)'/)[1];
  eq(app, sw);
});

test('nothing is loaded from elsewhere', async () => {
  const html = await text('index.html');
  no(/(src|href)="https?:/.test(html), 'absolute URL in index.html');
  const css = await text('css/app.css');
  no(/url\(["']?https?:/.test(css), 'absolute URL in CSS');
  for (const f of await listedFiles()) {
    if (!f.endsWith('.js')) continue;
    const src = await text(f);
    no(/\beval\(|new Function\(|document\.write/.test(src), `riskanter Aufruf in ${f}`);
    // dom.js puts its own fixed icon paths into SVG; no other text ever becomes markup.
    if (f !== 'js/ui/dom.js') no(/innerHTML|outerHTML|insertAdjacentHTML/.test(src), `HTML aus Text in ${f}`);
    if (f !== 'js/core/claudeapi.js') no(/https?:\/\/(?!www\.w3\.org)/.test(src), `Adresse in ${f}`);
  }
});

test('no syntax that older Safari cannot parse', async () => {
  for (const f of await listedFiles()) {
    if (!f.endsWith('.js')) continue;
    const src = await text(f);
    no(/\(\?<[=!]/.test(src), `Lookbehind in ${f}: Safari vor 16.4 lädt die Datei dann gar nicht`);
    no(/\.at\(|structuredClone\(|\.replaceAll\(|\?\?=|\|\|=|&&=/.test(src.split('repo.replaceAll(').join('')), `Zu neuer Aufruf in ${f}`);
  }
});

test('paths are relative, so a subfolder works', async () => {
  const html = await text('index.html');
  no(/(src|href)="\//.test(html));
  const manifest = JSON.parse(await text('manifest.webmanifest'));
  eq(manifest.start_url, './');
  eq(manifest.scope, './');
  ok(manifest.icons.some((i) => i.purpose === 'maskable' && i.sizes === '512x512'));
  ok(manifest.icons.some((i) => i.purpose === 'any' && i.sizes === '192x192'));
  for (const i of manifest.icons) ok(!i.src.startsWith('/'));
});

test('the sample pack is marked and contains no subject matter claim', async () => {
  const { pack, errors } = parsePack(await text('beispiele/app-kennenlernen.json'));
  eq(errors, []);
  eq(pack.beispiel, true);
  ok(pack.titel.startsWith('Beispiel'));
  ok(pack.stapel.every((d) => d.name.startsWith('Beispiel')));
  ok(pack.stapel.flatMap((d) => d.karten).some((k) => k['geprüft'] === false), 'shows the unverified state');
});

suite('Design');

function lum(hex) {
  const v = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
}
const ratio = (a, b) => {
  const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
};

test('text reaches 4.5:1 in both themes', async () => {
  const css = await text('css/app.css');
  const blockOf = (re) => re.exec(css)[1];
  const light = blockOf(/:root \{([\s\S]*?)\n\}/);
  const dark = blockOf(/:root\[data-theme="dark"\] \{([\s\S]*?)\n\}/);
  const token = (block, name) => new RegExp(`--${name}:\\s*(#[0-9A-Fa-f]{6})`).exec(block)[1];
  for (const [label, block] of [['hell', light], ['dunkel', dark]]) {
    const pairs = [['ink', 'paper'], ['ink', 'desk'], ['ink', 'raised'], ['ink-muted', 'paper'], ['ink-muted', 'desk'], ['ink-muted', 'raised'],
      ['signal-text', 'paper'], ['signal-text', 'raised'], ['on-signal', 'signal-fill'], ['good', 'paper'], ['warn', 'warn-paper'], ['on-display-chip', 'display-chip']];
    for (const [f, b] of pairs) {
      const r = ratio(token(block, f), token(block, b));
      ok(r >= 4.5, `${label}: ${f} auf ${b} nur ${r.toFixed(2)}:1`);
    }
  }
});

test('touch targets stay at least 44 px in the styles', async () => {
  const css = await text('css/app.css');
  for (const cls of ['.plate', '.btn-quiet', '.input, .select, .textarea', '.check']) {
    const at = css.indexOf(`${cls} {`);
    ok(at >= 0, cls);
    const rule = css.slice(at, css.indexOf('}', at));
    const m = /min-height:\s*(\d+)px/.exec(rule);
    ok(m && Number(m[1]) >= 44, `${cls}: ${rule}`);
  }
});

suite('Erklärhilfe (ohne Netz)');

test('a request carries the opt-in header and the card, and the key only in its header', () => {
  const { headers, body } = requestFor(MODEL, 'SCHLUESSEL', 'Frage?', 'Antwort.');
  eq(headers['anthropic-dangerous-direct-browser-access'], 'true');
  eq(headers['x-api-key'], 'SCHLUESSEL');
  no(JSON.stringify(body).includes('SCHLUESSEL'));
  ok(body.messages[0].content.includes('Frage?'));
  eq(body.fallbacks, 'default');
});

test('the thrifty model sends no fallback', () => {
  const { headers, body } = requestFor(MODEL_THRIFTY, 'k', 'a', 'b');
  isNull(body.fallbacks);
  isNull(headers['anthropic-beta']);
});

test('replies and failures read sensibly', () => {
  eq(readReply({ content: [{ type: 'thinking', thinking: '' }, { type: 'text', text: ' Hallo ' }] }).text, 'Hallo');
  ok(readReply({ stop_reason: 'refusal', content: [] }).error);
  ok(readReply({ content: [] }).error);
  ok(describeStatus(401).includes('Schlüssel'));
  no(describeStatus(500).includes('sk-'));
});

suite('Kalender');

test('epoch days match java.time', () => {
  eq(epochDayOf(1970, 1, 1), 0);
  eq(epochDayOf(2026, 10, 29), 20755);
  eq(epochDayOf(2027, 1, 1), 20819);
});

test('a local day does not shift with the hour', () => {
  eq(toEpochDay(new Date(2026, 2, 29, 0, 30)), epochDayOf(2026, 3, 29));
  eq(toEpochDay(new Date(2026, 2, 29, 23, 30)), epochDayOf(2026, 3, 29));
  eq(toEpochDay(new Date(2026, 9, 25, 0, 30)), epochDayOf(2026, 10, 25));
  eq(toEpochDay(new Date(2026, 9, 25, 23, 30)), epochDayOf(2026, 10, 25));
});

test('date fields read back and bad dates are refused', () => {
  eq(parseIso(isoOf(20755)), 20755);
  isNull(parseIso('2026-02-31'));
  isNull(parseIso('morgen'));
  eq(countdown(100, 101), 'morgen');
  eq(countdown(100, 105), 'in 5 Tagen');
});

suite('Verknüpfung der Daten');

const baseCard = (id, deckId, over = {}) => ({ id, deckId, front: `Frage ${id}`, back: 'Antwort', reps: 0, intervalDays: 0, ease: 2.5, lapses: 0, dueEpochDay: 100, lastReviewEpochDay: null, ...over });
const state = (over = {}) => ({ decks: [], cards: [], exams: [], reviews: [], mockRuns: [], focus: [], packs: [], settings: new Map(), ...over });

test('decks join an exam by subject or by hand', () => {
  const decks = [{ id: 'a', name: 'Bruch', subject: 'Mathe' }, { id: 'b', name: 'Mathe', subject: '' }, { id: 'c', name: 'Vokabeln', subject: 'Englisch' }];
  eq(Study.examDecks({ subject: 'mathe', deckIds: null }, decks).map((d) => d.id), ['a', 'b']);
  eq(Study.examDecks({ subject: 'mathe', deckIds: ['c'] }, decks).map((d) => d.id), ['c']);
});

test('the next exam caps the interval, a past one does not', () => {
  const s = state({ decks: [{ id: 'a', name: 'X', subject: 'Mathe' }], exams: [{ id: 'old', subject: 'Mathe', dateEpochDay: 90 }, { id: 'far', subject: 'Mathe', dateEpochDay: 200 }, { id: 'near', subject: 'Mathe', dateEpochDay: 110 }] });
  eq(Study.examDateForCard({ deckId: 'a' }, s, 100), 110);
  eq(Study.examDateForCard({ deckId: 'a' }, s, 300), null);
});

test('a pack topic wins, otherwise the words of the card decide', () => {
  const exam = { topics: 'Bruchrechnen\nGeometrie' };
  const deck = { name: 'Stapel' };
  eq(Study.topicOfCard({ topic: 'geometrie', front: 'x', back: 'y' }, exam, deck), 'Geometrie');
  eq(Study.topicOfCard({ topic: '', front: 'Was ist Geometrie?', back: 'y' }, exam, deck), 'Geometrie');
  eq(Study.topicOfCard({ topic: '', front: 'ganz anders', back: 'y' }, exam, deck), 'Stapel');
});

test('the plan does not hand out new cards twice a day', () => {
  const cards = [];
  for (let i = 0; i < 20; i++) cards.push(baseCard(`c${i}`, 'a'));
  const s = state({ decks: [{ id: 'a', name: 'X', subject: 'M' }], exams: [{ id: 'e', subject: 'M', kind: 'TEST', dateEpochDay: 105, material: [] }], cards });
  eq(Study.todayPlan(s, 100, 600).cards.length, 5);
  // Five learnt today: nothing new until tomorrow.
  const learnt = cards.map((c, i) => (i < 5 ? { ...c, reps: 1, intervalDays: 1, dueEpochDay: 101, lastReviewEpochDay: 100 } : c));
  const reviews = learnt.slice(0, 5).map((c, i) => ({ id: `r${i}`, cardId: c.id, deckId: 'a', epochDay: 100, at: i, rating: Rating.GOOD }));
  eq(Study.todayPlan({ ...s, cards: learnt, reviews }, 100, 600).cards.length, 0);
  // The next day the quota is open again.
  ok(Study.todayPlan({ ...s, cards: learnt, reviews }, 101, 600).cards.length > 0);
});

test('weak means on the error list or forgotten twice, as on Android', () => {
  const s = state({ decks: [{ id: 'a', name: 'X' }], cards: [baseCard('c', 'a'), baseCard('d', 'a', { lapses: 2 })] });
  eq([...Study.weakCardIds(s)], ['d']);
  s.mockRuns = [{ id: 'm', examId: 'e', at: 1, epochDay: 100, answers: [{ cardId: 'c', grade: 'WRONG', topic: 'T' }] }];
  s.reviews = [{ id: 'r', cardId: 'c', deckId: 'a', epochDay: 101, at: 2, rating: Rating.AGAIN }];
  ok(Study.weakCardIds(s).has('c'), 'wrong on two days, one of them in a mock exam');
});

test('readiness counts the material and names missing topics', () => {
  const s = state({
    decks: [{ id: 'a', name: 'X', subject: 'M' }],
    cards: [baseCard('c', 'a')],
    exams: [],
  });
  const exam = { id: 'e', subject: 'M', kind: 'TEST', dateEpochDay: 110, topics: 'Fehlt', material: [{ id: 'm', label: 'S. 1', doneAt: null }] };
  const r = Study.readinessOf(exam, s, 100);
  ok(r.gaps.some((g) => g.kind === 'LINKS_OPEN'));
  ok(r.gaps.some((g) => g.kind === 'TOPICS_MISSING'));
  isNull(r.percent);
});
