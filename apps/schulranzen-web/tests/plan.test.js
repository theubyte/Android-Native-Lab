// The test values of PlanReadinessTest.kt (branch agent/lernen), plus the PWA's own addition.
import { suite, test, eq, near, ok, no, isNull, notNull } from './harness.js';
import { epochDayOf } from '../js/core/dates.js';
import { newSchedule, Rating } from '../js/core/repetition.js';
import * as P from '../js/core/todayplan.js';
import * as R from '../js/core/readiness.js';
import { grade } from '../js/core/mockexam.js';
import { buildErrorBook, isRight, focusAt, summary } from '../js/core/progress.js';
import { shuffled, seeded } from '../js/core/text.js';

const today = epochDayOf(2026, 11, 5);
const d = today;
let nextId = 1;

const due = (weak = false, overdue = 0) => ({ id: nextId++, weak, schedule: newSchedule({ reps: 2, intervalDays: 3, dueEpochDay: d - overdue, lastReviewEpochDay: d - 3 - overdue }) });
const fresh = () => ({ id: nextId++, weak: false, schedule: newSchedule({ dueEpochDay: d }) });
const later = (weak = false) => ({ id: nextId++, weak, schedule: newSchedule({ reps: 3, intervalDays: 10, dueEpochDay: d + 5, lastReviewEpochDay: d - 5 }) });
const exam = (id, days, cards, weight = 1.0, links = [], mock = null) => ({ id, date: today + days, weight, cards, openLinkIds: links, daysSinceMock: mock });
const times = (n, f) => Array.from({ length: n }, () => f());

suite('TodayPlannerTest');

test('nothing to do is empty', () => {
  const p = P.build([], [], today);
  ok(p.isEmpty);
  eq(p.minutes, 0);
  eq(p.forecast.length, 0);
});

test('past exams are ignored', () => {
  ok(P.build([exam(1, -1, [due()])], [], today).isEmpty);
});

test('reviews come before new cards and weak before plain', () => {
  const p = P.build([exam(1, 10, [fresh(), due(), due(true)])], [], today, 60);
  eq(p.cards.map((c) => c.tier), ['WEAK_DUE', 'DUE', 'NEW']);
});

test('new cards are spread and the eve stays free', () => {
  const p = P.build([exam(1, 5, times(20, fresh))], [], today, 600);
  eq(p.cards.length, 5);
  eq(P.daysForNew(today, today + 5), 4);
  eq(P.daysForNew(today, today + 1), 1);
  eq(P.daysForNew(today, today), 1);
});

test('exam tomorrow takes all new cards today', () => {
  eq(P.build([exam(1, 1, times(7, fresh))], [], today, 600).cards.length, 7);
});

test('near exam gets more of a short budget than a far one', () => {
  const near_ = exam(1, 2, times(60, due));
  const far = exam(2, 30, times(60, due));
  const p = P.build([far, near_], [], today, 10);
  const a = p.cards.filter((c) => c.examId === 1).length;
  const b = p.cards.filter((c) => c.examId === 2).length;
  ok(a > b * 3, `near ${a} far ${b}`);
  ok(p.deferred > 0);
  ok(p.minutes <= 10);
});

test('a final exam outweighs a small test at the same distance', () => {
  eq(P.weightOf('GP1'), 3.0);
  const small = exam(1, 7, times(50, due), P.weightOf('TEST'));
  const big = exam(2, 7, times(50, due), P.weightOf('GP2'));
  const p = P.build([small, big], [], today, 10);
  ok(p.cards.filter((c) => c.examId === 2).length > p.cards.filter((c) => c.examId === 1).length);
});

test('every exam gets something when time allows', () => {
  const p = P.build([exam(1, 1, times(4, due)), exam(2, 40, times(4, due))], [], today, 60);
  eq([...new Set(p.cards.map((c) => c.examId))].sort(), [1, 2]);
  eq(p.deferred, 0);
});

test('a card shared by two exams is learnt once for the nearer', () => {
  const shared = due();
  const p = P.build([exam(1, 2, [shared]), exam(2, 20, [shared])], [], today, 60);
  eq(p.cards, [{ cardId: shared.id, examId: 1, tier: 'DUE' }]);
});

test('weak cards not yet due are pulled forward only close to the exam', () => {
  const weak = later(true);
  eq(P.build([exam(1, 5, [weak])], [], today, 60).cards[0].tier, 'WEAK_EXTRA');
  eq(P.build([exam(1, 20, [weak])], [], today, 60).cards.length, 0);
  eq(P.build([exam(1, 5, [later()])], [], today, 60).cards.length, 0);
});

test('other decks get what is left', () => {
  const other = due();
  eq(P.build([], [other], today, 10).cards[0].tier, 'OTHER_DUE');
  const busy = P.build([exam(1, 2, times(30, due))], [other], today, 5);
  no(busy.cards.some((c) => c.cardId === other.id));
  ok(busy.deferred > 0);
});

test('material share is spread and cards keep time', () => {
  const p = P.build([exam(1, 5, [due()], 1.0, [11, 12, 13, 14, 15, 16])], [], today, 30);
  ok(p.links.length <= 2);
  eq(p.links.slice(0, 1), [[1, 11]]);
  ok(p.cards.length > 0);
});

test('mock is suggested close to the exam with cards seen and not too often', () => {
  const seen = times(8, later);
  eq(P.build([exam(1, 6, seen)], [], today).mockExamId, 1);
  isNull(P.build([exam(1, 6, seen, 1, [], 1)], [], today).mockExamId);
  eq(P.build([exam(1, 6, seen, 1, [], 5)], [], today).mockExamId, 1);
  isNull(P.build([exam(1, 30, seen)], [], today).mockExamId);
  isNull(P.build([exam(1, 6, times(8, fresh))], [], today).mockExamId);
  isNull(P.build([exam(1, 0, seen)], [], today).mockExamId, 'on the day itself it is too late');
  isNull(P.build([exam(1, 6, seen.slice(0, 3))], [], today).mockExamId);
});

test('forecast covers the days up to the last exam', () => {
  const cards = [...times(10, fresh), later()];
  const p = P.build([exam(1, 6, cards)], [], today, 60);
  eq(p.forecast.length, 7);
  eq(p.forecast[0].date, today);
  eq(p.forecast[6].date, today + 6);
  eq(p.forecast.reduce((s, x) => s + x.cards, 0), 11 + 10);
  ok(p.minutesNeededPerDay > 0);
});

test('zero budget plans nothing but reports it', () => {
  const p = P.build([exam(1, 3, [due(), due()])], [], today, 0);
  eq(p.cards.length, 0);
  eq(p.deferred, 2);
});

suite('Heute-Plan: Zusatz der Web-App');

test('new cards already started today use up the quota', () => {
  const started = times(5, () => ({ ...later(), introducedToday: true }));
  const p = P.build([exam(1, 5, [...times(15, fresh), ...started])], [], today, 600);
  eq(p.cards.filter((c) => c.tier === 'NEW').length, 0, '20 over 4 days: 5 are done');
  const q = P.build([exam(1, 5, [...times(17, fresh), ...started.slice(0, 3)])], [], today, 600);
  eq(q.cards.filter((c) => c.tier === 'NEW').length, 2);
});

test('without the flag the quota stays as in Kotlin', () => {
  eq(P.build([exam(1, 5, times(15, fresh))], [], today, 600).cards.length, 4);
});

suite('ReadinessTest');

const examDay = today + 10;
const solid = (interval = 30, ago = 2) => newSchedule({ reps: 4, intervalDays: interval, dueEpochDay: d - ago + interval, lastReviewEpochDay: d - ago });
const input = (cards, { mocks = [], target = 10, topics = [], cardTopics = [], lt = 0, ld = 0 } = {}) => ({ cards, cardTopics, topics, mocks, linksTotal: lt, linksDone: ld, cardTarget: target, today, examDate: examDay });
const gapOf = (r, kind) => r.gaps.find((g) => g.kind === kind);

test('no basis gives no number', () => {
  const r = R.estimate(input([]));
  isNull(r.percent);
  isNull(r.grade);
  eq(r.confidence, 'NONE');
  eq(r.gaps[0].kind, 'NO_CARDS');
  isNull(R.estimate(input(times(3, solid))).percent);
});

test('new cards know nothing', () => {
  eq(R.recall(newSchedule(), today, examDay), 0.0);
});

test('recall follows the interval', () => {
  near(R.recall(newSchedule({ reps: 3, intervalDays: 10, lastReviewEpochDay: d }), today, examDay), 0.9);
  const short = R.recall(newSchedule({ reps: 1, intervalDays: 1, lastReviewEpochDay: d }), today, examDay);
  ok(short < 0.5, `a one-day card is gone in ten days: ${short}`);
  const failed = R.recall(newSchedule({ reps: 0, lapses: 1, lastReviewEpochDay: d }), today, examDay);
  ok(failed < short);
});

test('past exam date counts from today', () => {
  near(R.recall(newSchedule({ reps: 3, intervalDays: 10, lastReviewEpochDay: d }), today, today - 5), 1.0);
});

test('solid cards without a mock stay capped and low confidence', () => {
  const r = R.estimate(input(times(10, () => solid(300))));
  notNull(r.percent);
  ok(r.percent <= 85);
  eq(r.confidence, 'LOW');
  ok(gapOf(r, 'NO_MOCK'));
  eq(r.percent - r.low, 20);
});

test('a good mock lifts the forecast and narrows the range', () => {
  const cards = times(10, () => solid(20));
  const without = R.estimate(input(cards));
  const withMock = R.estimate(input(cards, { mocks: [{ percent: 95.0, daysAgo: 1, asked: 20 }] }));
  ok(withMock.percent > without.percent);
  eq(withMock.confidence, 'HIGH');
  ok(withMock.high - withMock.low < without.high - without.low);
});

test('a bad mock pulls it down even with good cards', () => {
  const r = R.estimate(input(times(10, () => solid(60)), { mocks: [{ percent: 30.0, daysAgo: 1, asked: 30 }] }));
  ok(r.percent < 60, `percent ${r.percent}`);
  ok(gapOf(r, 'MOCK_LOW'));
});

test('old mocks do not count and are named', () => {
  const r = R.estimate(input(times(10, solid), { mocks: [{ percent: 95.0, daysAgo: 40, asked: 30 }] }));
  isNull(r.mockPercent);
  eq(gapOf(r, 'OLD_MOCK').count, 40);
});

test('newer and longer mocks weigh more', () => {
  const r = R.estimate(input(times(10, solid), { mocks: [{ percent: 90.0, daysAgo: 1, asked: 30 }, { percent: 40.0, daysAgo: 15, asked: 5 }] }));
  ok(r.mockPercent > 80);
});

test('few cards reduce coverage and the forecast', () => {
  const full = R.estimate(input(times(10, solid)));
  const thin = R.estimate(input(times(5, solid), { target: 40 }));
  ok(thin.coveragePercent < full.coveragePercent);
  ok(thin.percent < full.percent);
  eq(gapOf(thin, 'FEW_CARDS').count, 35);
});

test('missing topics are named', () => {
  const r = R.estimate(input(times(6, solid), { topics: ['Trinkwasser', 'Heizung'], cardTopics: times(6, () => 'Trinkwasser') }));
  eq(gapOf(r, 'TOPICS_MISSING').names, ['Heizung']);
});

test('topic with unseen cards counts as weak not missing', () => {
  const cards = [...times(6, solid), newSchedule()];
  const r = R.estimate(input(cards, { topics: ['A', 'B'], cardTopics: [...times(6, () => 'A'), 'B'] }));
  eq(gapOf(r, 'TOPICS_WEAK').names, ['B']);
  no(gapOf(r, 'TOPICS_MISSING'));
});

test('open material and due and new cards are listed', () => {
  const cards = [...times(6, solid), newSchedule(), newSchedule({ reps: 2, intervalDays: 3, dueEpochDay: d - 1, lastReviewEpochDay: d - 4 })];
  const r = R.estimate(input(cards, { lt: 4, ld: 1 }));
  eq(gapOf(r, 'NEW_CARDS').count, 1);
  eq(gapOf(r, 'DUE_CARDS').count, 1);
  eq(gapOf(r, 'LINKS_OPEN').count, 3);
});

test('grade follows the percent and bounds hold', () => {
  const r = R.estimate(input(times(10, solid), { mocks: [{ percent: 100.0, daysAgo: 0, asked: 30 }] }));
  eq(r.grade, grade(r.percent));
  ok(r.high <= 100);
  const z = R.estimate(input(times(10, () => newSchedule()), { mocks: [{ percent: 0.0, daysAgo: 0, asked: 10 }] }));
  eq(z.percent, 0);
  eq(z.low, 0);
  eq(z.grade, 6);
});

test('mock only basis works without cards', () => {
  const r = R.estimate(input([], { mocks: [{ percent: 80.0, daysAgo: 1, asked: 10 }] }));
  notNull(r.percent);
  eq(r.confidence, 'MEDIUM');
});

suite('ErrorBookTest');

const a = (cardId, epochDay, at, right) => ({ cardId, epochDay, at, right });

test('two failures in the first sitting are normal', () => {
  eq(buildErrorBook([a(1, 10, 1, false), a(1, 10, 2, false), a(1, 10, 3, true)]).length, 0);
});

test('wrong on two days puts it on the list', () => {
  const e = buildErrorBook([a(1, 10, 1, false), a(1, 12, 2, false)])[0];
  eq(e.wrongDays, 2);
  eq(e.lastWrongDay, 12);
});

test('three times wrong on one day puts it on the list too', () => {
  eq(buildErrorBook([a(1, 10, 1, false), a(1, 10, 2, false), a(1, 10, 3, false)]).length, 1);
});

test('three right in a row clear it whatever the input order', () => {
  const base = [a(1, 10, 1, false), a(1, 11, 2, false)];
  eq(buildErrorBook([...base, a(1, 12, 3, true), a(1, 13, 4, true)]).length, 1);
  eq(buildErrorBook(shuffled([...base, a(1, 12, 3, true), a(1, 13, 4, true), a(1, 14, 5, true)], seeded(4))).length, 0);
  eq(buildErrorBook([...base, a(1, 12, 3, true), a(1, 13, 4, true), a(1, 14, 5, false), a(1, 15, 6, true)]).length, 1);
});

test('worst first', () => {
  const list = buildErrorBook([a(1, 10, 1, false), a(1, 11, 2, false), a(2, 10, 3, false), a(2, 11, 4, false), a(2, 12, 5, false)]);
  eq(list.map((e) => e.cardId), [2, 1]);
});

test('only again is wrong', () => {
  no(isRight(Rating.AGAIN));
  ok(isRight(Rating.HARD));
});

suite('FocusTimerTest');

const plan = { focus: 25, shortBreak: 5, longBreak: 15, blocksUntilLong: 4 };

test('first block counts down', () => {
  const s = focusAt(plan, 60);
  no(s.inBreak);
  eq(s.block, 1);
  eq(s.secondsLeft, 24 * 60);
  eq(s.focusSeconds, 60);
});

test('break follows and does not count as focus', () => {
  const s = focusAt(plan, 26 * 60);
  ok(s.inBreak);
  no(s.longBreak);
  eq(s.block, 1);
  eq(s.secondsLeft, 4 * 60);
  eq(s.focusSeconds, 25 * 60);
});

test('second block starts after the break', () => {
  const s = focusAt(plan, 30 * 60);
  no(s.inBreak);
  eq(s.block, 2);
  eq(s.focusSeconds, 25 * 60);
});

test('every fourth break is long', () => {
  const end = 4 * 25 * 60 + 3 * 5 * 60;
  const s = focusAt(plan, end + 1);
  ok(s.longBreak);
  eq(s.block, 4);
  eq(s.secondsLeft, 15 * 60 - 1);
  eq(focusAt(plan, end + 15 * 60).block, 5);
});

test('negative time is the start and no break plans work', () => {
  eq(focusAt(plan, -5).block, 1);
  eq(focusAt({ focus: 25, shortBreak: 0, longBreak: 0, blocksUntilLong: 4 }, 25 * 60).block, 2);
});

suite('StatisticsTest');

test('days include gaps and the span ends today', () => {
  const s = summary([{ epochDay: d, deckId: 1, right: true }, { epochDay: d - 2, deckId: 1, right: false }], new Map(), today, 7);
  eq(s.days.length, 7);
  eq(s.days[6].date, today);
  eq(s.days[6].answers, 1);
  eq(s.days[6].percent, 100);
  eq(s.days[5].answers, 0);
  isNull(s.days[5].percent);
  eq(s.activeDays, 2);
});

test('older answers stay out', () => {
  eq(summary([{ epochDay: d - 30, deckId: 1, right: true }], new Map(), today, 7).answers, 0);
});

test('hit rate per deck weakest first', () => {
  const s = summary([
    { epochDay: d, deckId: 1, right: true }, { epochDay: d, deckId: 1, right: true },
    { epochDay: d, deckId: 2, right: true }, { epochDay: d, deckId: 2, right: false },
  ], new Map(), today, 7);
  eq(s.decks.map((x) => x.deckId), [2, 1]);
  eq(s.decks[0].percent, 50);
  eq(s.right, 3);
});

test('focus counts as activity and adds up', () => {
  const s = summary([], new Map([[d, 25], [d - 1, 50], [d - 10, 99]]), today, 7);
  eq(s.focusMinutes, 75);
  eq(s.activeDays, 2);
  eq(s.bestStreak, 2);
});
