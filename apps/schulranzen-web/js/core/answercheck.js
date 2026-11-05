// Tolerant checking of typed answers, same rules as AnswerCheck.kt: it forgives what an
// exam corrector would forgive (umlaut spelling, capitals, articles, typing slips,
// 1,5 vs 1.5, 1,5 bar vs 150 kPa) and nothing that changes the meaning.
import { trimStartChars, minBy } from './text.js';

export const Verdict = Object.freeze({ RIGHT: 'RIGHT', TYPO: 'TYPO', PARTLY: 'PARTLY', WRONG: 'WRONG', UNCHECKABLE: 'UNCHECKABLE' });
const ORDINAL = { RIGHT: 0, TYPO: 1, PARTLY: 2, WRONG: 3, UNCHECKABLE: 4 };

export const SelfGrade = Object.freeze({ RIGHT: 'RIGHT', PARTLY: 'PARTLY', WRONG: 'WRONG', SKIPPED: 'SKIPPED' });
export const GRADE_POINTS = { RIGHT: 1.0, PARTLY: 0.5, WRONG: 0.0, SKIPPED: 0.0 };

export const gradeOf = (verdict) => ({
  RIGHT: SelfGrade.RIGHT, TYPO: SelfGrade.RIGHT, PARTLY: SelfGrade.PARTLY, WRONG: SelfGrade.WRONG, UNCHECKABLE: SelfGrade.SKIPPED,
}[verdict]);

export const AnswerHint = Object.freeze({ UNIT_MISSING: 'UNIT_MISSING', UNIT_WRONG: 'UNIT_WRONG', NUMBER_WRONG: 'NUMBER_WRONG', NEGATION: 'NEGATION', TOO_MANY_VALUES: 'TOO_MANY_VALUES' });

const result = (verdict, matched = 0, total = 0, hint = null) => ({ verdict, matched, total, hint });

const MAX_CHECKABLE_WORDS = 8;
const MAX_CHECKABLE_PARTS = 12;

const FILLERS = new Set([
  'der', 'die', 'das', 'den', 'dem', 'des', 'ein', 'eine', 'einen', 'einem', 'einer', 'eines',
  'ca', 'circa', 'etwa', 'rund', 'ungefaehr', 'mindestens', 'mind', 'hoechstens', 'max', 'min', 'maximal', 'minimal',
  'und', 'sowie', 'von', 'im', 'in', 'am', 'an', 'auf', 'zu', 'zum', 'zur', 'fuer', 'mit', 'bei', 'beim', 'ist', 'sind', 'wird', 'werden',
]);
const NEGATIONS = new Set(['nicht', 'kein', 'keine', 'keinen', 'keinem', 'keiner', 'nie', 'niemals', 'ohne']);

/** Lower case, umlauts spelled out, punctuation gone. Digits stay. */
export function normalize(text) {
  return text.toLowerCase()
    .split('ä').join('ae').split('ö').join('oe').split('ü').join('ue').split('ß').join('ss')
    .replace(/[^\p{L}\p{N}\s]/gu, ' ')
    .replace(/\s+/g, ' ').trim();
}

/** Damerau-Levenshtein with transposition, the usual typing slips. */
export function distance(a, b) {
  if (a === b) return 0;
  if (a.length === 0) return b.length;
  if (b.length === 0) return a.length;
  const d = Array.from({ length: a.length + 1 }, () => new Array(b.length + 1).fill(0));
  for (let i = 0; i <= a.length; i++) d[i][0] = i;
  for (let j = 0; j <= b.length; j++) d[0][j] = j;
  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost);
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
    }
  }
  return d[a.length][b.length];
}

/** Short words must be exact ("warm"/"kalt", "Ohr"/"Rohr"); longer ones allow more slips. */
function allowedSlips(len) {
  if (len <= 4) return 0;
  if (len <= 7) return 1;
  if (len <= 12) return 2;
  return 3;
}

function wordsMatch(expected, given) {
  if (expected === given) return Verdict.RIGHT;
  return distance(expected, given) <= allowedSlips(expected.length) ? Verdict.TYPO : null;
}

const PLAIN_NUMBER = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/;

/**
 * "1,5" and "1.5" give 1.5; "1.500" and "1.500,5" use the German thousands dot;
 * "1,500" stays one and a half. Null when it is no number.
 */
export function parseNumber(raw) {
  const t = raw.trim().split('−').join('-').split(' ').join('');
  let german;
  if (t.includes(',') && t.includes('.')) german = t.split('.').join('').split(',').join('.');
  else if (t.includes(',')) german = t.split(',').join('.');
  else if (/^-?\d{1,3}(\.\d{3})+$/.test(t)) german = t.split('.').join('');
  else german = t;
  return PLAIN_NUMBER.test(german) ? Number(german) : null;
}

const UNITS = new Map();
function addUnits(family, factor, ...names) { for (const n of names) UNITS.set(n, { family, factor }); }
addUnits('temp', 1.0, '°c', '°', 'grad', 'gradcelsius', 'celsius', 'gradc');
addUnits('kelvin', 1.0, 'k', 'kelvin');
addUnits('press', 1.0, 'pa', 'pascal');
addUnits('press', 100.0, 'hpa', 'mbar', 'millibar');
addUnits('press', 1000.0, 'kpa');
addUnits('press', 1e5, 'bar');
addUnits('press', 1e6, 'mpa');
addUnits('len', 1e-3, 'mm', 'millimeter');
addUnits('len', 1e-2, 'cm', 'zentimeter');
addUnits('len', 0.1, 'dm', 'dezimeter');
addUnits('len', 1.0, 'm', 'meter');
addUnits('len', 1000.0, 'km', 'kilometer');
addUnits('vol', 1e-3, 'ml', 'milliliter');
addUnits('vol', 1.0, 'l', 'liter');
addUnits('vol', 1000.0, 'm3', 'm³', 'kubikmeter');
addUnits('area', 1e-4, 'cm2', 'cm²');
addUnits('area', 1.0, 'm2', 'm²', 'quadratmeter');
addUnits('power', 1.0, 'w', 'watt');
addUnits('power', 1000.0, 'kw', 'kilowatt');
addUnits('energy', 1.0, 'wh');
addUnits('energy', 1000.0, 'kwh');
addUnits('time', 1.0, 's', 'sek', 'sekunde', 'sekunden');
addUnits('time', 60.0, 'min', 'minute', 'minuten');
addUnits('time', 3600.0, 'h', 'std', 'stunde', 'stunden');
addUnits('time', 86400.0, 'd', 'tag', 'tage', 'tagen');
addUnits('mass', 1e-3, 'g', 'gramm');
addUnits('mass', 1.0, 'kg', 'kilogramm');
addUnits('mass', 1000.0, 't', 'tonne', 'tonnen');
addUnits('pct', 1.0, '%', 'prozent');
addUnits('volt', 1.0, 'v', 'volt');
addUnits('amp', 1.0, 'a', 'ampere');

const QUANTITY = '([-−]?\\d[\\d.,]*\\d|[-−]?\\d)\\s*(°\\s*[cC]|°|%|[\\p{L}][\\p{L}²³\\d]*(?:/[\\p{L}²³\\d]+)?)?';
const BEFORE_NUMBER = /[\p{L}\d.,]/u;

/**
 * All number-plus-unit matches. The Android regex starts with a lookbehind, which Safari
 * before 16.4 cannot even parse and which would take the whole app down there, so the
 * same check is made here by hand: on a refused position the search resumes one further,
 * exactly as the regex engine would.
 */
function findQuantities(text) {
  const re = new RegExp(QUANTITY, 'gu');
  const out = [];
  let m;
  while ((m = re.exec(text)) !== null) {
    const before = m.index > 0 ? text[m.index - 1] : '';
    if (before && BEFORE_NUMBER.test(before)) { re.lastIndex = m.index + 1; continue; }
    out.push({ index: m.index, end: m.index + m[0].length, number: m[1], rawUnit: m[2] || '' });
  }
  return out;
}

const cleanUnit = (raw) => raw.toLowerCase().split(' ').join('');

function quantities(text) {
  const out = [];
  for (const q of findQuantities(text)) {
    const v = parseNumber(q.number);
    if (v === null) continue;
    const raw = cleanUnit(q.rawUnit);
    out.push({ value: v, unit: raw === '' ? null : raw });
  }
  return out;
}

function withoutQuantities(text) {
  let out = '';
  let at = 0;
  for (const q of findQuantities(text)) {
    out += text.slice(at, q.index);
    // Unknown words after a number ("3 Schritte") stay words.
    const raw = cleanUnit(q.rawUnit);
    out += raw !== '' && !UNITS.has(raw) && !raw.includes('/') ? ` ${q.rawUnit} ` : ' ';
    at = q.end;
  }
  return out + text.slice(at);
}

const sameValue = (a, b) => Math.abs(a - b) <= 1e-9 * Math.max(1.0, Math.abs(a), Math.abs(b));

/** Remarks in brackets are extras on a card: "60 °C (Legionellenschutz)". */
const stripBrackets = (s) => s.replace(/\([^)]*\)/g, ' ').replace(/\[[^\]]*\]/g, ' ');

const BULLETS = ['•', '-', '–', '*', '·', ' '];

function parts(expected) {
  return expected.split('\n').map((l) => trimStartChars(l.trim(), BULLETS).trim()).filter((l) => l.length > 0);
}

function alternatives(part) {
  return part.split(/\s+oder\s+|\s+bzw\.?\s+|\s+\/\s+|;/).map((s) => s.trim()).filter((s) => s.length > 0);
}

const wordsOf = (text) => normalize(text).split(' ').filter((w) => w.length > 0);

function contentWords(text) {
  const n = wordsOf(text);
  const content = n.filter((w) => !FILLERS.has(w));
  return content.length > 0 ? content : n;
}

/** Whether [expected] can be compared by machine at all. */
export function canCheck(expected) {
  const clean = stripBrackets(expected);
  if (clean.trim().length === 0) return false;
  const ps = parts(clean);
  if (ps.length === 0 || ps.length > MAX_CHECKABLE_PARTS) return false;
  return ps.every((p) => alternatives(p).every((a) => contentWords(withoutQuantities(a)).length <= MAX_CHECKABLE_WORDS));
}

export function check(given, expected) {
  if (!canCheck(expected)) return result(Verdict.UNCHECKABLE);
  const required = parts(stripBrackets(expected));
  if (given.trim().length === 0) return result(Verdict.WRONG, 0, required.length);
  if (required.length === 1) {
    const r = one(given, required[0]);
    return { ...r, matched: gradeOf(r.verdict) === SelfGrade.RIGHT ? 1 : 0, total: 1 };
  }
  // A list: every item must be named somewhere, in any order.
  const pieces = given.split(/[\n;,]|\s+und\s+|\s+sowie\s+/).map((s) => trimStartChars(s.trim(), BULLETS)).filter((s) => s.length > 0);
  let matched = 0;
  let typo = false;
  const free = pieces.slice();
  for (const item of required) {
    const idx = free.findIndex((p) => gradeOf(one(p, item).verdict) === SelfGrade.RIGHT);
    if (idx < 0) continue;
    matched++;
    if (one(free[idx], item).verdict === Verdict.TYPO) typo = true;
    free.splice(idx, 1);
  }
  let verdict;
  if (matched === required.length) verdict = typo ? Verdict.TYPO : Verdict.RIGHT;
  else if (matched === 0) verdict = Verdict.WRONG;
  else verdict = Verdict.PARTLY;
  return result(verdict, matched, required.length);
}

function one(given, expected) {
  const alts = [...new Set([...alternatives(expected), expected])];
  return minBy(alts.map((a) => single(given, a)), (r) => ORDINAL[r.verdict]) ?? result(Verdict.WRONG);
}

function single(given, expected) {
  const wantQ = quantities(expected);
  const haveQ = quantities(given);
  const wantWords = contentWords(withoutQuantities(expected));
  const haveWords = contentWords(withoutQuantities(given));
  // "mindestens 60 °C": around a value, filler words are not asked for.
  const wantPlain = wordsOf(withoutQuantities(expected));
  const hasWantWords = wantPlain.length > 0 && (wantQ.length === 0 || wantPlain.some((w) => !FILLERS.has(w)));

  // Meaning flips on a negation: never forgive that.
  const wantNeg = wordsOf(expected).some((w) => NEGATIONS.has(w));
  const haveNeg = wordsOf(given).some((w) => NEGATIONS.has(w));
  if (wantNeg !== haveNeg) return result(Verdict.WRONG, 0, 0, AnswerHint.NEGATION);

  let unitMissing = false;
  if (wantQ.length > 0) {
    const pool = haveQ.slice();
    for (const w of wantQ) {
      const exact = pool.findIndex((h) => matches(w, h) === 'OK');
      if (exact >= 0) { pool.splice(exact, 1); continue; }
      const bare = pool.findIndex((h) => matches(w, h) === 'NO_UNIT');
      if (bare >= 0) { pool.splice(bare, 1); unitMissing = true; continue; }
      const wrongUnit = pool.some((h) => matches(w, h) === 'WRONG_UNIT');
      return result(Verdict.WRONG, 0, 0, wrongUnit ? AnswerHint.UNIT_WRONG : AnswerHint.NUMBER_WRONG);
    }
    // "60 oder 80" must not score on 60: hedging is no answer.
    if (pool.length > 0) return result(Verdict.WRONG, 0, 0, AnswerHint.TOO_MANY_VALUES);
  } else if (haveQ.length > 0 && !hasWantWords) {
    return result(Verdict.WRONG, 0, 0, AnswerHint.NUMBER_WRONG);
  }

  let verdict = Verdict.RIGHT;
  if (hasWantWords && wantWords.length > 0) {
    // Compound spelling: "Membran Ausdehnungsgefäß" and "Membranausdehnungsgefäß" are the same.
    const joined = wordsMatch(wantWords.join(''), haveWords.join(''));
    if (joined !== null) {
      verdict = joined;
    } else {
      let found = 0;
      let slip = false;
      const pool = haveWords.slice();
      for (const w of wantWords) {
        const hits = pool.map((h) => ({ h, v: wordsMatch(w, h) })).filter((x) => x.v !== null);
        const hit = minBy(hits, (x) => ORDINAL[x.v]);
        if (hit === null) continue;
        found++;
        if (hit.v === Verdict.TYPO) slip = true;
        pool.splice(pool.indexOf(hit.h), 1);
      }
      const share = found / wantWords.length;
      if (found === wantWords.length) verdict = slip ? Verdict.TYPO : Verdict.RIGHT;
      else if (wantWords.length >= 3 && share >= 0.6) verdict = Verdict.PARTLY;
      else return result(Verdict.WRONG);
    }
  }
  if (unitMissing) return result(Verdict.PARTLY, 0, 0, AnswerHint.UNIT_MISSING);
  return result(verdict);
}

function matches(want, have) {
  const wi = want.unit !== null ? UNITS.get(want.unit) ?? null : null;
  const hi = have.unit !== null ? UNITS.get(have.unit) ?? null : null;
  // A word after the number that is no unit ("3 Schritte") is judged as a word, not here.
  const wantUnit = want.unit !== null && (wi !== null || want.unit.includes('/')) ? want.unit : null;
  const haveUnit = have.unit !== null && (hi !== null || have.unit.includes('/')) ? have.unit : null;
  const same = sameValue(want.value, have.value);
  if (wantUnit === null) return same ? 'OK' : 'DIFFERENT';
  if (haveUnit === null) return same ? 'NO_UNIT' : 'DIFFERENT';
  if (wi !== null && hi !== null) {
    if (wi.family !== hi.family) return same ? 'WRONG_UNIT' : 'DIFFERENT';
    if (sameValue(want.value * wi.factor, have.value * hi.factor)) return 'OK';
    return same ? 'WRONG_UNIT' : 'DIFFERENT';
  }
  // Compound units are compared as written: "m³/h", "l/min".
  if (!same) return 'DIFFERENT';
  return wantUnit === haveUnit ? 'OK' : 'WRONG_UNIT';
}
