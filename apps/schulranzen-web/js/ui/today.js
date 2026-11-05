// "Heute": what to do now, drawn from all exams, with Prüfungsreife at a glance.
import { h, btn, linkBtn, plates, plural, subjectColor, sectionHead, toast } from './dom.js';
import { app } from '../app.js';
import { backupBanner, installBanner } from './banners.js';
import { todayPlan, readinessOf, examKindLabel } from '../core/study.js';
import { DEFAULT_MINUTES, SECONDS_REVIEW } from '../core/todayplan.js';
import { streak } from '../core/progress.js';
import { countdown, dateShort } from '../core/dates.js';
import { saveExam } from '../data/repo.js';
import { importSample } from './packs.js';
import { editDeck } from './decks.js';
import { editExam } from './exams.js';
import { gradeLine } from './texts.js';

const BUDGETS = [[15, '15 Min.'], [30, '30 Min.'], [45, '45 Min.'], [60, '60 Min.']];

/** Minutes the answers of today already took, roughly; they count against the day's time. */
export function minutesDoneToday(state, today) {
  const n = state.reviews.filter((r) => r.epochDay === today).length;
  return Math.floor((n * SECONDS_REVIEW) / 60);
}

function firstSteps() {
  return h('div.empty', {},
    h('h1.title', {}, 'Willkommen'),
    h('p', {}, 'Noch ist dein Ranzen leer. Schau dir das kleine Beispiel an oder fang gleich mit eigenen Karten an.'),
    h('div.row', { css: { justifyContent: 'center' } },
      btn('Beispiel laden', importSample, { kind: 'signal', icon: 'download' }),
      btn('Neuer Stapel', () => editDeck(), { icon: 'plus' }),
      btn('Prüfung eintragen', () => editExam(), { icon: 'calendar' })),
    h('p.caption', {}, 'Kein Konto, kein Server: Deine Daten bleiben auf diesem Gerät.'));
}

function counters(state, today, due) {
  const reviewDays = new Set(state.reviews.map((r) => r.epochDay));
  const box = (value, label) => h('div.counter', {}, h('b', {}, value), h('span', {}, label));
  return h('div.counters', {},
    box(String(state.reviews.filter((r) => r.epochDay === today).length).padStart(2, '0'), 'Heute gelernt'),
    box(String(due).padStart(2, '0'), 'Fällig'),
    box(String(streak(reviewDays, today)).padStart(2, '0'), 'Tage Streak'));
}

function examShareRow(share, exam, today) {
  const bits = [];
  if (share.due > 0) bits.push(`${share.due} fällig`);
  if (share.fresh > 0) bits.push(`${share.fresh} neu`);
  if (share.weak > 0) bits.push(`${share.weak} schwach`);
  if (share.links > 0) bits.push(plural(share.links, 'Stoff', 'Stoffe'));
  if (share.deferred > 0) bits.push(`${share.deferred} warten`);
  return h('a.list-row', { href: `#/pruefungen/${exam.id}`, vars: { '--c': subjectColor(exam.colorIndex) } },
    h('span.list-edge'),
    h('span.list-main', {},
      h('span.list-title', { css: { display: 'block' } }, exam.subject),
      h('span.list-sub', {}, bits.length > 0 ? bits.join(' · ') : 'Heute nichts Neues')),
    h('span.mono', { class: exam.dateEpochDay - today <= 3 ? 'signal-text' : 'muted' }, countdown(today, exam.dateEpochDay)));
}

function materialRow(exam, item) {
  return h('div.list-row', { vars: { '--c': subjectColor(exam.colorIndex) } },
    h('span.list-edge'),
    h('div.list-main', {}, h('div.list-title', {}, item.label), h('div.list-sub', {}, `Stoff für ${exam.subject}`)),
    btn('Erledigt', async () => {
      const material = (exam.material ?? []).map((m) => (m.id === item.id ? { ...m, doneAt: Date.now() } : m));
      await saveExam({ ...exam, material });
      toast('Eintrag erledigt.');
      app.refresh();
    }, { icon: 'check' }));
}

function readinessRow(exam, state, today) {
  const r = readinessOf(exam, state, today);
  return h('a.list-row', { href: `#/pruefungen/${exam.id}`, vars: { '--c': subjectColor(exam.colorIndex) } },
    h('span.list-edge'),
    h('span.list-main', {},
      h('span.list-title', { css: { display: 'block' } }, `${exam.subject} · ${examKindLabel(exam.kind)}`),
      h('span.list-sub', {}, r.percent === null ? `${dateShort(exam.dateEpochDay)} · Noch keine Grundlage` : `${dateShort(exam.dateEpochDay)} · Erwartet: ${gradeLine(r.grade)}`)),
    h('span.mono-large', { css: { fontSize: '22px', lineHeight: '26px' } }, r.percent === null ? '–' : `${r.percent} %`));
}

export async function renderToday(root) {
  const { state, today } = app;
  if (state.cards.length === 0 && state.exams.length === 0) {
    root.append(h('div.stack.stack-lg', {}, firstSteps(), installBanner()));
    return;
  }
  const budget = app.setting('budgetMinutes', DEFAULT_MINUTES);
  const done = minutesDoneToday(state, today);
  const left = Math.max(0, budget - done);
  const plan = todayPlan(state, today, left);
  const exams = new Map(state.exams.map((e) => [e.id, e]));
  const everything = todayPlan(state, today, 100000);
  const coming = state.exams.filter((e) => e.dateEpochDay >= today);

  const dueAll = state.cards.filter((c) => c.lastReviewEpochDay !== null && c.lastReviewEpochDay !== undefined && c.dueEpochDay <= today).length;

  const startArea = h('div.start');
  if (plan.cards.length > 0) {
    startArea.append(
      h('div.start-sum', {}, `${plural(plan.cards.length, 'Karte', 'Karten')} · ca. ${plan.minutes} Min.`),
      linkBtn('Jetzt lernen', '#/lernen/heute', { kind: 'signal', big: true, icon: 'play', wide: true }),
    );
    if (plan.deferred > 0) startArea.append(h('p.caption.muted', {}, `${plural(plan.deferred, 'Karte passt', 'Karten passen')} heute nicht mehr in die Zeit und bleiben für morgen.`));
  } else if (everything.cards.length > 0) {
    startArea.append(
      h('p.title', {}, 'Für heute reicht es'),
      h('p.muted', {}, `Deine ${budget} Minuten sind um. ${plural(everything.cards.length, 'Karte wartet', 'Karten warten')} noch, wenn du magst.`),
      linkBtn('Trotzdem weiterlernen', '#/lernen/alles', { icon: 'play' }),
    );
  } else {
    startArea.append(h('p.title', {}, 'Alles erledigt'), h('p.muted', {}, plan.links.length > 0 || plan.mockExamId !== null ? 'Karten sind durch. Unten wartet noch etwas.' : 'Für heute ist alles wiederholt. Morgen geht es weiter.'));
  }

  const needed = plan.minutesNeededPerDay;
  const tight = needed > budget;

  const planShares = plan.shares.filter((s) => s.due + s.fresh + s.weak + s.links + s.deferred > 0);

  const links = plan.links.map(([examId, id]) => {
    const e = exams.get(examId);
    const item = (e.material ?? []).find((m) => m.id === id);
    return item ? materialRow(e, item) : null;
  });

  const mockExam = plan.mockExamId !== null ? exams.get(plan.mockExamId) : null;

  root.append(h('div.stack.stack-lg', {},
    backupBanner(),
    h('h1.sr', {}, 'Heute'),
    h('div.grid-2', {},
      h('div.stack', {},
        startArea,
        h('div.field', {}, h('div.field-label', {}, 'Wie viel Zeit hast du heute?'),
          plates('Lernzeit am Tag', BUDGETS, budget, async (v) => { await app.set('budgetMinutes', v); app.refresh(); })),
        tight ? h('p.caption.signal-text', {}, `Das wird knapp: An manchen Tagen bis zur Prüfung brauchst du etwa ${needed} Minuten.`) : null),
      h('div.stack', {},
        counters(state, today, dueAll),
        h('div.row', {}, linkBtn('Fehlerheft', '#/fehler', { kind: 'quiet', icon: 'error' }), linkBtn('Statistik', '#/statistik', { kind: 'quiet', icon: 'chart' })))),
    planShares.length > 0 ? h('section.stack', {}, sectionHead('Heute dran', planShares.length),
      h('div.rows', {}, planShares.map((s) => examShareRow(s, exams.get(s.examId), today)))) : null,
    links.length > 0 ? h('section.stack', {}, sectionHead('Stoff durcharbeiten', links.length), h('div.rows', {}, links)) : null,
    mockExam ? h('div.panel', {}, h('div.row-between', {},
      h('div.grow', {}, h('p.label', {}, `Probeprüfung für ${mockExam.subject}`), h('p.caption.muted', {}, 'Jetzt am sinnvollsten: Sie zeigt ehrlich, wo du stehst.')),
      linkBtn('Probeprüfung', `#/probe/${mockExam.id}`, { icon: 'timer' }))) : null,
    coming.length > 0 ? h('section.stack', {}, sectionHead('Prüfungsreife', coming.length),
      h('div.rows', {}, coming.slice(0, 5).map((e) => readinessRow(e, state, today)))) : h('div.panel-flat', {}, h('div.row-between', {},
      h('p.grow.muted', {}, 'Trag eine Prüfung ein, dann teilt die App den Stoff auf die Tage auf.'),
      btn('Prüfung eintragen', () => editExam(), { icon: 'calendar' }))),
    installBanner(),
  ));
}
