// Content packs: JSON files with decks and cards, shared by the Android app and this one.
// The format is described in docs/INHALTSPAKET.md. This module only checks files and works
// out what an import would do; nothing is stored here, so the preview shows the exact truth.
import { normalize } from './answercheck.js';

export const FORMAT = 'schulranzen-inhaltspaket';
export const FORMAT_VERSION = 1;
export const MAX_IMAGE_CHARS = 4 * 1024 * 1024;
const IMAGE_RE = /^data:image\/(png|jpeg|webp);base64,[A-Za-z0-9+/]+={0,2}$/;

const isText = (v) => typeof v === 'string';
const clean = (v) => (isText(v) ? v.trim() : '');

/** Small stable hash for cards that came without an id. */
function hash(text) {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(36);
}

function checkImage(value, where, errors) {
  if (value === undefined || value === null || value === '') return null;
  if (!isText(value) || !IMAGE_RE.test(value)) {
    errors.push(`${where}: Bild muss eine data-URL mit PNG, JPEG oder WebP sein.`);
    return null;
  }
  if (value.length > MAX_IMAGE_CHARS) {
    errors.push(`${where}: Bild ist zu groß (höchstens 3 MB).`);
    return null;
  }
  return value;
}

/**
 * Reads a pack from text or a parsed object. Returns { pack, errors, warnings }; [pack] is
 * null when errors exist. A broken file never reaches the database.
 */
export function parsePack(input) {
  const errors = [];
  const warnings = [];
  let raw = input;
  if (isText(input)) {
    try {
      raw = JSON.parse(input.replace(/^﻿/, ''));
    } catch {
      return { pack: null, errors: ['Die Datei ist kein gültiges JSON.'], warnings };
    }
  }
  if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) return { pack: null, errors: ['Die Datei enthält kein Inhaltspaket.'], warnings };
  if (raw.format !== FORMAT) return { pack: null, errors: [`Das ist kein Inhaltspaket (Feld "format" muss "${FORMAT}" sein).`], warnings };
  if (raw.formatVersion !== FORMAT_VERSION) {
    return { pack: null, errors: [`Formatversion ${raw.formatVersion} kennt diese App nicht. Bitte die App aktualisieren.`], warnings };
  }
  if (!clean(raw.id)) errors.push('Feld "id" fehlt.');
  if (!clean(raw.titel)) errors.push('Feld "titel" fehlt.');
  if (!clean(String(raw.version ?? ''))) errors.push('Feld "version" fehlt.');
  if (!Array.isArray(raw.stapel) || raw.stapel.length === 0) errors.push('Das Paket enthält keine Stapel.');
  if (errors.length > 0) return { pack: null, errors, warnings };

  const deckIds = new Set();
  let derived = 0;
  let noStatus = 0;
  const stapel = [];
  raw.stapel.forEach((d, di) => {
    const where = `Stapel ${di + 1}`;
    if (d === null || typeof d !== 'object') { errors.push(`${where}: kein Objekt.`); return; }
    const id = clean(d.id);
    const name = clean(d.name);
    if (!id) errors.push(`${where}: Feld "id" fehlt.`);
    if (!name) errors.push(`${where}: Feld "name" fehlt.`);
    if (id && deckIds.has(id)) errors.push(`${where}: id "${id}" kommt doppelt vor.`);
    deckIds.add(id);
    if (!Array.isArray(d.karten)) { errors.push(`${where}: Feld "karten" fehlt.`); return; }
    const cardIds = new Set();
    const karten = [];
    d.karten.forEach((k, ki) => {
      const at = `${where}, Karte ${ki + 1}`;
      if (k === null || typeof k !== 'object') { errors.push(`${at}: kein Objekt.`); return; }
      const vorne = clean(k.vorne);
      const hinten = clean(k.hinten);
      const vorneBild = checkImage(k.vorneBild, at, errors);
      const hintenBild = checkImage(k.hintenBild, at, errors);
      if (!vorne && !vorneBild) errors.push(`${at}: Vorderseite fehlt.`);
      if (!hinten && !hintenBild) errors.push(`${at}: Rückseite fehlt.`);
      let cid = clean(k.id);
      if (!cid) { cid = `auto-${hash(normalize(vorne))}`; derived++; }
      if (cardIds.has(cid)) errors.push(`${at}: id "${cid}" kommt doppelt vor.`);
      cardIds.add(cid);
      if (typeof k['geprüft'] !== 'boolean') noStatus++;
      const q = k.quelle && typeof k.quelle === 'object' ? k.quelle : d.quelle && typeof d.quelle === 'object' ? d.quelle : {};
      karten.push({
        id: cid, vorne, hinten, vorneBild, hintenBild,
        thema: clean(k.thema) || clean(d.thema),
        quelle: { buch: clean(q.buch), seite: clean(String(q.seite ?? '')) },
        'geprüft': k['geprüft'] === true,
      });
    });
    stapel.push({ id, name, fach: clean(d.fach), thema: clean(d.thema), karten });
  });
  if (derived > 0) warnings.push(`${derived} Karten haben keine eigene id. Ändert sich ihre Vorderseite, gilt der Lernfortschritt als neue Karte.`);
  if (noStatus > 0) warnings.push(`${noStatus} Karten haben keinen Prüfstatus und gelten als ungeprüft.`);
  if (errors.length > 0) return { pack: null, errors, warnings };
  return {
    pack: {
      format: FORMAT, formatVersion: FORMAT_VERSION,
      id: clean(raw.id), titel: clean(raw.titel), version: String(raw.version).trim(),
      beschreibung: clean(raw.beschreibung), autor: clean(raw.autor), lizenz: clean(raw.lizenz), stand: clean(raw.stand),
      beispiel: raw.beispiel === true, stapel,
    },
    errors,
    warnings,
  };
}

/** Compares "1.10.0" and "1.9": numbers count as numbers. Negative when a is older. */
export function compareVersions(a, b) {
  return String(a).localeCompare(String(b), 'de', { numeric: true });
}

/**
 * What importing [pack] would do. existing: { decks, cards } as stored. options:
 * { removeMissing: delete cards the new version no longer has (else they become own cards),
 *   skipUnverified: leave out new cards with geprüft=false, today, newId, colorStart }.
 * Learning progress is never touched: the schedule fields of a known card are copied as they
 * are, whatever else changes. Returns { summary, deckPuts, cardPuts, cardDeletes, images }.
 */
export function planImport(pack, existing, options) {
  const { today, newId, removeMissing = false, skipUnverified = false, colorStart = 0, now = Date.now() } = options;
  const myDecks = existing.decks.filter((d) => d.packId === pack.id);
  const myCards = existing.cards.filter((c) => c.packId === pack.id);
  const summary = {
    isUpdate: myDecks.length > 0, newDecks: 0, newCards: 0, changedCards: 0, unchangedCards: 0,
    keptOwnEdits: 0, removedCards: 0, removedWithProgress: 0, skippedUnverified: 0, unverifiedCards: 0, totalCards: 0,
  };
  const deckPuts = [];
  const cardPuts = [];
  const images = [];
  const seenCardKeys = new Set();
  let color = colorStart;

  const imageFor = (dataUrl) => {
    if (!dataUrl) return null;
    const id = newId();
    images.push({ id, dataUrl });
    return id;
  };

  for (const d of pack.stapel) {
    let deck = myDecks.find((x) => x.packDeckKey === d.id);
    if (deck) {
      deckPuts.push({ ...deck, name: d.name, subject: d.fach || deck.subject || '' });
    } else {
      summary.newDecks++;
      deck = { id: newId(), name: d.name, subject: d.fach, colorIndex: color++, packId: pack.id, packDeckKey: d.id, createdAt: now };
      deckPuts.push(deck);
    }
    for (const k of d.karten) {
      summary.totalCards++;
      if (!k['geprüft']) summary.unverifiedCards++;
      const key = `${d.id}/${k.id}`;
      seenCardKeys.add(key);
      const old = myCards.find((c) => c.packCardKey === key);
      if (!old) {
        if (skipUnverified && !k['geprüft']) { summary.skippedUnverified++; continue; }
        summary.newCards++;
        cardPuts.push({
          id: newId(), deckId: deck.id, front: k.vorne, back: k.hinten,
          frontImage: imageFor(k.vorneBild), backImage: imageFor(k.hintenBild),
          topic: k.thema, source: k.quelle, verified: k['geprüft'],
          packId: pack.id, packCardKey: key, packFront: k.vorne, packBack: k.hinten,
          reps: 0, intervalDays: 0, ease: 2.5, lapses: 0, dueEpochDay: today, lastReviewEpochDay: null,
          // One apart, so the cards keep the order of the file.
          createdAt: now + summary.totalCards,
        });
        continue;
      }
      const packChanged = old.packFront !== k.vorne || old.packBack !== k.hinten;
      const ownEdit = old.front !== old.packFront || old.back !== old.packBack;
      const next = { ...old, deckId: deck.id, topic: k.thema, source: k.quelle, packFront: k.vorne, packBack: k.hinten };
      if (packChanged && !ownEdit) {
        next.front = k.vorne;
        next.back = k.hinten;
        next.verified = k['geprüft'];
        summary.changedCards++;
      } else if (packChanged && ownEdit) {
        // The learner's own wording wins; the new text stays visible as the baseline.
        summary.keptOwnEdits++;
      } else {
        // Same text: a learner's own "geprüft" stays, the pack may only add one.
        next.verified = old.verified === true || k['geprüft'];
        if (old.verified !== next.verified || old.topic !== next.topic || JSON.stringify(old.source ?? null) !== JSON.stringify(next.source)) summary.changedCards++;
        else summary.unchangedCards++;
      }
      cardPuts.push(next);
    }
  }

  const cardDeletes = [];
  for (const c of myCards) {
    if (seenCardKeys.has(c.packCardKey)) continue;
    summary.removedCards++;
    if (c.lastReviewEpochDay !== null && c.lastReviewEpochDay !== undefined) summary.removedWithProgress++;
    if (removeMissing) {
      cardDeletes.push(c.id);
    } else {
      cardPuts.push({ ...c, packId: null, packCardKey: null, packFront: null, packBack: null });
    }
  }
  return { summary, deckPuts, cardPuts, cardDeletes, images };
}

/** A deck as a pack, so own cards can be passed on or read by the Android app. */
export function exportPack(meta, decks, cards) {
  return {
    format: FORMAT,
    formatVersion: FORMAT_VERSION,
    id: meta.id,
    titel: meta.titel,
    version: meta.version ?? '1',
    beschreibung: meta.beschreibung ?? '',
    stapel: decks.map((d) => ({
      id: d.packDeckKey || d.id,
      name: d.name,
      fach: d.subject || '',
      karten: cards.filter((c) => c.deckId === d.id).map((c) => {
        const k = { id: c.packCardKey ? c.packCardKey.split('/').pop() : c.id, vorne: c.front, hinten: c.back };
        if (c.topic) k.thema = c.topic;
        if (c.source && (c.source.buch || c.source.seite)) k.quelle = c.source;
        k['geprüft'] = c.verified === true;
        if (c.frontImageData) k.vorneBild = c.frontImageData;
        if (c.backImageData) k.hintenBild = c.backImageData;
        return k;
      }),
    })),
  };
}
