// Fehlerheft, Fokus-Timer, statistics and streak, as in Progress.kt and StudyStats.kt.
import { Rating } from './repetition.js';

export const MIN_WRONG_DAYS = 2;
export const MIN_WRONG_TOTAL = 3;
export const CLEAR_AFTER = 3;

/** Only "Nochmal" counts as wrong; "Schwer" was remembered. */
export const isRight = (rating) => rating !== Rating.AGAIN;

/**
 * The error list collects itself from the answer log: a card is on it once it went wrong
 * on two different days, or three times overall. It leaves on its own after CLEAR_AFTER
 * right answers in a row. attempts: { cardId, epochDay, at, right }, in any order.
 */
export function buildErrorBook(attempts) {
  const groups = new Map();
  for (const a of attempts) {
    if (!groups.has(a.cardId)) groups.set(a.cardId, []);
    groups.get(a.cardId).push(a);
  }
  const out = [];
  for (const [cardId, list] of groups) {
    const sorted = list.slice().sort((a, b) => a.at - b.at);
    const wrongs = sorted.filter((a) => !a.right);
    const days = new Set(wrongs.map((a) => a.epochDay)).size;
    if (wrongs.length < MIN_WRONG_TOTAL && days < MIN_WRONG_DAYS) continue;
    let streak = 0;
    for (let i = sorted.length - 1; i >= 0 && sorted[i].right; i--) streak++;
    if (streak >= CLEAR_AFTER) continue;
    out.push({ cardId, wrong: wrongs.length, wrongDays: days, lastWrongDay: Math.max(...wrongs.map((a) => a.epochDay)), rightInARow: streak });
  }
  return out.sort((a, b) => b.wrong - a.wrong || b.lastWrongDay - a.lastWrongDay);
}

export const DEFAULT_FOCUS = Object.freeze({ focus: 25, shortBreak: 5, longBreak: 15, blocksUntilLong: 4 });

/**
 * Focus timer as a pure function of elapsed time. Measured from a start instant rather
 * than ticked, so it stays right when the screen goes dark or the tab sleeps.
 */
export function focusAt(plan, elapsedSeconds) {
  let t = Math.max(0, Math.floor(elapsedSeconds));
  let block = 1;
  let focus = 0;
  const fs = plan.focus * 60;
  for (;;) {
    if (t < fs) return { inBreak: false, block, secondsLeft: fs - t, phaseSeconds: fs, focusSeconds: focus + t, longBreak: false };
    t -= fs;
    focus += fs;
    const long = block % plan.blocksUntilLong === 0;
    const bs = (long ? plan.longBreak : plan.shortBreak) * 60;
    if (t < bs) return { inBreak: true, block, secondsLeft: bs - t, phaseSeconds: bs, focusSeconds: focus, longBreak: long };
    t -= bs;
    block++;
  }
}

/**
 * Statistics over the [spanDays] days up to [today], one entry per day (also the empty
 * ones, so a gap is visible). answers: { epochDay, deckId, right }; focus: Map day→minutes.
 */
export function summary(answers, focus, today, spanDays) {
  const from = today - (spanDays - 1);
  const inSpan = answers.filter((a) => a.epochDay >= from && a.epochDay <= today);
  const days = [];
  for (let i = 0; i < spanDays; i++) {
    const d = from + i;
    const list = inSpan.filter((a) => a.epochDay === d);
    const right = list.filter((a) => a.right).length;
    days.push({
      date: d, answers: list.length, right, focusMinutes: focus.get(d) ?? 0,
      percent: list.length === 0 ? null : Math.trunc((right * 100) / list.length),
    });
  }
  const perDeck = new Map();
  for (const a of inSpan) {
    if (!perDeck.has(a.deckId)) perDeck.set(a.deckId, { deckId: a.deckId, answers: 0, right: 0 });
    const s = perDeck.get(a.deckId);
    s.answers++;
    if (a.right) s.right++;
  }
  const decks = [...perDeck.values()]
    .map((s) => ({ ...s, percent: s.answers === 0 ? 0 : Math.trunc((s.right * 100) / s.answers) }))
    .sort((a, b) => a.percent - b.percent);
  let best = 0;
  let run = 0;
  for (const d of days) {
    if (d.answers > 0 || d.focusMinutes > 0) { run++; best = Math.max(best, run); } else run = 0;
  }
  return {
    days, decks,
    answers: inSpan.length,
    right: inSpan.filter((a) => a.right).length,
    focusMinutes: days.reduce((s, d) => s + d.focusMinutes, 0),
    activeDays: days.filter((d) => d.answers > 0 || d.focusMinutes > 0).length,
    bestStreak: best,
  };
}

/**
 * Days in a row with at least one review. A streak that ended yesterday still counts
 * until today is over: the learner can keep it alive tonight.
 */
export function streak(reviewDays, today) {
  const set = reviewDays instanceof Set ? reviewDays : new Set(reviewDays);
  let day = today;
  if (!set.has(day)) day -= 1;
  let n = 0;
  while (set.has(day)) { n++; day--; }
  return n;
}

/** Share of cards out of the learning phase (two good answers in a row), 0..100. */
export function mastery(schedules) {
  if (schedules.length === 0) return 0;
  return Math.trunc((schedules.filter((s) => s.reps >= 2).length * 100) / schedules.length);
}
