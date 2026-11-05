// The test values of RepetitionTest.kt (branch agent/lernen), one to one.
import { suite, test, eq, near, ok, no } from './harness.js';
import { epochDayOf } from '../js/core/dates.js';
import { Rating, newSchedule, isNew, isDue, review, preview, queue, MIN_EASE, MAX_EASE, MAX_INTERVAL, START_EASE } from '../js/core/repetition.js';
import { streak } from '../js/core/progress.js';
import * as Plan from '../js/core/examplan.js';

const day0 = epochDayOf(2026, 10, 29);
const answer = (s, r, on, exam = null) => review(s, r, on, exam);
const { AGAIN, HARD, GOOD, EASY } = Rating;

suite('RepetitionTest');

test('new card is due at once', () => {
  const s = newSchedule();
  ok(isNew(s));
  ok(isDue(s, day0));
  ok(isDue(s, epochDayOf(1970, 1, 1)));
});

test('first answers give short school steps', () => {
  const s = newSchedule();
  eq(answer(s, AGAIN, day0).intervalDays, 0);
  eq(answer(s, HARD, day0).intervalDays, 1);
  eq(answer(s, GOOD, day0).intervalDays, 1);
  eq(answer(s, EASY, day0).intervalDays, 3);
  const second = answer(s, GOOD, day0);
  eq(answer(second, GOOD, day0 + 1).intervalDays, 3);
  eq(answer(second, HARD, day0 + 1).intervalDays, 2);
  eq(answer(second, EASY, day0 + 1).intervalDays, 5);
});

test('good answers grow by the ease', () => {
  let s = newSchedule();
  let d = day0;
  const intervals = [];
  for (let i = 0; i < 6; i++) {
    s = answer(s, GOOD, d);
    intervals.push(s.intervalDays);
    d = s.dueEpochDay;
  }
  eq(intervals, [1, 3, 8, 20, 50, 125]);
  near(s.ease, START_EASE);
  eq(s.lapses, 0);
});

test('answers are strictly ordered in the review phase', () => {
  const s = newSchedule({ reps: 3, intervalDays: 10, ease: 2.5, dueEpochDay: day0, lastReviewEpochDay: day0 - 10 });
  const p = preview(s, day0);
  eq(p[AGAIN], 0);
  ok(p[HARD] < p[GOOD]);
  ok(p[GOOD] < p[EASY]);
  eq(p[HARD], 12);
  eq(p[GOOD], 25);
  eq(p[EASY], Math.round((10 + 0) * 2.65 * 1.3));
});

test('hard on a tiny interval still moves forward', () => {
  const s = newSchedule({ reps: 2, intervalDays: 1, ease: 1.3, dueEpochDay: day0, lastReviewEpochDay: day0 - 1 });
  const p = preview(s, day0);
  eq(p[HARD], 2);
  eq(p[GOOD], 3);
  eq(p[EASY], 4);
});

test('forgetting resets and counts a lapse', () => {
  const s = newSchedule({ reps: 4, intervalDays: 20, ease: 2.5, dueEpochDay: day0, lastReviewEpochDay: day0 - 20 });
  const a = answer(s, AGAIN, day0);
  eq(a.reps, 0);
  eq(a.intervalDays, 0);
  eq(a.lapses, 1);
  near(a.ease, 2.3);
  ok(isDue(a, day0), 'a forgotten card comes back the same day');
  eq(answer(a, GOOD, day0).intervalDays, 1);
});

test('again on a new card is not a lapse', () => {
  eq(answer(newSchedule(), AGAIN, day0).lapses, 0);
});

test('ease stays within bounds', () => {
  let s = newSchedule();
  for (let i = 0; i < 30; i++) s = answer(s, HARD, day0);
  near(s.ease, MIN_EASE);
  for (let i = 0; i < 30; i++) s = answer(s, EASY, day0);
  near(s.ease, MAX_EASE);
});

test('interval is capped', () => {
  let s = newSchedule();
  let d = day0;
  for (let i = 0; i < 20; i++) { s = answer(s, EASY, d); d = s.dueEpochDay; }
  eq(s.intervalDays, MAX_INTERVAL);
});

test('late review earns a longer interval', () => {
  const onTime = newSchedule({ reps: 3, intervalDays: 10, ease: 2.5, dueEpochDay: day0, lastReviewEpochDay: day0 - 10 });
  const late = { ...onTime, lastReviewEpochDay: day0 - 20 };
  eq(answer(onTime, GOOD, day0).intervalDays, 25);
  eq(answer(late, GOOD, day0).intervalDays, 38); // (10 + 10/2) * 2.5 = 37.5
});

test('exam date pulls the next review before the exam', () => {
  const s = newSchedule({ reps: 3, intervalDays: 10, ease: 2.5, dueEpochDay: day0, lastReviewEpochDay: day0 - 10 });
  const exam = day0 + 6;
  const r = answer(s, GOOD, day0, exam);
  eq(r.dueEpochDay, exam - 1);
  eq(r.intervalDays, 25, 'the interval itself is kept for after the exam');
});

test('exam tomorrow still schedules on a later day', () => {
  eq(answer(newSchedule(), GOOD, day0, day0 + 1).dueEpochDay, day0 + 1);
});

test('past exam does not cap', () => {
  eq(answer(newSchedule(), EASY, day0, day0 - 3).dueEpochDay, day0 + 3);
});

test('due check is by calendar day', () => {
  const s = answer(newSchedule(), GOOD, day0);
  no(isDue(s, day0));
  ok(isDue(s, day0 + 1));
  ok(isDue(s, day0 + 30));
});

test('queue puts overdue cards first and new cards last', () => {
  const cards = [
    { id: 'new', s: newSchedule() },
    { id: 'today', s: newSchedule({ reps: 1, intervalDays: 1, dueEpochDay: day0, lastReviewEpochDay: day0 - 1 }) },
    { id: 'future', s: newSchedule({ reps: 1, intervalDays: 3, dueEpochDay: day0 + 2, lastReviewEpochDay: day0 }) },
    { id: 'overdue', s: newSchedule({ reps: 2, intervalDays: 3, dueEpochDay: day0 - 4, lastReviewEpochDay: day0 - 7 }) },
  ];
  eq(queue(cards, day0, (c) => c.s).map((c) => c.id), ['overdue', 'today', 'new']);
});

suite('RepetitionAuditTest');

test('repeating again in one sitting costs ease only once', () => {
  let s = newSchedule({ reps: 3, intervalDays: 10, ease: 2.5, lastReviewEpochDay: day0 - 10 });
  s = review(s, AGAIN, day0);
  near(s.ease, 2.3);
  for (let i = 0; i < 5; i++) s = review(s, AGAIN, day0);
  near(s.ease, 2.3);
  eq(s.lapses, 1, 'retries in the same sitting are not new lapses');
  near(review(s, AGAIN, day0 + 1).ease, 2.1);
});

test('early review grows from the days really waited', () => {
  const s = newSchedule({ reps: 4, intervalDays: 20, ease: 2.5, dueEpochDay: day0, lastReviewEpochDay: day0 - 2 });
  eq(review(s, GOOD, day0).intervalDays, 5);
  eq(review({ ...s, lastReviewEpochDay: day0 }, GOOD, day0).intervalDays, 3);
});

test('exam capped card does not balloon on its capped review', () => {
  const exam = day0 + 6;
  let s = newSchedule({ reps: 3, intervalDays: 10, ease: 2.5, dueEpochDay: day0, lastReviewEpochDay: day0 - 10 });
  s = review(s, GOOD, day0, exam);
  const again = review(s, GOOD, s.dueEpochDay, exam);
  eq(again.intervalDays, 13); // waited 5 days, not the planned 25
  eq(again.dueEpochDay, exam);
});

test('exam on the same day does not cap', () => {
  eq(review(newSchedule(), EASY, day0, day0).dueEpochDay, day0 + 3);
});

test('year boundary counts calendar days', () => {
  const eve = epochDayOf(2026, 12, 31);
  const r = review(newSchedule(), GOOD, eve);
  eq(r.dueEpochDay, epochDayOf(2027, 1, 1));
  ok(isDue(r, epochDayOf(2027, 1, 1)));
  eq(streak(new Set([eve]), epochDayOf(2027, 1, 1)), 1);
});

suite('AuditPlanTimetableTest');

test('quota never goes negative when new cards are not yet due', () => {
  eq(Plan.cardsToday(-3, 0, day0, day0 + 2), 0);
  eq(Plan.cardsToday(-3, 4, day0, day0 + 2), 2);
});

test('mock just below the mark is not done', () => {
  const p = Plan.buildPath({ linksTotal: 0, linksDone: 0, topics: 0, cards: 12, cardsSeen: 12, cardsSolid: 12, cardsDue: 0, mockBestPercent: 66.97, daysLeft: 5 });
  eq(p.stages.find((s) => s.kind === 'MOCK').state, 'ACTIVE');
});

test('streak survives until the day is over and breaks after', () => {
  const d = day0;
  eq(streak(new Set([d - 1, d - 2]), day0), 2);
  eq(streak(new Set([d - 2, d - 3]), day0), 0);
  eq(streak(new Set([d, d - 1, d - 2, d - 4]), day0), 3);
  eq(streak(new Set(), day0), 0);
});
