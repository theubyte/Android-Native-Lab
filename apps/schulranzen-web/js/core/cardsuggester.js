// Offline card proposals from plain text, as in CardSuggester.kt: definitions, term lines
// and lists under a heading. A heuristic: the learner confirms every card.
import { normalize } from './answercheck.js';
import { cloze } from './questions.js';
import { isDigit, isUpper, lines, trimEndChars } from './text.js';

export const SuggestionKind = Object.freeze({ DEFINITION: 'DEFINITION', TERM: 'TERM', LIST: 'LIST', GAP: 'GAP' });
export const MAX_SUGGESTIONS = 60;
export const MAX_TEXT_QUESTIONS = 40;

const BULLET = /^\s*(?:[-–•*·▪►]|\d{1,2}[.)]|[a-zA-Z][)])\s+(.+)$/u;
const TERM_LINE = /^\s*([^:]{2,60}?)\s*:\s+(.{2,})$/u;
const UNDERSTAND = /^[Uu]nter\s+(?:dem\s+Begriff\s+|einer?m?\s+|der\s+|dem\s+|den\s+)?(.{2,60}?)\s+versteht\s+man\s+(.{3,})$/u;
const CALLED_BY = /^(.{3,})\s+(?:nennt|bezeichnet)\s+man\s+(?:als\s+)?(.{2,60})$/u;
const DEFINITION = /^(.{2,70}?)\s+(ist|sind|bezeichnet|bedeutet|heißt|heißen|nennt\s+man)\s+(.{3,})$/u;

const PRONOUNS = new Set([
  'es', 'das', 'dies', 'dieses', 'diese', 'dieser', 'er', 'sie', 'man', 'hier', 'dann', 'damit', 'dabei',
  'dort', 'da', 'so', 'was', 'wer', 'wie', 'wo', 'jetzt', 'nun', 'heute', 'wichtig', 'außerdem', 'auch',
  'der', 'die', 'ein', 'eine', 'welche', 'welcher', 'alles', 'nichts', 'dazu', 'daher', 'deshalb',
]);
const NO_TERMS = new Set(['beispiel', 'z. b', 'hinweis', 'merke', 'achtung', 'aufgabe', 'lösung']);
const ABBREVIATIONS = new Set(['bzw', 'ca', 'evtl', 'ggf', 'inkl', 'max', 'min', 'usw', 'vgl', 'bspw', 'etc', 'nr', 'abb', 'kap', 'tab', 'zzgl', 'allg']);

const capitalize = (s) => { const t = s.trim(); return t.length === 0 ? t : t[0].toUpperCase() + t.slice(1); };

function lowerFirst(s) {
  const at = s.indexOf(' ');
  const first = at < 0 ? s : s.slice(0, at);
  // Nouns keep their capital letter; only articles are lowered.
  if (['der', 'die', 'das', 'ein', 'eine', 'einen'].includes(first.toLowerCase())) {
    return first.toLowerCase() + (at < 0 ? '' : ` ${s.slice(at + 1)}`);
  }
  return s;
}

function startsWithPronoun(s) {
  const words = s.trim().split(' ');
  const first = words[0].toLowerCase();
  // "Das Ventil" is fine, "Das" alone (or "Das ist") is not.
  if (['der', 'die', 'das', 'ein', 'eine', 'einer', 'einem'].includes(first) && words.length >= 2 && isUpper(words[1][0])) return false;
  return PRONOUNS.has(first);
}

function isAbbreviation(w) {
  const bare = trimEndChars(w, ['.']).toLowerCase();
  return bare.length === 1 || ABBREVIATIONS.has(bare) || [...bare].every(isDigit);
}

/** Splits prose into sentences without breaking "z. B." or "ca. 60 °C". */
export function sentences(text) {
  const out = [];
  let sb = '';
  const words = text.split(/\s+/).filter((w) => w.length > 0);
  words.forEach((w, k) => {
    if (sb.length > 0) sb += ' ';
    sb += w;
    const next = words[k + 1];
    const ends = (w.endsWith('.') || w.endsWith('!') || w.endsWith('?')) && !isAbbreviation(w);
    if (ends && next !== undefined && isUpper(next[0])) { out.push(sb); sb = ''; }
  });
  if (sb.length > 0) out.push(sb);
  return out;
}

function term(m) {
  const name = m[1].trim();
  const meaning = m[2].trim();
  // "Beispiel: Das Ventil ist …" is prose, not a term.
  if (name.split(/\s+/).length > 6 || NO_TERMS.has(name.toLowerCase())) return null;
  return { front: name, back: meaning, kind: SuggestionKind.TERM };
}

function definitionOf(sentence) {
  const s = trimEndChars(sentence.trim(), ['.', '!']).trim();
  if (s.endsWith('?') || s.length < 8) return null;
  const u = UNDERSTAND.exec(s);
  if (u) return { front: `Was versteht man unter ${u[1].trim()}?`, back: `${capitalize(u[2])}.`, kind: SuggestionKind.DEFINITION };
  const c = CALLED_BY.exec(s);
  if (c) {
    const what = c[1].trim();
    const name = c[2].trim();
    if (name.split(' ').length <= 5 && !startsWithPronoun(what)) {
      return { front: `Wie nennt man ${lowerFirst(what)}?`, back: `${capitalize(name)}.`, kind: SuggestionKind.DEFINITION };
    }
  }
  const m = DEFINITION.exec(s);
  if (!m) return null;
  const subject = trimEndChars(m[1].trim(), [',']);
  const verb = m[2].replace(/\s+/g, ' ');
  const rest = m[3].trim();
  if (startsWithPronoun(subject) || subject.split(' ').length > 7 || subject.includes(',')) return null;
  if (!isUpper(subject[0]) && !isDigit(subject[0])) return null;
  if (rest.split(' ').length < 2) return null;
  const subj = lowerFirst(subject);
  let front;
  if (verb === 'heißt' || verb === 'heißen') front = `Wie ${verb} ${subj}?`;
  else if (verb === 'nennt man') front = `Wie nennt man ${subj}?`;
  else front = `Was ${verb} ${subj}?`;
  return { front, back: `${capitalize(rest)}.`, kind: SuggestionKind.DEFINITION };
}

export function suggest(text) {
  const out = new Map();
  const add = (s) => {
    if (s === null) return;
    const front = s.front.trim();
    const back = s.back.trim();
    if (front.length < 2 || back.length < 2 || out.size >= MAX_SUGGESTIONS) return;
    const k = front.toLowerCase();
    if (!out.has(k)) out.set(k, { front, back, kind: s.kind });
  };

  const all = lines(text.split('\r').join(''));
  const usedByList = new Set();
  // Lists: a heading line followed by at least two bullet lines.
  let i = 0;
  while (i < all.length) {
    const head = all[i].trim();
    if (head.length > 0 && !BULLET.test(head) && head.length <= 90) {
      const items = [];
      let j = i + 1;
      while (j < all.length) {
        const m = BULLET.exec(all[j]);
        if (!m) break;
        items.push(m[1].trim());
        j++;
      }
      if (items.length >= 2) {
        const topic = trimEndChars(head, [':', ' ', '.']);
        add({ front: `${topic} – nenne die ${items.length} Punkte.`, back: items.map((x) => `• ${x}`).join('\n'), kind: SuggestionKind.LIST });
        for (let k = i; k < j; k++) usedByList.add(k);
        i = j;
        continue;
      }
    }
    i++;
  }

  // Term lines and definitions, sentence by sentence.
  all.forEach((raw, idx) => {
    const bullet = BULLET.exec(raw);
    if (usedByList.has(idx)) {
      // A bullet may itself be "Term: meaning"; that is worth its own card.
      if (bullet) {
        const t = TERM_LINE.exec(bullet[1]);
        if (t) add(term(t));
      }
      return;
    }
    const line = (bullet ? bullet[1] : raw).trim();
    if (line.length === 0) return;
    const t = TERM_LINE.exec(line);
    if (t) {
      const tc = term(t);
      if (tc !== null) { add(tc); return; }
    }
    for (const s of sentences(line)) add(definitionOf(s));
  });
  return [...out.values()];
}

/**
 * Exam questions straight from a text: suggest() plus gap sentences for statements carrying
 * a value or a defined term. Only text the learner brought goes in.
 */
export function fromText(text) {
  const out = new Map();
  for (const s of suggest(text)) { const k = normalize(s.front); if (!out.has(k)) out.set(k, s); }
  const backs = [...out.values()].map((s) => normalize(s.back));
  for (const line of lines(text.split('\r').join(''))) {
    for (const sentence of sentences(line.trim())) {
      if (out.size >= MAX_TEXT_QUESTIONS) return [...out.values()];
      const s = sentence.trim();
      const words = s.split(/\s+/);
      if (words.length < 6 || words.length > 30 || s.endsWith('?') || s.includes(':')) continue;
      // Only statements worth asking: with a number, or with a capitalised term beyond the start.
      const hasValue = [...s].some(isDigit);
      const hasTerm = words.slice(1).some((w) => w.length >= 6 && isUpper(w[0]));
      if (!hasValue && !hasTerm) continue;
      if (backs.some((b) => b.length > 0 && b === normalize(s))) continue;
      const gap = cloze(s);
      if (gap === null) continue;
      const k = normalize(gap.text);
      if (!out.has(k)) out.set(k, { front: `Ergänze: ${gap.text}`, back: gap.answer, kind: SuggestionKind.GAP });
    }
  }
  return [...out.values()];
}

