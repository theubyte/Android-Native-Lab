// Day-based SM-2, tuned for school. Same formulas and results as Repetition.kt in the
// Android app (branch agent/lernen); tests/repetition.test.js carries its test values.
import { clamp } from './text.js';

/** Answers in the learning mode; the numbers are the ordinals the Android app stores. */
export const Rating = Object.freeze({ AGAIN: 0, HARD: 1, GOOD: 2, EASY: 3 });
export const RATINGS = [Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY];
export const RATING_NAMES = ['Nochmal', 'Schwer', 'Gut', 'Leicht'];

export const START_EASE = 2.5;
export const MIN_EASE = 1.3;
export const MAX_EASE = 3.2;
export const MAX_INTERVAL = 365;
const HARD_FACTOR = 1.2;
const EASY_BONUS = 1.3;

/** A card that was never seen: due at once on any day. */
export const NEVER = Number.MIN_SAFE_INTEGER;

export function newSchedule(over = {}) {
  return { reps: 0, intervalDays: 0, ease: START_EASE, lapses: 0, dueEpochDay: NEVER, lastReviewEpochDay: null, ...over };
}

export const isNew = (s) => s.lastReviewEpochDay === null || s.lastReviewEpochDay === undefined;
export const isDue = (s, today) => s.dueEpochDay <= today;

/**
 * Interval for a card in the review phase. A review before the due day only proves what
 * was actually waited, so it grows from the days elapsed, not from the planned interval.
 */
function grow(state, rating, ease, today) {
  const planned = Math.max(1, state.intervalDays);
  const elapsed = isNew(state) ? planned : Math.max(0, today - state.lastReviewEpochDay);
  const current = elapsed < planned ? Math.max(1, elapsed) : planned;
  const delay = Math.max(0, elapsed - planned);
  const hard = Math.max(current + 1, Math.round(current * HARD_FACTOR));
  const good = Math.max(hard + 1, Math.round((current + delay / 2.0) * ease));
  const easy = Math.max(good + 1, Math.round((current + delay) * ease * EASY_BONUS));
  if (rating === Rating.HARD) return hard;
  if (rating === Rating.GOOD) return good;
  return easy;
}

export function review(state, rating, today, examDate = null) {
  // Failing a card again in the same sitting says nothing new about it; punishing every
  // retry would push it to the minimum ease for good.
  const retryToday = rating === Rating.AGAIN && state.reps === 0 && state.lastReviewEpochDay === today;
  const change = rating === Rating.AGAIN ? (retryToday ? 0.0 : -0.20)
    : rating === Rating.HARD ? -0.15
      : rating === Rating.GOOD ? 0.0 : 0.15;
  const ease = clamp(state.ease + change, MIN_EASE, MAX_EASE);

  let interval;
  let reps;
  let lapses = state.lapses;
  if (rating === Rating.AGAIN) {
    interval = 0;
    reps = 0;
    if (state.reps > 0) lapses++;
  } else {
    reps = state.reps + 1;
    let raw;
    if (state.reps === 0) raw = rating === Rating.EASY ? 3 : 1;
    else if (state.reps === 1) raw = rating === Rating.HARD ? 2 : rating === Rating.GOOD ? 3 : 5;
    else raw = grow(state, rating, ease, today);
    interval = clamp(raw, 1, MAX_INTERVAL);
  }

  let due = today + interval;
  if (examDate !== null && examDate !== undefined && examDate > today) {
    // Keep one review before the exam; on the eve itself the exam day is the latest slot.
    due = Math.min(due, Math.max(today + (interval === 0 ? 0 : 1), examDate - 1));
  }
  return { reps, intervalDays: interval, ease, lapses, dueEpochDay: due, lastReviewEpochDay: today };
}

/** What each answer would give, in days, for the small counts on the buttons. */
export function preview(state, today, examDate = null) {
  return RATINGS.map((r) => review(state, r, today, examDate).dueEpochDay - today);
}

/** Due cards first by how overdue they are, then new cards in the order they were made. */
export function queue(cards, today, schedule = (c) => c) {
  const due = cards.filter((c) => isDue(schedule(c), today));
  const old = due.filter((c) => !isNew(schedule(c))).sort((a, b) => schedule(a).dueEpochDay - schedule(b).dueEpochDay);
  return old.concat(due.filter((c) => isNew(schedule(c))));
}
