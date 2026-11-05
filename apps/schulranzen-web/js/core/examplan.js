// Study days before an exam and the learning path, as in ExamPlan.kt and LearningPath.kt.
import { SOLID_PERCENT } from './mockexam.js';
import { clamp, average } from './text.js';

export const PlanPhase = Object.freeze({ START: 'START', REVIEW: 'REVIEW', FINAL: 'FINAL' });

/** Days before the exam, nearest first; gaps widen by one each step. */
const OFFSETS = (() => {
  const out = [];
  let o = 1;
  let gap = 1;
  while (o <= 120) { out.push(o); o += gap; gap++; }
  return out;
})();

export function plan(today, exam, maxSessions = 8) {
  const left = exam - today;
  if (left <= 0 || maxSessions <= 0) return [];
  const dates = OFFSETS.filter((o) => o <= left).slice(0, maxSessions).map((o) => exam - o);
  // Start today if the first planned day lies further ahead: the sooner the first pass, the better.
  if (dates.length < maxSessions && !dates.includes(today)) dates.push(today);
  const sorted = [...new Set(dates)].sort((a, b) => a - b);
  return sorted.map((d, i) => {
    let phase = PlanPhase.REVIEW;
    if (d === exam - 1 && sorted.length > 1) phase = PlanPhase.FINAL;
    else if (i === 0) phase = PlanPhase.START;
    return { date: d, phase };
  });
}

export function cardsToday(dueCards, newCards, today, exam) {
  if (newCards <= 0) return Math.max(0, dueCards);
  const days = exam === null || exam === undefined ? 7 : Math.max(1, exam - today);
  return Math.max(0, dueCards) + Math.ceil(newCards / days);
}

export const StageKind = Object.freeze({ MATERIAL: 'MATERIAL', CARDS: 'CARDS', LEARN: 'LEARN', MOCK: 'MOCK', FINAL: 'FINAL' });
export const StageState = Object.freeze({ DONE: 'DONE', ACTIVE: 'ACTIVE', OPEN: 'OPEN' });

export const MIN_CARDS = 10;
export const MAX_CARDS_TARGET = 40;
export const SOLID_SHARE = 0.8;

/** Cards worth writing: three per topic or marked page, at least [MIN_CARDS]. */
export const cardTarget = (topics, links) => clamp(Math.max(topics, links) * 3, MIN_CARDS, MAX_CARDS_TARGET);

/** input: { linksTotal, linksDone, topics, cards, cardsSeen, cardsSolid, cardsDue, mockBestPercent, daysLeft } */
export function buildPath(i) {
  const best = i.mockBestPercent ?? 0.0;
  const material = i.linksTotal > 0
    ? { p: i.linksDone / i.linksTotal, done: i.linksDone, total: i.linksTotal }
    // Nothing marked: working from cards alone is fine once cards exist.
    : { p: i.cards > 0 ? 1 : 0, done: 0, total: 0 };
  const target = cardTarget(i.topics, i.linksTotal);
  const mockDone = best >= SOLID_PERCENT;
  const cardsP = mockDone && i.cards > 0 ? 1 : Math.min(1, i.cards / target);
  const learnP = i.cards === 0 ? 0 : Math.min(1, (i.cardsSolid / i.cards) / SOLID_SHARE);
  // Just below the mark must not round up to "passed".
  const mockP = mockDone ? 1 : Math.min(0.99, best / SOLID_PERCENT);
  const eve = i.daysLeft <= 1;
  let finalP;
  if (!eve || i.cards === 0) finalP = 0;
  else if (i.cardsDue === 0 && i.cardsSeen === i.cards) finalP = 1;
  else finalP = Math.max(0, i.cards - i.cardsDue) / i.cards;
  const raw = [
    [StageKind.MATERIAL, material.p, material.done, material.total],
    [StageKind.CARDS, cardsP, Math.min(i.cards, target), target],
    [StageKind.LEARN, learnP, i.cardsSolid, i.cards],
    [StageKind.MOCK, mockP, Math.trunc(best), Math.trunc(SOLID_PERCENT)],
    [StageKind.FINAL, finalP, Math.max(0, i.cards - i.cardsDue), i.cards],
  ];
  let activeFound = false;
  const stages = raw.map(([kind, p, done, total]) => {
    let state;
    if (p >= 0.999) state = StageState.DONE;
    else if (activeFound) state = StageState.OPEN;
    else if (kind === StageKind.FINAL && !eve) state = StageState.OPEN;
    else { activeFound = true; state = StageState.ACTIVE; }
    return { kind, state, progress: clamp(p, 0, 1), done, total };
  });
  let next = null;
  if (i.daysLeft >= 0) {
    next = stages.find((s) => s.state === StageState.ACTIVE) ?? stages.find((s) => s.state === StageState.OPEN) ?? null;
  }
  return { stages, progress: average(stages.map((s) => s.progress)), next };
}
