// Everything the app stores. Screens read the snapshot from loadState() and write through
// the functions here; after each write they ask the app to read again.
import { getAll, get, write } from './db.js';
import { base64ToBuffer, bufferToBase64, buildBackup, STORES } from '../core/backup.js';
import { planImport } from '../core/contentpack.js';
import { toEpochDay } from '../core/dates.js';
import { applyRating, examDateForCard } from '../core/study.js';

export function newId() {
  const bytes = new Uint8Array(12);
  crypto.getRandomValues(bytes);
  return [...bytes].map((b) => b.toString(36).padStart(2, '0')).join('');
}

export async function loadState() {
  const [decks, cards, exams, reviews, mockRuns, focus, packs, settings] = await Promise.all([
    getAll('decks'), getAll('cards'), getAll('exams'), getAll('reviews'), getAll('mockRuns'), getAll('focus'), getAll('packs'), getAll('settings'),
  ]);
  decks.sort((a, b) => a.name.localeCompare(b.name, 'de'));
  cards.sort((a, b) => (a.createdAt ?? 0) - (b.createdAt ?? 0));
  exams.sort((a, b) => a.dateEpochDay - b.dateEpochDay);
  return { decks, cards, exams, reviews, mockRuns, focus, packs, settings: new Map(settings.map((s) => [s.key, s.value])) };
}

export async function setSetting(key, value) {
  await write('settings', (t) => {
    if (value === null || value === undefined) t.del('settings', key);
    else t.put('settings', { key, value });
  });
}

export const getSetting = async (key) => (await get('settings', key))?.value ?? null;

/** Marks that something worth backing up changed; the warning counts from here. */
const touched = (t) => t.put('settings', { key: 'changedAt', value: Date.now() });

export async function saveDeck(deck) {
  const row = { createdAt: Date.now(), colorIndex: 0, subject: '', ...deck, id: deck.id ?? newId() };
  await write(['decks', 'settings'], (t) => { t.put('decks', row); touched(t); });
  return row;
}

export async function deleteDeck(id, state) {
  const cards = state.cards.filter((c) => c.deckId === id);
  const ids = new Set(cards.map((c) => c.id));
  await write(['decks', 'cards', 'reviews', 'images', 'settings'], (t) => {
    t.del('decks', id);
    for (const c of cards) {
      t.del('cards', c.id);
      if (c.frontImage) t.del('images', c.frontImage);
      if (c.backImage) t.del('images', c.backImage);
    }
    for (const r of state.reviews) if (ids.has(r.cardId)) t.del('reviews', r.id);
    touched(t);
  });
}

export function newCardRow(deckId, front, back, over = {}) {
  return {
    id: newId(), deckId, front: front.trim(), back: back.trim(), frontImage: null, backImage: null,
    topic: '', source: null, verified: null, packId: null, packCardKey: null, packFront: null, packBack: null,
    reps: 0, intervalDays: 0, ease: 2.5, lapses: 0, dueEpochDay: toEpochDay(), lastReviewEpochDay: null, createdAt: Date.now(),
    ...over,
  };
}

export async function saveCard(card) {
  await write(['cards', 'settings'], (t) => { t.put('cards', card); touched(t); });
  return card;
}

export async function saveCards(cards) {
  await write(['cards', 'settings'], (t) => { for (const c of cards) t.put('cards', c); touched(t); });
}

export async function deleteCard(card, state) {
  await write(['cards', 'reviews', 'images', 'settings'], (t) => {
    t.del('cards', card.id);
    if (card.frontImage) t.del('images', card.frontImage);
    if (card.backImage) t.del('images', card.backImage);
    for (const r of state.reviews) if (r.cardId === card.id) t.del('reviews', r.id);
    touched(t);
  });
}

/** Applies an answer. Returns what undo() needs to put the card back as it was. */
export async function rate(card, rating, state) {
  const today = toEpochDay();
  const next = applyRating(card, rating, today, examDateForCard(card, state, today));
  const log = { id: newId(), cardId: card.id, deckId: card.deckId, epochDay: today, rating, at: Date.now() };
  await write(['cards', 'reviews', 'settings'], (t) => {
    t.put('cards', next);
    t.put('reviews', log);
    touched(t);
  });
  state.reviews.push(log);
  const i = state.cards.findIndex((c) => c.id === card.id);
  if (i >= 0) state.cards[i] = next;
  return { before: card, after: next, logId: log.id };
}

export async function undo(token, state) {
  await write(['cards', 'reviews'], (t) => {
    t.put('cards', token.before);
    t.del('reviews', token.logId);
  });
  state.reviews = state.reviews.filter((r) => r.id !== token.logId);
  const i = state.cards.findIndex((c) => c.id === token.before.id);
  if (i >= 0) state.cards[i] = token.before;
}

export async function saveExam(exam) {
  const row = { createdAt: Date.now(), material: [], topics: '', deckIds: null, ...exam, id: exam.id ?? newId() };
  await write(['exams', 'settings'], (t) => { t.put('exams', row); touched(t); });
  return row;
}

export async function deleteExam(id, state) {
  await write(['exams', 'mockRuns', 'settings'], (t) => {
    t.del('exams', id);
    for (const r of state.mockRuns) if (r.examId === id) t.del('mockRuns', r.id);
    touched(t);
  });
}

/** Stores a finished mock exam and pulls what went wrong forward, so today starts with it. */
export async function saveMockRun(run, weakCardIds, state) {
  const today = toEpochDay();
  const row = { ...run, id: newId(), at: Date.now(), epochDay: today };
  await write(['mockRuns', 'cards', 'settings'], (t) => {
    t.put('mockRuns', row);
    for (const id of weakCardIds) {
      const c = state.cards.find((x) => x.id === id);
      if (c && c.dueEpochDay > today) t.put('cards', { ...c, dueEpochDay: today });
    }
    t.del('settings', 'activeMock');
    touched(t);
  });
  return row;
}

export async function logFocus(minutes) {
  if (minutes <= 0) return;
  await write(['focus', 'settings'], (t) => {
    t.put('focus', { id: newId(), at: Date.now(), epochDay: toEpochDay(), minutes });
    touched(t);
  });
}

// ---- Pictures ----------------------------------------------------------

export async function saveImage(mime, buffer) {
  const id = newId();
  await write('images', (t) => t.put('images', { id, mime, data: buffer }));
  return id;
}

export async function deleteImage(id) {
  await write('images', (t) => t.del('images', id));
}

const urlCache = new Map();

/** A blob URL for showing a picture; kept while the app runs because cards come back often. */
export async function imageUrl(id) {
  if (!id) return null;
  if (urlCache.has(id)) return urlCache.get(id);
  const row = await get('images', id);
  if (!row) return null;
  const url = URL.createObjectURL(new Blob([row.data], { type: row.mime }));
  urlCache.set(id, url);
  return url;
}

function dataUrlToImage(dataUrl) {
  const comma = dataUrl.indexOf(',');
  return { mime: dataUrl.slice(5, dataUrl.indexOf(';')), data: base64ToBuffer(dataUrl.slice(comma + 1)) };
}

export async function imageDataUrl(id) {
  const row = id ? await get('images', id) : null;
  return row ? `data:${row.mime};base64,${bufferToBase64(row.data)}` : null;
}

// ---- Packs -------------------------------------------------------------

export function previewPack(pack, state, options) {
  return planImport(pack, state, { today: toEpochDay(), newId, colorStart: state.decks.length, ...options });
}

export async function applyPack(pack, plan) {
  const images = plan.images.map((i) => ({ id: i.id, ...dataUrlToImage(i.dataUrl) }));
  await write(['decks', 'cards', 'images', 'packs', 'settings'], (t) => {
    for (const d of plan.deckPuts) t.put('decks', d);
    for (const c of plan.cardPuts) t.put('cards', c);
    for (const id of plan.cardDeletes) t.del('cards', id);
    for (const i of images) t.put('images', i);
    t.put('packs', {
      id: pack.id, titel: pack.titel, version: pack.version, beschreibung: pack.beschreibung, autor: pack.autor, lizenz: pack.lizenz,
      stand: pack.stand, beispiel: pack.beispiel, importedAt: Date.now(),
    });
    touched(t);
  });
}

/** Takes a pack away. keepCards turns its cards into own cards instead of deleting them. */
export async function removePack(packId, keepCards, state) {
  const decks = state.decks.filter((d) => d.packId === packId);
  const cards = state.cards.filter((c) => c.packId === packId);
  await write(['decks', 'cards', 'reviews', 'images', 'packs', 'settings'], (t) => {
    t.del('packs', packId);
    if (keepCards) {
      for (const d of decks) t.put('decks', { ...d, packId: null, packDeckKey: null });
      for (const c of cards) t.put('cards', { ...c, packId: null, packCardKey: null, packFront: null, packBack: null });
    } else {
      const deckIds = new Set(decks.map((d) => d.id));
      const ids = new Set(cards.map((c) => c.id));
      for (const c of cards) {
        t.del('cards', c.id);
        if (c.frontImage) t.del('images', c.frontImage);
        if (c.backImage) t.del('images', c.backImage);
      }
      for (const r of state.reviews) if (ids.has(r.cardId)) t.del('reviews', r.id);
      // A deck stays if the learner put own cards into it.
      for (const d of deckIds) if (!state.cards.some((c) => c.deckId === d && c.packId !== packId)) t.del('decks', d);
    }
    touched(t);
  });
}

// ---- Backup ------------------------------------------------------------

async function readStores() {
  const out = {};
  for (const s of STORES) out[s] = await getAll(s);
  out.images = out.images.map((i) => ({ id: i.id, mime: i.mime, base64: bufferToBase64(i.data) }));
  return out;
}

export async function makeBackup(appVersion) {
  return buildBackup(await readStores(), appVersion);
}

export const readForMerge = readStores;

/** Replaces all stored data; one transaction, so a broken run changes nothing. */
export async function replaceAll(data) {
  const images = (data.images ?? []).map((i) => ({ id: i.id, mime: i.mime, data: base64ToBuffer(i.base64) }));
  // Own device settings stay: the key is never in a file.
  const keyRow = await get('settings', 'claudeKey');
  await write(STORES, (t) => {
    for (const s of STORES) t.clear(s);
    for (const s of STORES) {
      if (s === 'images') continue;
      for (const row of data[s] ?? []) t.put(s, row);
    }
    for (const i of images) t.put('images', i);
    if (keyRow) t.put('settings', keyRow);
  });
}
