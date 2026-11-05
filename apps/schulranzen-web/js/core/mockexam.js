// Mock exams from the learner's own cards and the IHK grading key, as in MockExam.kt.
import { SelfGrade, GRADE_POINTS } from './answercheck.js';
import { isNew, START_EASE } from './repetition.js';
import { sum } from './text.js';

/** Percent needed for a 4 (ausreichend). */
export const PASS_PERCENT = 50.0;
/** Percent from which a topic or the whole exam counts as "sitzt" (grade 3). */
export const SOLID_PERCENT = 67.0;

/** Draw weight of a card: 1 for a well-known card, up to ~8 for a shaky one. */
export function weight(s) {
  let w = 1.0;
  if (isNew(s)) w += 1.0;
  w += Math.min(s.lapses, 4) * 1.0;
  w += Math.max(0.0, START_EASE - s.ease) * 2.0;
  if (s.reps <= 1 && !isNew(s)) w += 0.5;
  return w;
}

/** Picks [count] cards (or all, if fewer), weak ones more likely, in a random order. */
export function pick(cards, count, schedule = (c) => c, random = Math.random) {
  if (count <= 0 || cards.length === 0) return [];
  const pool = cards.map((c) => ({ c, w: weight(schedule(c)) }));
  const out = [];
  while (out.length < count && pool.length > 0) {
    const total = sum(pool.map((p) => p.w));
    let r = random() * total;
    let idx = pool.length - 1;
    for (let i = 0; i < pool.length; i++) {
      r -= pool[i].w;
      if (r <= 0) { idx = i; break; }
    }
    out.push(pool.splice(idx, 1)[0].c);
  }
  return out;
}

/** Suggested time in minutes: 1.5 min per question, rounded up to 5. */
export const suggestedMinutes = (questions) => Math.max(5, Math.ceil((questions * 1.5) / 5.0) * 5);

/** IHK key: 100–92 → 1, 91–81 → 2, 80–67 → 3, 66–50 → 4, 49–30 → 5, below → 6. */
export function grade(percent) {
  if (percent >= 92) return 1;
  if (percent >= 81) return 2;
  if (percent >= 67) return 3;
  if (percent >= 50) return 4;
  if (percent >= 30) return 5;
  return 6;
}

export const GRADE_WORDS = { 1: 'sehr gut', 2: 'gut', 3: 'befriedigend', 4: 'ausreichend', 5: 'mangelhaft', 6: 'ungenügend' };

/** How the last run compares with the one before; [percents] are newest first. */
export const trend = (percents) => (percents.length < 2 ? null : percents[0] - percents[1]);

/** Whether a question counts as still open for relearning. */
export const needsRelearning = (g) => g !== SelfGrade.RIGHT;

/** [answers]: { topic, grade }. Topics weakest first. */
export function evaluate(answers) {
  const points = sum(answers.map((a) => GRADE_POINTS[a.grade]));
  const percent = answers.length === 0 ? 0.0 : (points * 100.0) / answers.length;
  const groups = new Map();
  for (const a of answers) {
    if (!groups.has(a.topic)) groups.set(a.topic, []);
    groups.get(a.topic).push(a);
  }
  const topics = [...groups].map(([topic, list]) => {
    const pts = sum(list.map((a) => GRADE_POINTS[a.grade]));
    return { topic, asked: list.length, points: pts, percent: list.length === 0 ? 0.0 : (pts * 100.0) / list.length };
  }).sort((a, b) => a.percent - b.percent);
  return {
    asked: answers.length, points, percent, grade: grade(percent),
    weakTopics: topics.filter((t) => t.percent < SOLID_PERCENT), topics,
  };
}

const STOP = new Set([
  'und', 'oder', 'der', 'die', 'das', 'den', 'dem', 'des', 'ein', 'eine', 'einer', 'eines', 'einem', 'einen',
  'mit', 'von', 'für', 'bei', 'nach', 'über', 'unter', 'wie', 'was', 'wird', 'werden', 'sind', 'ist', 'beim',
  'welche', 'welcher', 'welches', 'nenne', 'erkläre', 'beschreibe', 'warum', 'wann', 'wozu',
]);

/** Crude German stemming: enough to match "Ventile" with "Ventil" and "Heizungen" with "Heizung". */
export function stem(w) {
  for (const suffix of ['ungen', 'en', 'er', 'es', 'e', 'n', 's']) {
    if (w.length - suffix.length >= 4 && w.endsWith(suffix)) {
      return suffix === 'ungen' ? w.slice(0, -2) : w.slice(0, -suffix.length);
    }
  }
  return w;
}

export function stems(text) {
  const out = new Set();
  for (const w of text.toLowerCase().split(/[^\p{L}\p{N}]+/u)) {
    if (w.length >= 4 && !STOP.has(w)) out.add(stem(w));
  }
  return out;
}

/** The topic sharing most stems with [text]; ties go to the earlier topic; none → [fallback]. */
export function topicOf(text, topics, fallback) {
  if (topics.length === 0) return fallback;
  const words = [...stems(text)];
  let best = null;
  let bestScore = 0;
  for (const t of topics) {
    let score = 0;
    for (const s of stems(t)) {
      if (words.some((w) => w === s || (s.length >= 5 && (w.startsWith(s) || s.startsWith(w))))) score++;
    }
    if (score > bestScore) { best = t; bestScore = score; }
  }
  return best ?? fallback;
}
