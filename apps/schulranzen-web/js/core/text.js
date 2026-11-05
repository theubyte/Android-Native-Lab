// Small helpers that give Kotlin's string and list functions the same meaning in JS.

/** Kotlin's lines(): splits on \n, \r\n and \r. */
export const lines = (s) => s.split(/\r\n|\r|\n/);

/** Kotlin's trimStart(vararg chars). */
export function trimStartChars(s, chars) {
  let i = 0;
  while (i < s.length && chars.includes(s[i])) i++;
  return s.slice(i);
}

export function trimEndChars(s, chars) {
  let i = s.length;
  while (i > 0 && chars.includes(s[i - 1])) i--;
  return s.slice(0, i);
}

export const trimChars = (s, chars) => trimEndChars(trimStartChars(s, chars), chars);

const UPPER = /^\p{Lu}$/u;
const LETTER = /^\p{L}$/u;

export const isUpper = (ch) => ch !== undefined && UPPER.test(ch);
export const isLetter = (ch) => ch !== undefined && LETTER.test(ch);
export const isDigit = (ch) => ch !== undefined && ch >= '0' && ch <= '9';

/** First element with the largest key; Kotlin's maxByOrNull. */
export function maxBy(list, key) {
  let best = null;
  let bestKey = -Infinity;
  let any = false;
  for (const x of list) {
    const k = key(x);
    if (!any || k > bestKey) { best = x; bestKey = k; any = true; }
  }
  return any ? best : null;
}

/** First element with the smallest key; Kotlin's minByOrNull. */
export function minBy(list, key) {
  let best = null;
  let bestKey = Infinity;
  let any = false;
  for (const x of list) {
    const k = key(x);
    if (!any || k < bestKey) { best = x; bestKey = k; any = true; }
  }
  return any ? best : null;
}

export const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
export const sum = (list) => list.reduce((a, b) => a + b, 0);
export const average = (list) => (list.length === 0 ? NaN : sum(list) / list.length);

/** Fisher-Yates on a copy. [random] returns 0..1 and makes tests reproducible. */
export function shuffled(list, random = Math.random) {
  const out = list.slice();
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

/** A small seeded generator for tests. */
export function seeded(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
