// Ties the stored data to the pure planners: which cards belong to an exam, what counts as
// weak, today's plan, readiness and path. No storage and no DOM here, so all of it is testable.
import { isNew, review, NEVER } from './repetition.js';
import { build as buildPlan, weightOf } from './todayplan.js';
import { estimate } from './readiness.js';
import { cardTarget, buildPath } from './examplan.js';
import { topicOf, needsRelearning } from './mockexam.js';
import { buildErrorBook, isRight } from './progress.js';
import { normalize } from './answercheck.js';

export const EXAM_KINDS = [
  ['KLASSENARBEIT', 'Klassenarbeit'], ['TEST', 'Test'], ['LERNFELD', 'Lernfeldarbeit'], ['REFERAT', 'Referat'],
  ['PRAXIS', 'Praktische Prüfung'], ['GP1', 'Gesellenprüfung Teil 1'], ['GP2', 'Gesellenprüfung Teil 2'], ['SONSTIGE', 'Sonstige'],
];
export const examKindLabel = (kind) => (EXAM_KINDS.find((k) => k[0] === kind) ?? EXAM_KINDS[7])[1];

const low = (s) => String(s ?? '').trim().toLowerCase();

export const scheduleOf = (c) => ({
  reps: c.reps, intervalDays: c.intervalDays, ease: c.ease, lapses: c.lapses,
  dueEpochDay: c.dueEpochDay ?? NEVER, lastReviewEpochDay: c.lastReviewEpochDay ?? null,
});

export const topicsOf = (exam) => String(exam.topics ?? '').split('\n').map((t) => t.trim()).filter((t) => t.length > 0);

/** Decks of an exam: chosen by hand, or else all of the same subject (deck name also counts). */
export function examDecks(exam, decks) {
  if (Array.isArray(exam.deckIds)) return decks.filter((d) => exam.deckIds.includes(d.id));
  const subject = low(exam.subject);
  return decks.filter((d) => low(d.subject) === subject || low(d.name) === subject);
}

export function examCards(exam, state) {
  const ids = new Set(examDecks(exam, state.decks).map((d) => d.id));
  return state.cards.filter((c) => ids.has(c.deckId));
}

/** The exam that caps a deck's intervals: the next one it belongs to. */
export function examForDeck(deck, state, today) {
  let best = null;
  for (const e of state.exams) {
    if (e.dateEpochDay < today) continue;
    if (!examDecks(e, [deck]).length) continue;
    if (best === null || e.dateEpochDay < best.dateEpochDay) best = e;
  }
  return best;
}

export function examDateForCard(card, state, today) {
  const deck = state.decks.find((d) => d.id === card.deckId);
  return deck ? examForDeck(deck, state, today)?.dateEpochDay ?? null : null;
}

/** A pack's topic wins when the exam names it; otherwise the words of the card decide. */
export function topicOfCard(card, exam, deck) {
  const topics = topicsOf(exam);
  const fallback = card.topic || deck?.name || 'Ohne Thema';
  if (topics.length === 0) return fallback;
  if (card.topic) {
    const hit = topics.find((t) => normalize(t) === normalize(card.topic));
    if (hit) return hit;
  }
  return topicOf(`${card.topic ?? ''} ${card.front} ${card.back}`, topics, fallback);
}

/** Answers from learning and from mock exams, for the error list. */
export function attemptsOf(state) {
  const out = state.reviews.map((r) => ({ cardId: r.cardId, epochDay: r.epochDay, at: r.at, right: isRight(r.rating) }));
  for (const run of state.mockRuns) {
    const day = run.epochDay ?? Math.floor(run.at / 86400000);
    for (const a of run.answers ?? []) {
      if (a.cardId !== null && a.cardId !== undefined) out.push({ cardId: a.cardId, epochDay: day, at: run.at, right: !needsRelearning(a.grade) });
    }
  }
  return out;
}

export function errorBookOf(state) {
  const known = new Set(state.cards.map((c) => c.id));
  return buildErrorBook(attemptsOf(state)).filter((e) => known.has(e.cardId));
}

/** As StudyRepository.todayPlan on Android: on the error list, or forgotten twice. */
export function weakCardIds(state) {
  const weak = new Set(errorBookOf(state).map((e) => e.cardId));
  for (const c of state.cards) if (c.lapses >= 2) weak.add(c.id);
  return weak;
}

function daysSinceMock(exam, state, today) {
  let best = null;
  for (const run of state.mockRuns) {
    if (run.examId !== exam.id) continue;
    const d = today - (run.epochDay ?? Math.floor(run.at / 86400000));
    if (best === null || d < best) best = d;
  }
  return best;
}

/** Cards answered for the first time today: they used up today's share of new cards. */
function introducedToday(state, today) {
  const first = new Map();
  for (const r of state.reviews) {
    const f = first.get(r.cardId);
    if (f === undefined || r.epochDay < f) first.set(r.cardId, r.epochDay);
  }
  const out = new Set();
  for (const [id, day] of first) if (day === today) out.add(id);
  return out;
}

export function planInput(state, today) {
  const weak = weakCardIds(state);
  const intro = introducedToday(state, today);
  const toPlanCard = (c) => ({ id: c.id, schedule: scheduleOf(c), weak: weak.has(c.id), introducedToday: intro.has(c.id) });
  const coming = state.exams.filter((e) => e.dateEpochDay >= today);
  const exams = coming.map((e) => ({
    id: e.id,
    date: e.dateEpochDay,
    weight: weightOf(e.kind),
    cards: examCards(e, state).map(toPlanCard),
    openLinkIds: (e.material ?? []).filter((m) => !m.doneAt).map((m) => m.id),
    daysSinceMock: daysSinceMock(e, state, today),
  }));
  const claimed = new Set(exams.flatMap((e) => e.cards.map((c) => c.id)));
  const otherDue = state.cards
    .filter((c) => !claimed.has(c.id) && scheduleOf(c).dueEpochDay <= today)
    .map(toPlanCard);
  return { exams, otherDue };
}

export function todayPlan(state, today, budgetMinutes) {
  const { exams, otherDue } = planInput(state, today);
  return buildPlan(exams, otherDue, today, budgetMinutes);
}

export function readinessOf(exam, state, today) {
  const cards = examCards(exam, state);
  const decks = new Map(state.decks.map((d) => [d.id, d]));
  const topics = topicsOf(exam);
  const material = exam.material ?? [];
  const mocks = state.mockRuns.filter((r) => r.examId === exam.id).map((r) => ({
    percent: r.percent, asked: r.asked, daysAgo: Math.max(0, today - (r.epochDay ?? Math.floor(r.at / 86400000))),
  }));
  return estimate({
    cards: cards.map(scheduleOf),
    cardTopics: topics.length > 0 ? cards.map((c) => topicOfCard(c, exam, decks.get(c.deckId))) : [],
    topics,
    mocks,
    linksTotal: material.length,
    linksDone: material.filter((m) => m.doneAt).length,
    cardTarget: cardTarget(topics.length, material.length),
    today,
    examDate: exam.dateEpochDay,
  });
}

export function pathOf(exam, state, today) {
  const cards = examCards(exam, state).map(scheduleOf);
  const material = exam.material ?? [];
  const runs = state.mockRuns.filter((r) => r.examId === exam.id);
  return buildPath({
    linksTotal: material.length,
    linksDone: material.filter((m) => m.doneAt).length,
    topics: topicsOf(exam).length,
    cards: cards.length,
    cardsSeen: cards.filter((s) => !isNew(s)).length,
    cardsSolid: cards.filter((s) => s.reps >= 2).length,
    cardsDue: cards.filter((s) => !isNew(s) && s.dueEpochDay <= today).length,
    mockBestPercent: runs.length === 0 ? null : Math.max(...runs.map((r) => r.percent)),
    daysLeft: exam.dateEpochDay - today,
  });
}

export function deckCounts(deck, state, today) {
  const cards = state.cards.filter((c) => c.deckId === deck.id);
  const fresh = cards.filter((c) => isNew(scheduleOf(c))).length;
  const due = cards.filter((c) => !isNew(scheduleOf(c)) && c.dueEpochDay <= today).length;
  return { total: cards.length, fresh, due, unverified: cards.filter((c) => c.verified === false).length };
}

/** The result of an answer, ready to store. */
export function applyRating(card, rating, today, examDate) {
  return { ...card, ...review(scheduleOf(card), rating, today, examDate) };
}

export function knownSubjects(state) {
  const seen = new Map();
  for (const d of state.decks) if (d.subject && !seen.has(low(d.subject))) seen.set(low(d.subject), { name: d.subject, colorIndex: d.colorIndex });
  for (const e of state.exams) if (e.subject && !seen.has(low(e.subject))) seen.set(low(e.subject), { name: e.subject, colorIndex: e.colorIndex });
  return [...seen.values()].sort((a, b) => a.name.localeCompare(b.name, 'de'));
}
