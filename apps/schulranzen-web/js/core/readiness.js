// Prüfungsreife: a forecast that does not flatter, as in Readiness.kt. It estimates what
// is still in memory on the exam day, scales that by how much of the material is covered
// and blends in recent mock exams, the only direct evidence.
import { isNew } from './repetition.js';
import { MIN_CARDS } from './examplan.js';
import { grade, SOLID_PERCENT } from './mockexam.js';
import { clamp, average, sum } from './text.js';

const RECALL_AT_DUE = 0.9;
const CAP_WITHOUT_MOCK = 85;
const MOCK_FRESH_DAYS = 14;
const MOCK_OLD_DAYS = 21;
const MIN_BASIS_CARDS = 5;
export const SHAKY_RECALL = 0.6;
const SHAKY_LAPSES = 2;

export const Confidence = Object.freeze({ NONE: 'NONE', LOW: 'LOW', MEDIUM: 'MEDIUM', HIGH: 'HIGH' });
export const GapKind = Object.freeze({
  NO_CARDS: 'NO_CARDS', FEW_CARDS: 'FEW_CARDS', NEW_CARDS: 'NEW_CARDS', DUE_CARDS: 'DUE_CARDS', SHAKY_CARDS: 'SHAKY_CARDS',
  TOPICS_MISSING: 'TOPICS_MISSING', TOPICS_WEAK: 'TOPICS_WEAK', LINKS_OPEN: 'LINKS_OPEN', NO_MOCK: 'NO_MOCK', OLD_MOCK: 'OLD_MOCK', MOCK_LOW: 'MOCK_LOW',
});

/**
 * Estimated chance to recall one card on the exam day. Exponential forgetting, with the
 * card's interval read as the time after which RECALL_AT_DUE is left.
 */
export function recall(s, today, exam) {
  if (isNew(s)) return 0.0;
  const interval = s.reps === 0 ? 0.25 : Math.max(1, s.intervalDays);
  const stability = interval / -Math.log(RECALL_AT_DUE);
  const until = Math.max(exam, today);
  const waited = Math.max(0, until - s.lastReviewEpochDay);
  return clamp(Math.exp(-waited / stability), 0.0, 1.0);
}

const covered = (topic, i) => i.cardTopics.some((t, k) => t === topic && k < i.cards.length && !isNew(i.cards[k]));

function coverage(i) {
  const parts = [Math.min(1.0, i.cards.length / Math.max(1, i.cardTarget))];
  if (i.topics.length > 0) parts.push(i.topics.filter((t) => covered(t, i)).length / i.topics.length);
  if (i.linksTotal > 0) parts.push(i.linksDone / i.linksTotal);
  return average(parts);
}

function gaps(i, fresh, due, shaky, counted, mock, cardCount) {
  const out = [];
  const gap = (kind, count, names = []) => out.push({ kind, count, names });
  if (cardCount === 0) gap(GapKind.NO_CARDS, 0);
  else if (cardCount < i.cardTarget) gap(GapKind.FEW_CARDS, i.cardTarget - cardCount);
  const missing = i.topics.filter((t) => !i.cardTopics.includes(t));
  if (missing.length > 0) gap(GapKind.TOPICS_MISSING, missing.length, missing);
  const unlearned = i.topics.filter((t) => !missing.includes(t) && !covered(t, i));
  if (unlearned.length > 0) gap(GapKind.TOPICS_WEAK, unlearned.length, unlearned);
  if (fresh > 0) gap(GapKind.NEW_CARDS, fresh);
  if (due > 0) gap(GapKind.DUE_CARDS, due);
  if (shaky > 0) gap(GapKind.SHAKY_CARDS, shaky);
  if (i.linksTotal > i.linksDone) gap(GapKind.LINKS_OPEN, i.linksTotal - i.linksDone);
  if (i.mocks.length === 0) {
    if (cardCount > 0) gap(GapKind.NO_MOCK, 0);
  } else if (counted.length === 0) {
    gap(GapKind.OLD_MOCK, Math.min(...i.mocks.map((m) => m.daysAgo)));
  } else if (mock !== null && mock < SOLID_PERCENT) {
    gap(GapKind.MOCK_LOW, Math.round(mock));
  } else if (!counted.some((m) => m.daysAgo <= MOCK_FRESH_DAYS)) {
    gap(GapKind.OLD_MOCK, Math.min(...counted.map((m) => m.daysAgo)));
  }
  return out;
}

/**
 * input: { cards: schedule[], cardTopics: string[], topics: string[], mocks: [{percent, daysAgo, asked}],
 *          linksTotal, linksDone, cardTarget, today, examDate }
 */
export function estimate(input) {
  const i = { cardTopics: [], topics: [], mocks: [], linksTotal: 0, linksDone: 0, cardTarget: MIN_CARDS, ...input };
  const cards = i.cards;
  const seen = cards.filter((c) => !isNew(c)).length;
  const fresh = cards.length - seen;
  const recalls = cards.map((c) => recall(c, i.today, i.examDate));
  const memory = cards.length === 0 ? 0.0 : average(recalls);
  const due = cards.filter((c) => !isNew(c) && c.dueEpochDay <= i.today).length;
  const shaky = cards.filter((c, k) => !isNew(c) && (recalls[k] < SHAKY_RECALL || c.lapses >= SHAKY_LAPSES)).length;

  const cov = coverage(i);

  const counted = i.mocks.filter((m) => m.daysAgo <= MOCK_OLD_DAYS && m.asked > 0);
  let mock = null;
  if (counted.length > 0) {
    // Newer runs count more, longer runs count more: a 5-question run is thin evidence.
    let s = 0.0;
    let weight = 0.0;
    for (const m of counted) {
      const w = Math.exp(-m.daysAgo / 10.0) * Math.min(m.asked, 30);
      s += m.percent * w;
      weight += w;
    }
    mock = s / weight;
  }
  const mockQuestions = sum(counted.map((m) => m.asked));
  const hasBasis = seen >= MIN_BASIS_CARDS || counted.length > 0;
  const gapList = gaps(i, fresh, due, shaky, counted, mock, cards.length);
  const round = (v) => (v === null ? null : Math.round(v));

  if (!hasBasis) {
    return {
      percent: null, grade: null, low: null, high: null, confidence: Confidence.NONE,
      memoryPercent: Math.round(memory * 100), coveragePercent: Math.round(cov * 100), mockPercent: round(mock), gaps: gapList,
    };
  }
  let mockWeight = 0.0;
  if (mock !== null) mockWeight = mockQuestions >= 30 ? 0.7 : mockQuestions >= 15 ? 0.55 : 0.35;
  const memoryPart = memory * 100.0;
  const blended = (mock ?? 0.0) * mockWeight + memoryPart * (1 - mockWeight);
  // Material not on cards is not known just because the cards went well.
  let percent = blended * (0.4 + 0.6 * cov);
  if (mock === null) percent = Math.min(percent, CAP_WITHOUT_MOCK);
  const value = clamp(Math.round(percent), 0, 100);
  let confidence = Confidence.LOW;
  if (mock !== null && mockQuestions >= 15 && seen >= MIN_BASIS_CARDS) confidence = Confidence.HIGH;
  else if (mock !== null) confidence = Confidence.MEDIUM;
  const spread = confidence === Confidence.HIGH ? 7 : confidence === Confidence.MEDIUM ? 12 : 20;
  return {
    percent: value,
    grade: grade(value),
    low: Math.max(0, value - spread),
    high: Math.min(100, value + spread),
    confidence,
    memoryPercent: Math.round(memoryPart),
    coveragePercent: Math.round(cov * 100),
    mockPercent: round(mock),
    gaps: gapList,
  };
}
