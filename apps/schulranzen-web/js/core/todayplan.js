// "Jetzt lernen": everything open over all coming exams becomes one session for today.
// Same rules and numbers as TodayPlan.kt. Reviews come before new cards, a close exam
// before a far one, new cards are spread so the eve stays free, and time is the hard
// limit: what does not fit is reported, not hidden.
//
// One addition, off by default: PlanCard.introducedToday. Without it a second look at the
// plan on the same day hands out a fresh quota of new cards, because the quota is computed
// from what is still new. See docs/AUDIT.md.
import { isNew } from './repetition.js';
import { maxBy, sum } from './text.js';

export const DEFAULT_MINUTES = 30;
export const SECONDS_REVIEW = 25;
export const SECONDS_NEW = 45;
export const SECONDS_WEAK = 40;
export const MINUTES_LINK = 10;
const MOCK_WINDOW_DAYS = 10;
const MOCK_REPEAT_DAYS = 4;
const MIN_CARDS_FOR_MOCK = 5;
const WEAK_EXTRA_DAYS = 7;

export const Tier = Object.freeze({ WEAK_DUE: 'WEAK_DUE', DUE: 'DUE', NEW: 'NEW', WEAK_EXTRA: 'WEAK_EXTRA', OTHER_DUE: 'OTHER_DUE' });

/** How much an exam counts relative to a class test; by name of its kind. */
export function weightOf(kind) {
  if (kind === 'GP1' || kind === 'GP2') return 3.0;
  if (kind === 'KLASSENARBEIT' || kind === 'LERNFELD' || kind === 'PRAXIS') return 1.5;
  if (kind === 'REFERAT') return 0.75;
  return 1.0;
}

/** Higher means first: closeness to the date, scaled by how much the exam counts. */
export function urgency(exam, today) {
  const days = Math.max(0, exam.date - today);
  return exam.weight / (days + 1.0);
}

/** Days on which new cards can still be introduced; the eve stays for repetition. */
export function daysForNew(today, date) {
  const left = date - today;
  return left <= 1 ? 1 : left - 1;
}

function costOf(tier) {
  if (tier === Tier.WEAK_DUE || tier === Tier.WEAK_EXTRA) return SECONDS_WEAK;
  if (tier === Tier.NEW) return SECONDS_NEW;
  return SECONDS_REVIEW;
}

/**
 * exams: { id, date, weight, cards: [{ id, schedule, weak, introducedToday }], openLinkIds, daysSinceMock }
 * otherDue: due cards of decks without an exam; they only get what time is left.
 */
export function build(exams, otherDue, today, budgetMinutes = DEFAULT_MINUTES) {
  const coming = exams.filter((e) => e.date >= today)
    .map((e, i) => ({ e, i }))
    .sort((a, b) => urgency(b.e, today) - urgency(a.e, today) || a.i - b.i)
    .map((x) => x.e);
  // A deck can serve two exams; a card is learnt once, for the more urgent one.
  const owner = new Map();
  for (const e of coming) for (const c of e.cards) if (!owner.has(c.id)) owner.set(c.id, e.id);
  const owned = new Map(coming.map((e) => [e.id, e.cards.filter((c) => owner.get(c.id) === e.id)]));

  let left = Math.max(0, budgetMinutes) * 60;

  // Material first, at most its fair share a day: it feeds everything else.
  const links = [];
  const linkCount = new Map();
  for (const e of coming) {
    const days = daysForNew(today, e.date);
    const open = e.openLinkIds ?? [];
    const share = Math.ceil(open.length / days);
    for (const id of open.slice(0, share)) {
      const cost = MINUTES_LINK * 60;
      // Keep at least half the time for cards, unless there are none.
      if (left - cost < budgetMinutes * 30 && links.length > 0) break;
      links.push([e.id, id]);
      linkCount.set(e.id, (linkCount.get(e.id) ?? 0) + 1);
      left -= cost;
    }
  }

  const tiers = new Map();
  for (const e of coming) {
    const cards = owned.get(e.id);
    const close = e.date - today <= WEAK_EXTRA_DAYS;
    const due = cards.filter((c) => !isNew(c.schedule) && c.schedule.dueEpochDay <= today);
    const fresh = cards.filter((c) => isNew(c.schedule));
    const introduced = cards.filter((c) => c.introducedToday).length;
    const quota = Math.max(0, Math.ceil((fresh.length + introduced) / daysForNew(today, e.date)) - introduced);
    const byDue = (a, b) => a.schedule.dueEpochDay - b.schedule.dueEpochDay;
    tiers.set(e.id, {
      [Tier.WEAK_DUE]: due.filter((c) => c.weak).sort(byDue),
      [Tier.DUE]: due.filter((c) => !c.weak).sort(byDue),
      [Tier.NEW]: fresh.slice(0, quota),
      [Tier.WEAK_EXTRA]: close ? cards.filter((c) => c.weak && !isNew(c.schedule) && c.schedule.dueEpochDay > today) : [],
    });
  }

  const session = [];
  const deferred = new Map();
  for (const tier of [Tier.WEAK_DUE, Tier.DUE, Tier.NEW, Tier.WEAK_EXTRA]) {
    if (coming.length === 0) break;
    // Within a tier the exams share the time by urgency, so a far exam is not starved
    // to zero, but the near one gets the larger part.
    const queues = coming.map((e) => ({ e, q: tiers.get(e.id)[tier].slice() }));
    const total = Math.max(1e-9, sum(coming.map((e) => urgency(e, today))));
    const credit = new Map();
    while (queues.some((x) => x.q.length > 0)) {
      for (const { e } of queues) credit.set(e.id, (credit.get(e.id) ?? 0.0) + urgency(e, today) / total);
      const pick = maxBy(queues.filter((x) => x.q.length > 0), (x) => credit.get(x.e.id) ?? 0.0);
      credit.set(pick.e.id, (credit.get(pick.e.id) ?? 0.0) - 1.0);
      const c = pick.q.shift();
      if (left >= costOf(tier)) {
        left -= costOf(tier);
        session.push({ cardId: c.id, examId: pick.e.id, tier });
      } else {
        deferred.set(pick.e.id, (deferred.get(pick.e.id) ?? 0) + 1);
      }
    }
  }
  let otherDeferred = 0;
  const others = otherDue.filter((c) => !owner.has(c.id)).sort((a, b) => a.schedule.dueEpochDay - b.schedule.dueEpochDay);
  for (const c of others) {
    const cost = isNew(c.schedule) ? SECONDS_NEW : SECONDS_REVIEW;
    if (left >= cost) {
      left -= cost;
      session.push({ cardId: c.id, examId: null, tier: Tier.OTHER_DUE });
    } else otherDeferred++;
  }

  const mock = coming.find((e) => {
    const mine = owned.get(e.id);
    const days = e.date - today;
    return mine.length >= MIN_CARDS_FOR_MOCK && days >= 1 && days <= MOCK_WINDOW_DAYS
      && (e.daysSinceMock === null || e.daysSinceMock === undefined || e.daysSinceMock >= MOCK_REPEAT_DAYS)
      // Writing a mock exam on cards never seen tells nothing.
      && mine.filter((c) => !isNew(c.schedule)).length * 2 >= mine.length;
  })?.id ?? null;

  const shares = coming.map((e) => {
    const mine = session.filter((s) => s.examId === e.id);
    return {
      examId: e.id,
      due: mine.filter((s) => s.tier === Tier.DUE || s.tier === Tier.WEAK_DUE).length,
      fresh: mine.filter((s) => s.tier === Tier.NEW).length,
      weak: mine.filter((s) => s.tier === Tier.WEAK_DUE || s.tier === Tier.WEAK_EXTRA).length,
      links: linkCount.get(e.id) ?? 0,
      deferred: deferred.get(e.id) ?? 0,
    };
  });
  const load = forecast(coming.map((e) => ({ ...e, cards: owned.get(e.id) })), otherDue, today);
  const used = Math.max(0, budgetMinutes) * 60 - left;
  return {
    cards: session,
    links,
    shares,
    mockExamId: mock,
    minutes: Math.ceil(used / 60.0),
    budgetMinutes,
    deferred: sum([...deferred.values()]) + otherDeferred,
    forecast: load,
    minutesNeededPerDay: load.length === 0 ? 0 : Math.max(...load.map((d) => d.minutes)),
    get isEmpty() { return this.cards.length === 0 && this.links.length === 0 && this.mockExamId === null; },
  };
}

/**
 * Rough work per day: reviews where they are already scheduled, plus the even share of
 * new cards and material, each new card coming back about a day later. An estimate for
 * "wird das knapp?", not a promise.
 */
export function forecast(exams, otherDue, today) {
  if (exams.length === 0) return [];
  const last = Math.max(...exams.map((e) => e.date));
  const horizon = Math.min(last - today, 60);
  const cards = new Array(horizon + 1).fill(0);
  const seconds = new Array(horizon + 1).fill(0);
  const add = (i, n, each) => {
    if (i >= 0 && i <= horizon) { cards[i] += n; seconds[i] += n * each; }
  };
  for (const c of otherDue) {
    if (!isNew(c.schedule)) add(Math.max(0, c.schedule.dueEpochDay - today), 1, SECONDS_REVIEW);
  }
  for (const e of exams) {
    const end = Math.min(Math.max(0, e.date - today), horizon);
    for (const c of e.cards) {
      if (isNew(c.schedule)) continue;
      const at = Math.max(0, c.schedule.dueEpochDay - today);
      if (at <= end) add(at, 1, c.weak ? SECONDS_WEAK : SECONDS_REVIEW);
    }
    const fresh = e.cards.filter((c) => isNew(c.schedule)).length;
    const days = Math.min(daysForNew(today, e.date), Math.max(1, end));
    const links = (e.openLinkIds ?? []).length;
    for (let i = 0; i < days; i++) {
      const n = Math.floor(fresh / days) + (i < fresh % days ? 1 : 0);
      add(i, n, SECONDS_NEW);
      add(i + 1, n, SECONDS_REVIEW);
      const l = Math.floor(links / days) + (i < links % days ? 1 : 0);
      if (i >= 0 && i <= horizon) seconds[i] += l * MINUTES_LINK * 60;
    }
  }
  return cards.map((n, i) => ({ date: today + i, cards: n, minutes: Math.ceil(seconds[i] / 60.0) }));
}
