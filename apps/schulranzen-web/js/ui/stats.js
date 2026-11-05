// Statistik: daily answers as stacked columns (right/wrong share one axis), headline tiles,
// a table as the accessible twin of the chart, hit rate per deck.
import { h, plates, bar, sectionHead, subjectColor } from './dom.js';
import { app } from '../app.js';
import { mastery, isRight, streak, summary } from '../core/progress.js';
import { scheduleOf } from '../core/study.js';
import { dateLong, dateShort, weekdayShort } from '../core/dates.js';

let span = 30;

function chart(s) {
  const max = Math.max(1, ...s.days.map((d) => d.answers));
  const read = h('p.caption.muted', { 'aria-live': 'off', css: { minHeight: '16px' } }, 'Fahre über eine Säule oder tippe sie an.');
  const show = (d) => {
    read.textContent = d.answers === 0 && d.focusMinutes === 0
      ? `${dateShort(d.date)}: nichts`
      : `${weekdayShort(d.date)} ${dateShort(d.date)}: ${d.answers} Antworten, ${d.right} richtig${d.focusMinutes ? `, ${d.focusMinutes} Min. Fokus` : ''}`;
  };
  const cols = s.days.map((d) => {
    const wrong = d.answers - d.right;
    const pct = (n) => `${(n * 100) / max}%`;
    return h('div.chart-col', { onmouseenter: () => show(d), onclick: () => show(d) },
      wrong > 0 ? h('i.wrong', { css: { height: pct(wrong), marginBottom: d.right > 0 ? '2px' : '0' } }) : null,
      d.right > 0 ? h('i', { css: { height: pct(d.right) } }) : null);
  });
  return h('figure.chart', { css: { margin: '0' } },
    h('div.row-between', {},
      h('div.legend', {}, h('span', {}, h('i', { vars: { '--c': 'var(--ink)' } }), 'Richtig'), h('span', {}, h('i', { vars: { '--c': 'var(--signal)' } }), 'Nochmal')),
      h('span.mono.muted', { css: { fontSize: '12px' } }, `höchstens ${max}`)),
    read,
    h('div.chart-bars', { 'aria-hidden': 'true' }, cols),
    h('div.chart-axis', { 'aria-hidden': 'true' }, h('span', {}, dateShort(s.days[0].date)), h('span', {}, 'heute')),
    h('figcaption.sr', {}, 'Antworten pro Tag. Die Tabelle darunter enthält dieselben Zahlen.'));
}

function table(s) {
  const rows = s.days.filter((d) => d.answers > 0 || d.focusMinutes > 0).reverse();
  return h('details', {}, h('summary.label.muted', { css: { minHeight: '44px', cursor: 'pointer' } }, 'Als Tabelle zeigen'),
    rows.length === 0 ? h('p.muted', {}, 'Noch nichts gelernt.') : h('table.table-simple', {},
      h('thead', {}, h('tr', {}, ['Tag', 'Antworten', 'Richtig', 'Fokus'].map((t) => h('th', { scope: 'col' }, t)))),
      h('tbody', {}, rows.map((d) => h('tr', {}, h('td', {}, dateLong(d.date)), h('td', {}, String(d.answers)), h('td', {}, d.percent === null ? '–' : `${d.percent} %`), h('td', {}, `${d.focusMinutes} Min.`))))));
}

export async function renderStats(root) {
  const { state, today } = app;
  const answers = state.reviews.map((r) => ({ epochDay: r.epochDay, deckId: r.deckId, right: isRight(r.rating) }));
  const focus = new Map();
  for (const f of state.focus) focus.set(f.epochDay, (focus.get(f.epochDay) ?? 0) + f.minutes);
  const s = summary(answers, focus, today, span);
  const reviewDays = new Set(state.reviews.map((r) => r.epochDay));
  const box = (v, l) => h('div.counter', {}, h('b', {}, v), h('span', {}, l));
  const decks = new Map(state.decks.map((d) => [d.id, d]));
  root.append(h('div.stack.stack-lg', {},
    h('div.row-between', {}, h('h1.title', {}, 'Statistik'),
      plates('Zeitraum', [[7, '7 Tage'], [30, '30 Tage'], [90, '90 Tage']], span, (v) => { span = v; app.refresh(); })),
    h('div.counters', {},
      box(String(streak(reviewDays, today)), 'Tage Streak'),
      box(String(s.bestStreak), 'Bester Lauf'),
      box(`${mastery(state.cards.map(scheduleOf))} %`, 'Aus der Lernphase')),
    h('div.counters', {},
      box(String(s.answers), 'Antworten'),
      box(s.answers === 0 ? '–' : `${Math.trunc((s.right * 100) / s.answers)} %`, 'Richtig'),
      box(`${s.focusMinutes}`, 'Min. Fokus')),
    chart(s),
    table(s),
    h('section.stack', {}, sectionHead('Stapel, schwächster zuerst', s.decks.length),
      s.decks.length === 0 ? h('p.muted', {}, 'Noch keine Antworten in diesem Zeitraum.') : s.decks.map((x) => {
        const d = decks.get(x.deckId);
        return h('div.stack', { vars: { '--gap': '4px' } },
          h('div.row-between', {}, h('span.label', {}, d?.name ?? 'Gelöschter Stapel'), h('span.mono', {}, `${x.percent} % · ${x.answers}`)),
          bar(x.percent, subjectColor(d?.colorIndex ?? 7)));
      }))));
}
