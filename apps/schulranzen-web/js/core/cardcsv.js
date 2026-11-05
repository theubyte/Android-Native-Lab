// Flash cards as text, as in CardCsv.kt: one card per line, front and back separated by a
// tab, optional #separator:, #html:, #columns:, #deck column:, #tags column: headers, double
// quotes around fields with line breaks. Plain CSV with semicolon or comma works too.
// Scheduling is not part of the format: imported cards start new.
import { lines } from './text.js';

const HEADER = /^#\s*([\w ]+?)\s*:\s*(.*)$/;
const MEDIA = /\[sound:[^\]]*\]|<img\b[^>]*>/gi;
const BREAK = /<br\s*\/?>|<\/div>|<\/p>|<\/li>/gi;
const TAG = /<[^>]+>/g;
const LOOKS_HTML = /<\/?(?:br|div|p|b|i|u|span|ul|ol|li)\b[^>]*>/i;

const SEPARATORS = { tab: '\t', comma: ',', semicolon: ';', pipe: '|', space: ' ', colon: ':' };
const FRONT_NAMES = new Set(['front', 'vorderseite', 'frage', 'question']);
const BACK_NAMES = new Set(['back', 'rückseite', 'antwort', 'answer']);

const hasMedia = (s) => { MEDIA.lastIndex = 0; return MEDIA.test(s); };

function cleanField(raw, html) {
  let s = raw.replace(MEDIA, ' ');
  if (html) {
    s = s.replace(BREAK, '\n').replace(TAG, '');
    s = s.split('&nbsp;').join(' ').split('&lt;').join('<').split('&gt;').join('>')
      .split('&quot;').join('"').split('&#39;').join("'").split('&amp;').join('&');
  }
  return lines(s).map((l) => l.replace(/ {2,}/g, ' ').trim()).join('\n').trim();
}

/** The separator that gives the steadiest number of fields; tabs win a tie. */
function guessDelimiter(body) {
  // Separators inside quoted fields say nothing about the format.
  const outside = body.replace(/"(?:[^"]|"")*"/g, '');
  const sample = lines(outside).filter((l) => l.trim().length > 0).slice(0, 20);
  if (sample.some((l) => l.includes('\t'))) return '\t';
  const semi = sample.filter((l) => l.includes(';')).length;
  const comma = sample.filter((l) => l.includes(',')).length;
  if (semi >= comma && semi > 0) return ';';
  return comma > 0 ? ',' : '\t';
}

/** RFC 4180 style: quotes around a field may hold separators and line breaks; "" is a quote. */
function rows(body, sep) {
  const out = [];
  let row = [];
  let field = '';
  let quoted = false;
  for (let i = 0; i < body.length; i++) {
    const ch = body[i];
    if (quoted && ch === '"' && body[i + 1] === '"') { field += '"'; i++; }
    else if (quoted && ch === '"') quoted = false;
    else if (quoted) field += ch;
    else if (ch === '"' && field.length === 0) quoted = true;
    else if (ch === sep) { row.push(field); field = ''; }
    else if (ch === '\n') { row.push(field); field = ''; out.push(row); row = []; }
    else field += ch;
  }
  if (field.length > 0 || row.length > 0) { row.push(field); out.push(row); }
  return out;
}

/** { cards: [{front, back, deck, tags}], skipped, mediaDropped, delimiter } */
export function parse(text) {
  const clean = text.replace(/^﻿/, '').split('\r\n').join('\n').split('\r').join('\n');
  let delimiter = null;
  let html = null;
  let deckCol = -1;
  let tagsCol = -1;
  let frontCol = 0;
  let backCol = 1;

  // Headers come first; they may name the columns.
  const all = clean.split('\n');
  let start = 0;
  for (; start < all.length; start++) {
    const line = all[start];
    if (!line.startsWith('#')) break;
    const m = HEADER.exec(line);
    if (!m) continue;
    const key = m[1].trim().toLowerCase();
    const value = m[2].trim();
    if (key === 'separator') delimiter = SEPARATORS[value.toLowerCase()] ?? (value[0] ?? null);
    else if (key === 'html') html = value.toLowerCase() === 'true';
    else if (key === 'deck column') deckCol = (parseInt(value, 10) || 0) - 1;
    else if (key === 'tags column') tagsCol = (parseInt(value, 10) || 0) - 1;
    else if (key === 'columns') {
      const names = value.split(/[\t,;]/).map((n) => n.trim().toLowerCase());
      const f = names.findIndex((n) => FRONT_NAMES.has(n));
      if (f >= 0) frontCol = f;
      const b = names.findIndex((n) => BACK_NAMES.has(n));
      if (b >= 0) backCol = b;
    }
  }
  const body = all.slice(start).join('\n');
  const sep = delimiter ?? guessDelimiter(body);
  let skipped = 0;
  let media = 0;
  const cards = [];
  for (const row of rows(body, sep)) {
    if (row.length === 1 && row[0].trim().length === 0) continue;
    if (row.length === 1 && row[0].trimStart().startsWith('#')) continue;
    const rawFront = row[frontCol];
    const rawBack = row[backCol];
    if (rawFront === undefined || rawBack === undefined) { skipped++; continue; }
    if (hasMedia(rawFront) || hasMedia(rawBack)) media++;
    const useHtml = html ?? (LOOKS_HTML.test(rawFront) || LOOKS_HTML.test(rawBack));
    const front = cleanField(rawFront, useHtml);
    const back = cleanField(rawBack, useHtml);
    if (front.length === 0) { skipped++; continue; }
    const deck = deckCol >= 0 ? (row[deckCol] ?? '').trim() : '';
    cards.push({ front, back, deck: deck.length > 0 ? deck : null, tags: tagsCol >= 0 ? (row[tagsCol] ?? '').trim() : '' });
  }
  return { cards, skipped, mediaDropped: media, delimiter: sep };
}

const TITLES = new Set(['front', 'back', 'vorderseite', 'rückseite', 'frage', 'antwort', 'question', 'answer']);

/** A known header row like "Frage;Antwort" is not a card. */
export function dropTitleRow(cards) {
  if (cards.length === 0) return cards;
  const first = cards[0];
  return TITLES.has(first.front.toLowerCase()) && TITLES.has(first.back.toLowerCase()) ? cards.slice(1) : cards;
}

function escape(s, sep) {
  return s.includes(sep) || s.includes('"') || s.includes('\n') || s.includes('\r') ? `"${s.split('"').join('""')}"` : s;
}

/** Tab-separated text that the usual card programs read back; [cards]: {front, back}. */
export function write(cards) {
  let out = '#separator:tab\n#html:false\n#columns:Front\tBack\n';
  for (const c of cards) out += `${escape(c.front, '\t')}\t${escape(c.back, '\t')}\n`;
  return out;
}

/** Excel-friendly variant: semicolons and a byte order mark, as German Excel expects. */
export function writeCsv(cards) {
  let out = '﻿Frage;Antwort\r\n';
  for (const c of cards) out += `${escape(c.front, ';')};${escape(c.back, ';')}\r\n`;
  return out;
}
