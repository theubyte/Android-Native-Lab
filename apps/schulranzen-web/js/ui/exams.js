// Prüfungen: list, editor with date, topics and material, detail with Prüfungsreife and path.
import { h, fill, btn, key, icon, field, plates, colorPicker, checkbox, openPanel, ask, sectionHead, subjectColor, ring, bar, linkBtn, plural } from './dom.js';
import { app } from '../app.js';
import * as repo from '../data/repo.js';
import { EXAM_KINDS, examCards, examDecks, examKindLabel, knownSubjects, pathOf, readinessOf, topicOfCard } from '../core/study.js';
import { countdown, dateLong, dateShort, isoOf, parseIso, weekdayShort } from '../core/dates.js';
import { plan as studyDays } from '../core/examplan.js';
import { GRADE_WORDS, trend } from '../core/mockexam.js';
import { confidenceText, gapText, gradeLine, stageDetail, stageTitle } from './texts.js';
import { editDeck } from './decks.js';

const lines = (s) => String(s ?? '').split('\n').map((x) => x.trim()).filter(Boolean);

export function editExam(exam = null) {
  const state = app.state;
  const today = app.today;
  const draft = {
    subject: exam?.subject ?? '', colorIndex: exam?.colorIndex ?? state.exams.length % 8, kind: exam?.kind ?? 'KLASSENARBEIT',
    date: isoOf(exam?.dateEpochDay ?? today + 14), topics: exam?.topics ?? '', material: (exam?.material ?? []).map((m) => m.label).join('\n'),
    deckIds: exam?.deckIds ?? null,
  };
  return openPanel(exam ? 'Prüfung bearbeiten' : 'Neue Prüfung', (close) => {
    const listId = `e${Math.random().toString(36).slice(2, 8)}`;
    const subject = h('input.input', { value: draft.subject, list: listId, 'data-autofocus': '', autocomplete: 'off', placeholder: 'z. B. Mathe', oninput: (e) => { draft.subject = e.target.value; drawDecks(); } });
    const date = h('input.input', { type: 'date', value: draft.date, oninput: (e) => { draft.date = e.target.value; } });
    const error = h('p.caption.signal-text', { role: 'alert' });
    const deckBox = h('div.stack', { vars: { '--gap': '0' } });
    const auto = () => examDecks({ subject: draft.subject, deckIds: null }, state.decks).map((d) => d.id);
    const drawDecks = () => {
      if (state.decks.length === 0) { fill(deckBox, h('p.caption.muted', {}, 'Noch keine Stapel.')); return; }
      const on = new Set(draft.deckIds ?? auto());
      fill(deckBox, ...state.decks.map((d) => checkbox(d.name, on.has(d.id), (v) => {
        const now = new Set(draft.deckIds ?? auto());
        if (v) now.add(d.id); else now.delete(d.id);
        draft.deckIds = [...now];
      }, d.subject || null)));
    };
    drawDecks();
    return h('form.stack', {
      onsubmit: async (e) => {
        e.preventDefault();
        const day = parseIso(draft.date);
        if (!draft.subject.trim()) { error.textContent = 'Welches Fach? Das Feld ist leer.'; subject.focus(); return; }
        if (day === null) { error.textContent = 'Bitte ein Datum wählen.'; date.focus(); return; }
        const old = new Map((exam?.material ?? []).map((m) => [m.label, m]));
        const material = lines(draft.material).map((label) => old.get(label) ?? { id: repo.newId(), label, doneAt: null });
        // An unchanged selection stays automatic, so a later deck of this subject joins by itself.
        const autoSet = auto().slice().sort().join();
        const deckIds = draft.deckIds && draft.deckIds.slice().sort().join() !== autoSet ? draft.deckIds : null;
        const row = await repo.saveExam({
          ...(exam ?? {}), subject: draft.subject.trim(), colorIndex: draft.colorIndex, kind: draft.kind, dateEpochDay: day,
          topics: lines(draft.topics).join('\n'), material, deckIds,
        });
        close(row);
        if (exam) app.refresh(); else app.go(`/pruefungen/${row.id}`);
      },
    },
    field('Fach', subject),
    h('datalist', { id: listId }, knownSubjects(state).map((s) => h('option', { value: s.name }))),
    h('div.field', {}, h('div.field-label', {}, 'Art'), plates('Art der Prüfung', EXAM_KINDS, draft.kind, (v) => { draft.kind = v; })),
    field('Datum', date),
    h('div.field', {}, h('div.field-label', {}, 'Farbe'), colorPicker(draft.colorIndex, (i) => { draft.colorIndex = i; })),
    field('Themen', h('textarea.textarea', { rows: 3, value: draft.topics, placeholder: 'Ein Thema pro Zeile', oninput: (e) => { draft.topics = e.target.value; } }), 'Daraus entsteht die Auswertung nach Thema.'),
    field('Stoff', h('textarea.textarea', { rows: 3, value: draft.material, placeholder: 'Ein Eintrag pro Zeile, z. B. Buch S. 112–118', oninput: (e) => { draft.material = e.target.value; } }), 'Zum Abhaken. Wird auf die Tage bis zur Prüfung verteilt.'),
    h('div.field', {}, h('div.field-label', {}, 'Stapel für diese Prüfung'), deckBox),
    error,
    h('div.drawer-actions', {},
      exam ? btn('Löschen', async () => {
        const yes = await ask('Prüfung löschen?', 'Die Prüfung und ihre Probeprüfungen werden gelöscht. Karten bleiben.', 'Löschen', { danger: true });
        if (!yes) return;
        await repo.deleteExam(exam.id, state);
        close();
        app.go('/pruefungen');
      }, { kind: 'quiet', icon: 'trash' }) : null,
      btn('Abbrechen', () => close(), { kind: 'quiet' }),
      h('button.btn.btn-signal', { type: 'submit' }, 'Speichern')));
  });
}

function examRow(exam, today) {
  const days = exam.dateEpochDay - today;
  return h('a.list-row', { href: `#/pruefungen/${exam.id}`, vars: { '--c': subjectColor(exam.colorIndex) } },
    h('span.list-edge'),
    h('span.list-main', {},
      h('span.list-title', { css: { display: 'block' } }, exam.subject),
      h('span.list-sub', {}, `${examKindLabel(exam.kind)} · ${weekdayShort(exam.dateEpochDay)} ${dateShort(exam.dateEpochDay)}`)),
    h('span.mono', { class: days >= 0 && days <= 3 ? 'signal-text' : 'muted' }, countdown(today, exam.dateEpochDay).toUpperCase()));
}

export async function renderExams(root) {
  const { state, today } = app;
  const coming = state.exams.filter((e) => e.dateEpochDay >= today);
  const past = state.exams.filter((e) => e.dateEpochDay < today).reverse();
  root.append(h('div.stack.stack-lg', {},
    h('div.row-between', {},
      h('div.section-head', {}, h('h1.title', {}, 'Prüfungen'), h('span.count', {}, String(coming.length).padStart(2, '0'))),
      btn('Neue Prüfung', () => editExam(), { kind: 'signal', icon: 'plus' })),
    state.exams.length === 0 ? h('div.empty', {},
      h('p.title', {}, 'Noch keine Prüfung'),
      h('p', {}, 'Mit Datum, Themen und Stoff plant die App deine Tage und schätzt, wie gut du vorbereitet bist.'),
      btn('Prüfung eintragen', () => editExam(), { kind: 'signal', icon: 'plus' })) : null,
    coming.length > 0 ? h('div.rows', {}, coming.map((e) => examRow(e, today))) : null,
    past.length > 0 ? h('details', {}, h('summary.label.muted', { css: { minHeight: '44px', cursor: 'pointer' } }, `Vergangene Prüfungen (${past.length})`),
      h('div.rows', {}, past.map((e) => examRow(e, today)))) : null));
}

function readinessBlock(exam, state, today) {
  const r = readinessOf(exam, state, today);
  const low = r.percent !== null && r.percent < 50;
  return h('section.stack', {},
    h('h2.heading', {}, 'Prüfungsreife'),
    h('div.panel', {}, h('div.ready', {},
      ring(r.percent, r.percent === null ? '–' : `${r.percent} %`, r.percent === null ? null : `Note ${r.grade}`, low ? 'var(--signal)' : 'var(--subject-0)'),
      h('div.stack', { vars: { '--gap': '8px' } },
        h('p.label', {}, r.percent === null ? 'Noch keine Grundlage' : `Erwartet: ${gradeLine(r.grade)}`),
        r.low !== null ? h('p.caption.muted', {}, `Spanne ${r.low}–${r.high} %. ${confidenceText[r.confidence]}.`) : h('p.caption.muted', {}, 'Lerne mindestens 5 Karten oder schreibe eine Probeprüfung.'),
        meter('Gedächtnis', r.memoryPercent),
        meter('Abdeckung', r.coveragePercent),
        r.mockPercent !== null ? meter('Probeprüfung', r.mockPercent) : null))),
    h('p.caption.muted', {}, 'Eine Schätzung aus deinen eigenen Karten. Die echte Prüfung kann anderes fragen.'),
    r.gaps.length > 0 ? h('div.stack', { vars: { '--gap': '4px' } }, h('h3.label', {}, 'Was fehlt noch'),
      h('ul', { css: { margin: '0', paddingLeft: '20px' } }, r.gaps.map((g) => h('li', {}, gapText(g))))) : h('p.good-text', {}, 'Es fehlt nichts Erkennbares. Weiter so.'));
}

const meter = (label, value) => h('div.meter', {}, h('span', {}, label), bar(value, 'var(--ink-muted)'), h('span.mono', {}, `${value} %`));

function pathBlock(exam, state, today) {
  const p = pathOf(exam, state, today);
  return h('section.stack', {},
    h('div.row-between', {}, h('h2.heading', {}, 'Lernpfad'), h('span.mono.muted', {}, `${Math.round(p.progress * 100)} %`)),
    h('div.path', {}, p.stages.map((s) => h(`div.path-step.${s.state}`, {},
      h('span.path-dot', { 'aria-hidden': 'true' }, s.state === 'DONE' ? icon('check') : ''),
      h('div', {}, h('p.label', {}, stageTitle[s.kind], s.state === 'DONE' ? h('span.sr', {}, ' (erledigt)') : s.state === 'ACTIVE' ? h('span.sr', {}, ' (jetzt dran)') : null),
        h('p.caption.muted', {}, stageDetail(s)))))));
}

function materialBlock(exam) {
  const items = exam.material ?? [];
  const set = async (id, done) => {
    await repo.saveExam({ ...exam, material: items.map((m) => (m.id === id ? { ...m, doneAt: done ? Date.now() : null } : m)) });
    app.refresh();
  };
  return h('section.stack', {},
    sectionHead('Stoff', items.length),
    items.length === 0 ? h('p.muted', {}, 'Noch kein Stoff eingetragen.') : h('div', {}, items.map((m) => checkbox(m.label, !!m.doneAt, (v) => set(m.id, v)))),
    exam.sources ? h('p.pre.caption.muted', {}, exam.sources) : null);
}

function topicsBlock(exam, state) {
  const topics = lines(exam.topics);
  if (topics.length === 0) return null;
  const decks = new Map(state.decks.map((d) => [d.id, d]));
  const cards = examCards(exam, state);
  const count = (t) => cards.filter((c) => topicOfCard(c, exam, decks.get(c.deckId)) === t).length;
  return h('section.stack', {}, sectionHead('Themen', topics.length),
    h('div.rows', {}, topics.map((t) => h('div.list-row', {}, h('div.list-main', {}, h('div.list-title', {}, t)), h('span.mono.muted', {}, plural(count(t), 'Karte', 'Karten'))))));
}

function daysBlock(exam, today) {
  const days = studyDays(today, exam.dateEpochDay);
  if (days.length === 0) return null;
  const word = { START: 'Stoff sichten, Karten schreiben', REVIEW: 'Wiederholen', FINAL: 'Letzter Durchgang: nur Unsicheres' };
  return h('details', {}, h('summary.label.muted', { css: { minHeight: '44px', cursor: 'pointer' } }, `Vorgeschlagene Lerntage (${days.length})`),
    h('div.rows', {}, days.map((d) => h('div.list-row', {}, h('span.mono', {}, `${weekdayShort(d.date)} ${dateShort(d.date)}`), h('span.list-main.muted', {}, word[d.phase])))));
}

function runsBlock(exam, state) {
  const runs = state.mockRuns.filter((r) => r.examId === exam.id).sort((a, b) => b.at - a.at);
  const t = trend(runs.map((r) => r.percent));
  return h('section.stack', {},
    sectionHead('Probeprüfungen', runs.length),
    t !== null ? h('p.caption.muted', {}, t >= 0 ? `Letzte Runde ${Math.round(t)} Punkte besser.` : `Letzte Runde ${Math.round(-t)} Punkte schwächer.`) : null,
    runs.length === 0 ? h('p.muted', {}, 'Noch keine Probeprüfung. Schwache Karten kommen öfter dran.') : h('div.rows', {}, runs.map((r) => h('a.list-row', { href: `#/probe/${exam.id}/${r.id}` },
      h('span.list-main', {}, h('span.list-title', { css: { display: 'block' } }, `Note ${r.grade} · ${GRADE_WORDS[r.grade]}`), h('span.list-sub', {}, `${dateLong(r.epochDay)} · ${r.asked} Fragen`)),
      h('span.mono', {}, `${Math.trunc(r.percent)} %`)))));
}

export async function renderExam(root, id) {
  const { state, today } = app;
  const exam = state.exams.find((e) => e.id === id);
  if (!exam) {
    root.append(h('div.empty', {}, h('h1.title', {}, 'Prüfung nicht gefunden'), linkBtn('Zu den Prüfungen', '#/pruefungen')));
    return;
  }
  const decks = examDecks(exam, state.decks);
  const cards = examCards(exam, state);
  root.append(h('div.stack.stack-lg', {},
    h('div.row', {},
      key('back', 'Zu den Prüfungen', () => app.go('/pruefungen')),
      h('div.grow', {}, h('h1.title', {}, exam.subject), h('p.caption.muted', {}, `${examKindLabel(exam.kind)} · ${dateLong(exam.dateEpochDay)} · ${countdown(today, exam.dateEpochDay)}`)),
      key('edit', 'Prüfung bearbeiten', () => editExam(exam))),
    h('div.row', {},
      cards.length > 0 ? linkBtn('Probeprüfung', `#/probe/${exam.id}`, { kind: 'signal', icon: 'timer' }) : null,
      linkBtn('Jetzt lernen', '#/lernen/heute', { icon: 'play' })),
    decks.length === 0 ? h('div.notice', {}, icon('warn'), h('div.stack', { class: 'grow', vars: { '--gap': '8px' } },
      h('p', {}, `Noch kein Stapel im Fach „${exam.subject}“. Ohne Karten gibt es weder Plan noch Prognose.`),
      h('div.row', {}, btn('Stapel anlegen', () => editDeck(null, { name: exam.subject, subject: exam.subject }), { icon: 'plus' }), btn('Prüfung bearbeiten', () => editExam(exam), { kind: 'quiet' })))) : h('p.caption.muted', {}, `Stapel: ${decks.map((d) => d.name).join(', ')}`),
    h('div.grid-2', {},
      h('div.stack.stack-lg', {}, readinessBlock(exam, state, today), runsBlock(exam, state)),
      h('div.stack.stack-lg', {}, pathBlock(exam, state, today), materialBlock(exam), topicsBlock(exam, state), daysBlock(exam, today)))));
  return undefined;
}
