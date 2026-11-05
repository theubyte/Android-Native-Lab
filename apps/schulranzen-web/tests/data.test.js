// Packs and backups: the rules that protect learning progress.
import { suite, test, eq, ok, no, notNull, isNull } from './harness.js';
import { parsePack, planImport, compareVersions, exportPack, FORMAT } from '../js/core/contentpack.js';
import { buildBackup, parseBackup, merge, bufferToBase64, base64ToBuffer, BACKUP_FORMAT } from '../js/core/backup.js';
import { review, Rating, newSchedule } from '../js/core/repetition.js';

const baseCard = (id, vorne, hinten, extra = {}) => ({ id, vorne, hinten, 'geprüft': true, ...extra });
const pack = (version, karten, extra = {}) => ({
  format: FORMAT, formatVersion: 1, id: 'test.paket', titel: 'Testpaket', version,
  stapel: [{ id: 's1', name: 'Stapel Eins', fach: 'Fach', karten }], ...extra,
});

let counter = 0;
const opts = (over = {}) => ({ today: 100, newId: () => `id${++counter}`, ...over });

function imported(p, over) {
  const { pack: parsed } = parsePack(p);
  const plan = planImport(parsed, { decks: [], cards: [] }, opts(over));
  return { parsed, plan, decks: plan.deckPuts, cards: plan.cardPuts };
}

suite('Inhaltspaket: Prüfung der Datei');

test('valid pack is read', () => {
  const r = parsePack(JSON.stringify(pack('1', [baseCard('k1', 'V', 'H', { thema: 'T', quelle: { buch: 'Buch', seite: 12 } })])));
  eq(r.errors, []);
  eq(r.pack.stapel[0].karten[0].quelle, { buch: 'Buch', seite: '12' });
  eq(r.pack.stapel[0].karten[0]['geprüft'], true);
});

test('broken files are refused with a reason', () => {
  eq(parsePack('{kaputt').errors.length, 1);
  isNull(parsePack('{"format":"anderes"}').pack);
  isNull(parsePack(JSON.stringify({ ...pack('1', []), formatVersion: 99 })).pack);
  isNull(parsePack(JSON.stringify({ ...pack('1', [baseCard('k', 'V', 'H')]), id: '' })).pack);
  isNull(parsePack(JSON.stringify(pack('1', [baseCard('k', '', 'H')]))).pack);
  isNull(parsePack(JSON.stringify(pack('1', [baseCard('k', 'V', 'H'), baseCard('k', 'W', 'H')]))).pack);
});

test('script urls and svg are no images', () => {
  isNull(parsePack(JSON.stringify(pack('1', [baseCard('k', 'V', 'H', { vorneBild: 'javascript:alert(1)' })]))).pack);
  isNull(parsePack(JSON.stringify(pack('1', [baseCard('k', 'V', 'H', { vorneBild: 'data:image/svg+xml;base64,AAAA' })]))).pack);
  notNull(parsePack(JSON.stringify(pack('1', [baseCard('k', 'V', 'H', { vorneBild: 'data:image/png;base64,AAAA' })]))).pack);
});

test('missing status counts as unverified and is said', () => {
  const r = parsePack(JSON.stringify(pack('1', [{ id: 'k', vorne: 'V', hinten: 'H' }])));
  eq(r.pack.stapel[0].karten[0]['geprüft'], false);
  ok(r.warnings.some((w) => w.includes('ungeprüft')));
});

test('versions compare as numbers', () => {
  ok(compareVersions('1.9', '1.10') < 0);
  ok(compareVersions('2', '2') === 0);
  ok(compareVersions('2026-11', '2026-10') > 0);
});

suite('Inhaltspaket: Import und Aktualisierung');

test('first import makes decks and new cards', () => {
  const { plan, cards } = imported(pack('1', [baseCard('k1', 'V1', 'H1'), baseCard('k2', 'V2', 'H2', { 'geprüft': false })]));
  eq(plan.summary.newDecks, 1);
  eq(plan.summary.newCards, 2);
  eq(plan.summary.unverifiedCards, 1);
  eq(cards[0].dueEpochDay, 100);
  eq(cards[0].lastReviewEpochDay, null);
  eq(cards[1].verified, false);
});

test('an update keeps the learning progress', () => {
  const first = imported(pack('1', [baseCard('k1', 'V1', 'H1'), baseCard('k2', 'V2', 'H2')]));
  const learnt = { ...first.cards[0], ...review(newSchedule({ dueEpochDay: 100 }), Rating.GOOD, 100) };
  const stored = [learnt, first.cards[1]];
  const next = parsePack(pack('2', [baseCard('k1', 'V1 neu', 'H1 neu'), baseCard('k2', 'V2', 'H2'), baseCard('k3', 'V3', 'H3')])).pack;
  const plan = planImport(next, { decks: first.decks, cards: stored }, opts());
  ok(plan.summary.isUpdate);
  eq(plan.summary.newDecks, 0);
  eq(plan.summary.newCards, 1);
  eq(plan.summary.changedCards, 1);
  eq(plan.summary.unchangedCards, 1);
  const k1 = plan.cardPuts.find((c) => c.packCardKey === 's1/k1');
  eq(k1.id, learnt.id);
  eq(k1.front, 'V1 neu');
  eq(k1.back, 'H1 neu');
  eq(k1.reps, learnt.reps);
  eq(k1.intervalDays, learnt.intervalDays);
  eq(k1.dueEpochDay, learnt.dueEpochDay);
  eq(k1.lastReviewEpochDay, 100);
  eq(k1.ease, learnt.ease);
});

test('own edits win over a new pack text', () => {
  const first = imported(pack('1', [baseCard('k1', 'V1', 'H1')]));
  const stored = [{ ...first.cards[0], back: 'Meine Antwort' }];
  const plan = planImport(parsePack(pack('2', [baseCard('k1', 'V1', 'H1 neu')])).pack, { decks: first.decks, cards: stored }, opts());
  eq(plan.summary.keptOwnEdits, 1);
  eq(plan.cardPuts[0].back, 'Meine Antwort');
});

test('a corrected card takes the status of the pack', () => {
  const first = imported(pack('1', [baseCard('k1', 'V1', 'H1')]));
  const plan = planImport(parsePack(pack('2', [baseCard('k1', 'V1', 'H1 neu', { 'geprüft': false })])).pack, { decks: first.decks, cards: first.cards }, opts());
  eq(plan.cardPuts[0].verified, false);
});

test('a card the new version dropped is kept as an own card unless asked', () => {
  const first = imported(pack('1', [baseCard('k1', 'V1', 'H1'), baseCard('k2', 'V2', 'H2')]));
  const stored = first.cards.map((c) => ({ ...c, lastReviewEpochDay: 100 }));
  const next = parsePack(pack('2', [baseCard('k1', 'V1', 'H1')])).pack;
  const keep = planImport(next, { decks: first.decks, cards: stored }, opts());
  eq(keep.summary.removedCards, 1);
  eq(keep.summary.removedWithProgress, 1);
  eq(keep.cardDeletes, []);
  const own = keep.cardPuts.find((c) => c.front === 'V2');
  isNull(own.packId);
  eq(own.lastReviewEpochDay, 100);
  const del = planImport(next, { decks: first.decks, cards: stored }, opts({ removeMissing: true }));
  eq(del.cardDeletes.length, 1);
});

test('unverified new cards can be left out', () => {
  const { plan } = imported(pack('1', [baseCard('k1', 'V', 'H', { 'geprüft': false }), baseCard('k2', 'W', 'H')]), { skipUnverified: true });
  eq(plan.summary.newCards, 1);
  eq(plan.summary.skippedUnverified, 1);
});

test('another pack stays untouched', () => {
  const first = imported(pack('1', [baseCard('k1', 'V1', 'H1')]));
  const other = { ...pack('1', [baseCard('k1', 'X', 'Y')]), id: 'anderes.paket' };
  const plan = planImport(parsePack(other).pack, { decks: first.decks, cards: first.cards }, opts());
  eq(plan.summary.isUpdate, false);
  eq(plan.summary.newCards, 1);
  eq(plan.summary.removedCards, 0);
});

test('a deck exported as a pack reads back', () => {
  const deck = { id: 'd', name: 'Mein Stapel', subject: 'Mathe' };
  const cards = [{ id: 'c', deckId: 'd', front: 'F', back: 'B', topic: 'T', source: { buch: 'B', seite: '3' }, verified: true }];
  const r = parsePack(JSON.stringify(exportPack({ id: 'p', titel: 'P' }, [deck], cards)));
  eq(r.errors, []);
  eq(r.pack.stapel[0].karten[0].vorne, 'F');
  eq(r.pack.stapel[0].karten[0]['geprüft'], true);
});

suite('Sicherung');

const emptyState = () => ({ decks: [], cards: [], exams: [], reviews: [], mockRuns: [], focus: [], packs: [], images: [], settings: [] });
const card = (id, deckId, front, over = {}) => ({ id, deckId, front, back: 'B', reps: 0, intervalDays: 0, ease: 2.5, lapses: 0, dueEpochDay: 100, lastReviewEpochDay: null, ...over });

test('a backup reads back and never carries a key', () => {
  const data = { ...emptyState(), decks: [{ id: 'd', name: 'D' }], cards: [card('c', 'd', 'F')], settings: [{ key: 'theme', value: 'dark' }, { key: 'claudeKey', value: 'geheim' }] };
  const text = JSON.stringify(buildBackup(data, '1'));
  no(text.includes('geheim'));
  const r = parseBackup(text);
  eq(r.errors, []);
  eq(r.backup.data.cards[0].id, 'c');
  eq(r.backup.data.settings.length, 1);
});

test('a key in a foreign file is dropped on the way in', () => {
  const r = parseBackup(JSON.stringify({ format: BACKUP_FORMAT, formatVersion: 1, data: { ...emptyState(), settings: [{ key: 'claudeKey', value: 'x' }] } }));
  eq(r.backup.data.settings, []);
});

test('damaged backups are refused', () => {
  isNull(parseBackup('kaputt').backup);
  isNull(parseBackup('{"format":"x"}').backup);
  isNull(parseBackup(JSON.stringify({ format: BACKUP_FORMAT, formatVersion: 1, data: { decks: [], cards: [card('c', 'fehlt', 'F')] } })).backup);
  isNull(parseBackup(JSON.stringify({ format: BACKUP_FORMAT, formatVersion: 1, data: { images: [{ id: 'i', mime: 'text/html', base64: 'AA' }] } })).backup);
});

test('base64 survives bytes', () => {
  const bytes = new Uint8Array(70000).map((_, i) => i % 256);
  eq([...new Uint8Array(base64ToBuffer(bufferToBase64(bytes.buffer)))].slice(250, 260), [...bytes].slice(250, 260));
});

suite('Zusammenführen');

test('decks by name and the later review wins', () => {
  const local = { ...emptyState(), decks: [{ id: 'a', name: 'Mathe' }], cards: [card('c1', 'a', 'Frage', { reps: 1, lastReviewEpochDay: 100, dueEpochDay: 101 })] };
  const remote = { ...emptyState(), decks: [{ id: 'x', name: 'mathe' }], cards: [card('c9', 'x', 'FRAGE', { reps: 2, lastReviewEpochDay: 105, dueEpochDay: 108, intervalDays: 3 })] };
  const m = merge(local, remote);
  eq(m.data.decks.length, 1);
  eq(m.data.cards.length, 1);
  eq(m.data.cards[0].id, 'c1');
  eq(m.data.cards[0].dueEpochDay, 108);
  eq(m.updated, 1);
});

test('an older review does not overwrite', () => {
  const local = { ...emptyState(), decks: [{ id: 'a', name: 'M' }], cards: [card('c', 'a', 'F', { lastReviewEpochDay: 200, dueEpochDay: 205 })] };
  const remote = { ...emptyState(), decks: [{ id: 'x', name: 'M' }], cards: [card('r', 'x', 'F', { lastReviewEpochDay: 100, dueEpochDay: 300 })] };
  eq(merge(local, remote).data.cards[0].dueEpochDay, 205);
});

test('what exists only on one side stays and ids do not collide', () => {
  const local = { ...emptyState(), decks: [{ id: 'a', name: 'A' }], cards: [card('c', 'a', 'Nur hier')] };
  const remote = { ...emptyState(), decks: [{ id: 'a', name: 'B' }], cards: [card('c', 'a', 'Nur dort')] };
  const m = merge(local, remote);
  eq(m.data.decks.length, 2);
  eq(m.data.cards.length, 2);
  eq(new Set(m.data.cards.map((c) => c.id)).size, 2);
  const there = m.data.cards.find((c) => c.front === 'Nur dort');
  eq(m.data.decks.find((d) => d.id === there.deckId).name, 'B');
});

test('reviews, runs and focus are not doubled and follow the cards', () => {
  const remote = {
    ...emptyState(), decks: [{ id: 'x', name: 'M' }], cards: [card('r', 'x', 'F')],
    exams: [{ id: 'e', subject: 'M', dateEpochDay: 200, kind: 'TEST' }],
    reviews: [{ id: 'rv', cardId: 'r', deckId: 'x', epochDay: 100, at: 5, rating: 2 }],
    mockRuns: [{ id: 'm', examId: 'e', at: 7, percent: 80, asked: 1, answers: [{ cardId: 'r', topic: 'T', grade: 'RIGHT' }] }],
    focus: [{ id: 'f', at: 9, epochDay: 100, minutes: 25 }],
  };
  const once = merge(emptyState(), remote);
  const twice = merge(once.data, remote);
  eq(twice.data.reviews.length, 1);
  eq(twice.data.mockRuns.length, 1);
  eq(twice.data.focus.length, 1);
  eq(twice.data.exams.length, 1);
  eq(twice.data.cards.length, 1);
  eq(twice.data.mockRuns[0].answers[0].cardId, twice.data.cards[0].id);
});

test('settings only where none exist; material done on either side counts', () => {
  const local = { ...emptyState(), settings: [{ key: 'theme', value: 'dark' }], exams: [{ id: 'e', subject: 'M', dateEpochDay: 1, kind: 'TEST', material: [{ id: 'm', label: 'S. 1', doneAt: null }] }] };
  const remote = { ...emptyState(), settings: [{ key: 'theme', value: 'light' }, { key: 'budgetMinutes', value: 45 }, { key: 'claudeKey', value: 'x' }], exams: [{ id: 'z', subject: 'm', dateEpochDay: 1, kind: 'TEST', material: [{ id: 'n', label: 'S. 1', doneAt: 5 }] }] };
  const m = merge(local, remote);
  eq(m.data.settings.find((s) => s.key === 'theme').value, 'dark');
  eq(m.data.settings.find((s) => s.key === 'budgetMinutes').value, 45);
  no(m.data.settings.some((s) => s.key === 'claudeKey'));
  eq(m.data.exams[0].material[0].doneAt, 5);
});
