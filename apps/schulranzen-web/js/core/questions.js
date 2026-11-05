// Builds questions from a learner's own cards, as in Questions.kt. Nothing is invented:
// distractors are answers of other cards in the same deck, gaps are cut from the card's text.
// A quiz card is { id, front, back, hasImage }.
import { check, canCheck, normalize, gradeOf, SelfGrade } from './answercheck.js';
import { stem, stems } from './mockexam.js';
import { isDigit, isLetter, isUpper, maxBy, minBy, shuffled, trimChars } from './text.js';

export const QuestionType = Object.freeze({ FLIP: 'FLIP', TYPE: 'TYPE', CHOICE: 'CHOICE', CLOZE: 'CLOZE', MATCH: 'MATCH' });

export const BLANK = '_____';
export const MATCH_EVERY = 8;
export const MATCH_SIZE = 4;
const MAX_CHOICE_LENGTH = 90;
const MAX_MATCH_LENGTH = 60;

const hasDigit = (s) => [...s].some(isDigit);

function tail(s) {
  const t = s.trim();
  return t.slice(t.lastIndexOf(' ') + 1).toLowerCase();
}

/** Lower is more alike. The sort is stable, which keeps the shuffle among equals. */
function similarity(a, b) {
  const numA = hasDigit(a);
  const numB = hasDigit(b);
  const kind = numA === numB ? 0 : 100;
  const unit = numA && numB && tail(a) !== tail(b) ? 20 : 0;
  const multi = a.includes('\n') === b.includes('\n') ? 0 : 50;
  return kind + unit + multi + Math.trunc(Math.abs(a.length - b.length) / 8);
}

/**
 * Up to three distractors from [pool]: other cards' answers, most similar in kind first,
 * never one that would also be correct. Null with fewer than two.
 */
export function choice(card, pool, random = Math.random) {
  const right = card.back.trim();
  if (card.hasImage || right.length === 0 || right.length > MAX_CHOICE_LENGTH) return null;
  const seen = new Set([normalize(right)]);
  const candidates = [];
  for (const c of pool) {
    if (c.id === card.id || c.hasImage) continue;
    const back = c.back.trim();
    if (back.length === 0 || back.length > MAX_CHOICE_LENGTH) continue;
    const k = normalize(back);
    if (k.length === 0 || seen.has(k)) continue;
    seen.add(k);
    // Near-spellings of the right answer would be correct under the tolerant check.
    if (gradeOf(check(back, right).verdict) !== SelfGrade.RIGHT) candidates.push(back);
  }
  const distractors = shuffled(candidates, random).map((s) => ({ s, k: similarity(right, s) }))
    .sort((a, b) => a.k - b.k).slice(0, 3).map((x) => x.s);
  if (distractors.length < 2) return null;
  const options = shuffled([...distractors, right], random);
  return { type: QuestionType.CHOICE, card, cards: [card], options, correct: options.indexOf(right) };
}

const CLOZE_MARK = /\{\{c\d+::(.+?)(?:::[^}]*)?\}\}/gu;
// The Android pattern ends in a word boundary; JS \b only knows ASCII, so it would stop
// inside "Stück". The lookahead asks the same question for all letters.
const VALUE = '\\d(?:[\\d.,]*\\d)?(?:\\s?(?:°\\s?C|°|%|[\\p{L}]{1,4}(?:/[\\p{L}²³\\d]+)?[²³]?(?![\\p{L}\\p{N}_])))?';
const BEFORE_VALUE = /[\p{L}\d.,]/u;

function findValue(text) {
  const re = new RegExp(VALUE, 'gu');
  let m;
  while ((m = re.exec(text)) !== null) {
    const before = m.index > 0 ? text[m.index - 1] : '';
    if (before && BEFORE_VALUE.test(before)) { re.lastIndex = m.index + 1; continue; }
    return m;
  }
  return null;
}

const COMMON = new Set([
  'werden', 'wurde', 'worden', 'haben', 'hatte', 'sollte', 'sollen', 'können', 'müssen', 'nicht', 'diese', 'dieser', 'dieses',
  'einem', 'einer', 'eines', 'durch', 'immer', 'dabei', 'sowie', 'außerdem', 'welche', 'welcher', 'welches',
  'zwischen', 'während', 'gegen', 'unter', 'wichtig', 'mindestens', 'höchstens',
]);
const PUNCT = ['.', ',', ';', ':', '!', '?', '(', ')', '„', '“', '"'];

/** A gap in [text]: marked with {{c1::…}} if the card has one, otherwise the best term. */
export function cloze(text, avoid = '') {
  CLOZE_MARK.lastIndex = 0;
  const marked = CLOZE_MARK.exec(text);
  if (marked !== null) {
    const at = marked.index;
    const filled = text.replace(CLOZE_MARK, (all, inner, offset) => (offset === at ? BLANK : inner));
    return { text: filled, answer: marked[1].trim() };
  }
  const clean = text.trim().split('\n').join(' ');
  const words = clean.split(/\s+/);
  if (words.length < 4 || words.length > 45) return null;
  // Values are what exams ask: "bei 60 °C", "mindestens 1,5 bar".
  const value = findValue(clean);
  if (value !== null) {
    return { text: clean.slice(0, value.index) + BLANK + clean.slice(value.index + value[0].length), answer: value[0].trim() };
  }
  const avoidStems = stems(avoid);
  const candidates = words.map((word, index) => ({ index, word, bare: trimChars(word, PUNCT) }))
    .filter((x) => x.bare.length >= 5 && [...x.bare].every((ch) => isLetter(ch) || ch === '-') && !COMMON.has(x.bare.toLowerCase()))
    .filter((x) => !avoidStems.has(stem(x.bare.toLowerCase())));
  // Nouns carry the fact; the sentence start is capitalised anyway.
  const best = maxBy(candidates, (x) => (isUpper(x.bare[0]) && x.index > 0 ? 100 : 0) + x.bare.length);
  if (best === null) return null;
  const out = words.slice();
  out[best.index] = best.word.split(best.bare).join(BLANK);
  return { text: out.join(' '), answer: best.bare };
}

export function clozeQuestion(card) {
  if (card.hasImage) return null;
  // A single word on the back is a typing question, not a gap.
  const c = cloze(card.back, card.front);
  if (c === null || !canCheck(c.answer)) return null;
  return { type: QuestionType.CLOZE, card, cards: [card], text: c.text, answer: c.answer };
}

export function canMatch(c) {
  return !c.hasImage && c.front.trim().length > 0 && c.back.trim().length > 0
    && c.front.length <= MAX_MATCH_LENGTH && c.back.length <= MAX_MATCH_LENGTH && !c.back.includes('\n');
}

/** A matching round from [cards] whose both sides are short enough to tap. */
export function match(cards, random = Math.random) {
  const seen = new Set();
  const usable = [];
  for (const c of cards.filter(canMatch)) {
    const k = normalize(c.front);
    if (!seen.has(k)) { seen.add(k); usable.push(c); }
  }
  if (usable.length < 3) return null;
  return { type: QuestionType.MATCH, cards: usable, rightOrder: shuffled(usable.map((_, i) => i), random) };
}

const flip = (card) => ({ type: QuestionType.FLIP, card, cards: [card] });
const typeIn = (card) => ({ type: QuestionType.TYPE, card, cards: [card] });

/** A question of [type], or the plainest one that works for this card. */
export function question(type, card, pool, random = Math.random) {
  if (type === QuestionType.TYPE) return !card.hasImage && canCheck(card.back) ? typeIn(card) : flip(card);
  if (type === QuestionType.CHOICE) return choice(card, pool, random) ?? question(QuestionType.TYPE, card, pool, random);
  if (type === QuestionType.CLOZE) return clozeQuestion(card) ?? question(QuestionType.TYPE, card, pool, random);
  return flip(card);
}

/**
 * Exam mix: every card gets a kind it suits, kinds stay balanced, and one matching block
 * per MATCH_EVERY cards. Free explanations stay open questions.
 */
export function examMix(cards, pool, random = Math.random) {
  const out = [];
  let rest = cards.slice();
  for (let i = 0; i < Math.trunc(cards.length / MATCH_EVERY); i++) {
    const group = rest.filter(canMatch).slice(0, MATCH_SIZE);
    const q = match(group, random);
    if (q !== null) {
      out.push(q);
      rest = rest.filter((c) => !q.cards.some((x) => x.id === c.id));
    }
  }
  const used = new Map();
  for (const card of rest) {
    const options = [clozeQuestion(card), choice(card, pool, random), !card.hasImage && canCheck(card.back) ? typeIn(card) : null].filter((o) => o !== null);
    const q = minBy(options, (o) => used.get(o.type) ?? 0) ?? flip(card);
    used.set(q.type, (used.get(q.type) ?? 0) + 1);
    out.push(q);
  }
  return shuffled(out, random);
}

/**
 * State of one matching round: tap a left item, tap a right item. Two cards with the same
 * answer accept either right item. A pair wrong on the first try stays in [missed].
 */
export class MatchRound {
  constructor(cards) {
    this.cards = cards;
    this.solvedIds = new Set();
    this.missed = new Set();
  }

  get done() { return this.solvedIds.size === this.cards.length; }

  pick(leftCardId, rightCardId) {
    const left = this.cards.find((c) => c.id === leftCardId);
    const right = this.cards.find((c) => c.id === rightCardId);
    const ok = normalize(left.back) === normalize(right.back);
    if (ok) this.solvedIds.add(leftCardId); else this.missed.add(leftCardId);
    return ok;
  }

  firstTry(cardId) { return this.solvedIds.has(cardId) && !this.missed.has(cardId); }
}
