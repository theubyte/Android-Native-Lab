// The full backup file and merging two states, with the rules of StudyMerge.kt: decks by
// name, exams by subject + date + kind, cards by deck + front; the card reviewed later keeps
// its schedule. Nothing is deleted: what exists only here stays.
export const BACKUP_FORMAT = 'schulranzen-sicherung';
export const BACKUP_VERSION = 1;
export const STORES = ['decks', 'cards', 'exams', 'reviews', 'mockRuns', 'focus', 'packs', 'images', 'settings'];

/** Settings that must never leave the device. */
export const PRIVATE_SETTINGS = new Set(['claudeKey']);

export function bufferToBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  let out = '';
  const STEP = 0x8000;
  for (let i = 0; i < bytes.length; i += STEP) out += String.fromCharCode.apply(null, bytes.subarray(i, i + STEP));
  return btoa(out);
}

export function base64ToBuffer(text) {
  const raw = atob(text);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes.buffer;
}

/** [data]: one array per store; images already as { id, mime, base64 }. */
export function buildBackup(data, appVersion, now = Date.now()) {
  const out = { format: BACKUP_FORMAT, formatVersion: BACKUP_VERSION, appVersion, createdAt: now, data: {} };
  for (const s of STORES) out.data[s] = data[s] ?? [];
  out.data.settings = out.data.settings.filter((x) => !PRIVATE_SETTINGS.has(x.key));
  return out;
}

const VALID_ID = (x) => typeof x === 'string' || typeof x === 'number';

/** Checks a backup file. Returns { backup, errors }; a broken file never touches the data. */
export function parseBackup(text) {
  let raw;
  try {
    raw = JSON.parse(String(text).replace(/^﻿/, ''));
  } catch {
    return { backup: null, errors: ['Die Datei ist kein gültiges JSON.'] };
  }
  if (!raw || raw.format !== BACKUP_FORMAT) return { backup: null, errors: ['Das ist keine Sicherung dieser App.'] };
  if (raw.formatVersion !== BACKUP_VERSION) return { backup: null, errors: [`Sicherung mit Formatversion ${raw.formatVersion}: bitte die App aktualisieren.`] };
  const errors = [];
  if (!raw.data || typeof raw.data !== 'object') errors.push('Daten fehlen.');
  else {
    for (const s of STORES) {
      if (raw.data[s] === undefined) raw.data[s] = [];
      if (!Array.isArray(raw.data[s])) errors.push(`Bereich "${s}" ist beschädigt.`);
    }
    const decks = new Set((raw.data.decks || []).map((d) => d.id));
    if (!(raw.data.decks || []).every((d) => VALID_ID(d?.id) && typeof d.name === 'string')) errors.push('Stapel sind beschädigt.');
    for (const c of raw.data.cards || []) {
      if (!VALID_ID(c?.id) || !decks.has(c.deckId) || typeof c.front !== 'string' || typeof c.back !== 'string' || typeof c.ease !== 'number' || !Number.isFinite(c.dueEpochDay)) {
        errors.push('Karten sind beschädigt.');
        break;
      }
    }
    if (!(raw.data.images || []).every((i) => VALID_ID(i?.id) && typeof i.base64 === 'string' && /^image\/(png|jpeg|webp)$/.test(i.mime))) errors.push('Bilder sind beschädigt.');
    raw.data.settings = (raw.data.settings || []).filter((x) => x && !PRIVATE_SETTINGS.has(x.key));
  }
  return errors.length > 0 ? { backup: null, errors } : { backup: raw, errors };
}

const low = (s) => String(s ?? '').trim().toLowerCase();
const imageKey = (c) => (c.frontImage ? 'b' : '');

/**
 * Merges [remote] into [local]; both hold one array per store. Returns the complete new
 * state and the counts. Ids of remote rows are mapped onto the local rows they match.
 */
export function merge(local, remote) {
  const out = {};
  for (const s of STORES) out[s] = (local[s] ?? []).map((x) => ({ ...x }));
  let added = 0;
  let updated = 0;
  let kept = 0;

  const ids = (store) => new Set(out[store].map((x) => x.id));
  const freshId = (store, id, taken) => {
    let next = id;
    let n = 1;
    while (taken.has(next)) next = `${id}-${n++}`;
    taken.add(next);
    return next;
  };

  for (const s of remote.settings ?? []) {
    if (PRIVATE_SETTINGS.has(s.key)) continue;
    if (!out.settings.some((x) => x.key === s.key)) { out.settings.push({ ...s }); added++; }
  }

  for (const p of remote.packs ?? []) {
    const same = out.packs.find((x) => x.id === p.id);
    if (!same) { out.packs.push({ ...p }); added++; }
  }

  const imageIds = ids('images');
  const imageMap = new Map();
  const remoteImages = new Map((remote.images ?? []).map((i) => [i.id, i]));
  const bringImage = (id) => {
    if (!id) return null;
    if (imageMap.has(id)) return imageMap.get(id);
    const img = remoteImages.get(id);
    if (!img) return null;
    const same = out.images.find((x) => x.base64 === img.base64);
    const mapped = same ? same.id : freshId('images', id, imageIds);
    if (!same) out.images.push({ ...img, id: mapped });
    imageMap.set(id, mapped);
    return mapped;
  };

  const deckIds = ids('decks');
  const deckMap = new Map();
  for (const d of remote.decks ?? []) {
    const same = out.decks.find((x) => (d.packId && x.packId === d.packId && x.packDeckKey === d.packDeckKey) || low(x.name) === low(d.name));
    if (same) { deckMap.set(d.id, same.id); kept++; } else {
      const id = freshId('decks', d.id, deckIds);
      out.decks.push({ ...d, id });
      deckMap.set(d.id, id);
      added++;
    }
  }

  const examIds = ids('exams');
  const examMap = new Map();
  const mappedDecks = (list) => (Array.isArray(list) ? list.map((x) => deckMap.get(x)).filter((x) => x !== undefined) : list ?? null);
  for (const e of remote.exams ?? []) {
    const key = (x) => `${low(x.subject)}|${x.dateEpochDay}|${x.kind}`;
    const same = out.exams.find((x) => key(x) === key(e));
    if (same) {
      examMap.set(e.id, same.id);
      // Material worked through on either device counts as done.
      for (const m of e.material ?? []) {
        const mine = (same.material ?? []).find((x) => low(x.label) === low(m.label));
        if (!mine) { same.material = [...(same.material ?? []), { ...m }]; updated++; } else if (!mine.doneAt && m.doneAt) { mine.doneAt = m.doneAt; updated++; }
      }
      kept++;
    } else {
      const id = freshId('exams', e.id, examIds);
      out.exams.push({ ...e, id, deckIds: mappedDecks(e.deckIds), material: (e.material ?? []).map((m) => ({ ...m })) });
      examMap.set(e.id, id);
      added++;
    }
  }

  const cardIds = ids('cards');
  const cardMap = new Map();
  const cardKey = (deckId, c) => `${deckId}|${low(c.front)}|${imageKey(c)}`;
  const index = new Map(out.cards.map((c) => [cardKey(c.deckId, c), c]));
  for (const c of remote.cards ?? []) {
    const deck = deckMap.get(c.deckId);
    if (deck === undefined) continue;
    const key = cardKey(deck, c);
    const same = index.get(key) ?? out.cards.find((x) => c.packCardKey && x.packId === c.packId && x.packCardKey === c.packCardKey);
    if (same) {
      cardMap.set(c.id, same.id);
      const mine = same.lastReviewEpochDay ?? Number.MIN_SAFE_INTEGER;
      const theirs = c.lastReviewEpochDay ?? Number.MIN_SAFE_INTEGER;
      if (theirs > mine || (theirs === mine && c.reps > same.reps)) {
        same.reps = c.reps; same.intervalDays = c.intervalDays; same.ease = c.ease; same.lapses = c.lapses;
        same.dueEpochDay = c.dueEpochDay; same.lastReviewEpochDay = c.lastReviewEpochDay ?? null;
        updated++;
      } else kept++;
    } else {
      const id = freshId('cards', c.id, cardIds);
      const next = { ...c, id, deckId: deck, frontImage: bringImage(c.frontImage), backImage: bringImage(c.backImage) };
      out.cards.push(next);
      index.set(key, next);
      cardMap.set(c.id, id);
      added++;
    }
  }

  const reviewIds = ids('reviews');
  for (const r of remote.reviews ?? []) {
    const card = cardMap.get(r.cardId);
    if (card === undefined) continue;
    if (out.reviews.some((x) => x.cardId === card && x.at === r.at)) continue;
    out.reviews.push({ ...r, id: freshId('reviews', r.id, reviewIds), cardId: card, deckId: deckMap.get(r.deckId) ?? r.deckId });
  }

  const runIds = ids('mockRuns');
  for (const m of remote.mockRuns ?? []) {
    const exam = examMap.get(m.examId);
    if (exam === undefined) continue;
    if (out.mockRuns.some((x) => x.examId === exam && x.at === m.at)) continue;
    out.mockRuns.push({
      ...m, id: freshId('mockRuns', m.id, runIds), examId: exam,
      answers: (m.answers ?? []).map((a) => ({ ...a, cardId: cardMap.get(a.cardId) ?? null })),
    });
    added++;
  }

  const focusIds = ids('focus');
  for (const f of remote.focus ?? []) {
    if (out.focus.some((x) => x.at === f.at)) continue;
    out.focus.push({ ...f, id: freshId('focus', f.id, focusIds) });
    added++;
  }

  return { data: out, added, updated, kept };
}
