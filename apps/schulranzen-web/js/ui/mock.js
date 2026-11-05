// Probeprüfung: setup, the timed run without feedback, marking of open answers, result by
// topic and the way into the Nachlernrunde. A running exam is kept in the database after
// every answer, so a closed tab or a discarded page on iOS loses nothing and the clock,
// measured from its start, keeps going.
import { h, fill, btn, key, plates, checkbox, field, linkBtn, bar, announce, ask, toast, plural } from './dom.js';
import { app } from '../app.js';
import * as repo from '../data/repo.js';
import { check, gradeOf, normalize, SelfGrade } from '../core/answercheck.js';
import { BLANK, examMix, QuestionType } from '../core/questions.js';
import { evaluate, GRADE_WORDS, needsRelearning, pick, suggestedMinutes } from '../core/mockexam.js';
import { examDecks, examKindLabel, scheduleOf, topicOfCard } from '../core/study.js';
import { cleanCloze, maybeImage, statusTags } from './cardview.js';

const asQuiz = (c) => ({ id: c.id, front: c.front, back: cleanCloze(c.back), hasImage: !!(c.frontImage || c.backImage) });

/** Plain data only: this is stored as it is. */
function serialize(q) {
  const base = { type: q.type, cardIds: q.cards.map((c) => c.id) };
  if (q.type === QuestionType.CHOICE) return { ...base, options: q.options.map(cleanCloze), correct: q.correct };
  if (q.type === QuestionType.CLOZE) return { ...base, text: q.text, answer: q.answer };
  if (q.type === QuestionType.MATCH) return { ...base, rightOrder: q.rightOrder };
  return base;
}

function buildRun(exam, deckIds, count, minutes, state) {
  const cards = state.cards.filter((c) => deckIds.includes(c.deckId));
  const chosen = pick(cards, count, scheduleOf);
  const clozeReady = (c) => ({ ...asQuiz(c), back: c.back });
  const questions = examMix(chosen.map(clozeReady), cards.map(asQuiz));
  return {
    examId: exam.id, deckIds, startedAt: Date.now(), limitSeconds: minutes === null ? null : minutes * 60,
    questions: questions.map(serialize), answers: {}, index: 0, stage: 'RUN',
  };
}

const saveActive = (run) => app.set('activeMock', run);

function formatTime(seconds) {
  const s = Math.max(0, Math.ceil(seconds));
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

/** Marks every question by machine where that is possible; open ones wait for the learner. */
export function markRun(run, cardsById) {
  const items = [];
  run.questions.forEach((q, i) => {
    const a = run.answers[i] ?? {};
    if (q.type === QuestionType.MATCH) {
      q.cardIds.forEach((id) => {
        const card = cardsById.get(id);
        if (!card) return;
        const chosenId = a.pairs?.[id] ?? null;
        const chosen = chosenId ? cardsById.get(chosenId) : null;
        const right = chosen && normalize(cleanCloze(chosen.back)) === normalize(cleanCloze(card.back));
        items.push({ q: i, cardId: id, type: q.type, question: card.front, solution: cleanCloze(card.back), given: chosen ? cleanCloze(chosen.back) : '', grade: right ? SelfGrade.RIGHT : chosen ? SelfGrade.WRONG : SelfGrade.SKIPPED, auto: true });
      });
      return;
    }
    const id = q.cardIds[0];
    const card = cardsById.get(id);
    if (!card) return;
    const solution = cleanCloze(card.back);
    if (q.type === QuestionType.CHOICE) {
      const given = a.pick === undefined || a.pick === null ? '' : q.options[a.pick];
      items.push({ q: i, cardId: id, type: q.type, question: card.front, solution, given, grade: a.pick === q.correct ? SelfGrade.RIGHT : a.pick === undefined || a.pick === null ? SelfGrade.SKIPPED : SelfGrade.WRONG, auto: true });
    } else if (q.type === QuestionType.CLOZE || q.type === QuestionType.TYPE) {
      const want = q.type === QuestionType.CLOZE ? q.answer : solution;
      const given = a.text ?? '';
      const grade = given.trim() === '' ? SelfGrade.SKIPPED : gradeOf(check(given, want).verdict);
      items.push({ q: i, cardId: id, type: q.type, question: q.type === QuestionType.CLOZE ? q.text : card.front, solution: want, given, grade, auto: true });
    } else {
      const given = a.text ?? '';
      // Nothing written and nothing seen counts as skipped; written answers get marked by the learner.
      items.push({ q: i, cardId: id, type: q.type, question: card.front, solution, given, grade: null, auto: false });
    }
  });
  return items;
}

export async function renderMock(root, args) {
  const examId = Array.isArray(args) ? args[0] : args;
  const runId = Array.isArray(args) ? args[1] : null;
  const { state } = app;
  const exam = state.exams.find((e) => e.id === examId);
  if (!exam) {
    root.append(h('div.empty', {}, h('h1.title', {}, 'Prüfung nicht gefunden'), linkBtn('Zu den Prüfungen', '#/pruefungen')));
    return undefined;
  }
  if (runId) {
    const saved = state.mockRuns.find((r) => r.id === runId);
    if (saved) { showResult(root, exam, saved); return undefined; }
  }
  const active = app.setting('activeMock');
  if (active && active.examId === exam.id) return runExam(root, exam, active);
  return setup(root, exam, active);
}

function setup(root, exam, otherActive) {
  const { state } = app;
  const decks = examDecks(exam, state.decks);
  const chosen = new Set(decks.map((d) => d.id));
  const available = () => state.cards.filter((c) => chosen.has(c.deckId)).length;
  let count = Math.min(20, Math.max(5, available()));
  let minutes = suggestedMinutes(count);
  let touched = false;
  const body = h('div.stack');
  const draw = () => {
    const n = available();
    const asked = Math.min(count, n);
    if (!touched && minutes !== null) minutes = suggestedMinutes(asked);
    fill(body, 
      h('div.field', {}, h('div.field-label', {}, 'Stapel'), decks.length === 0 ? h('p.muted', {}, 'Noch keine Karten. Erst Karten schreiben, dann prüfen.') : decks.map((d) => checkbox(d.name, chosen.has(d.id), (v) => {
        if (v) chosen.add(d.id); else chosen.delete(d.id);
        draw();
      }))),
      h('div.field', {}, h('div.field-label', {}, 'Fragen'), plates('Anzahl der Fragen', [[10, '10'], [20, '20'], [30, '30'], [50, '50']], count, (v) => { count = v; draw(); }),
        h('p.caption.muted', {}, `${plural(asked, 'Frage', 'Fragen')} aus ${plural(n, 'Karte', 'Karten')}. Schwache Karten kommen öfter dran.`)),
      h('div.field', {}, h('div.field-label', {}, 'Zeit'), plates('Zeit', [[suggestedMinutes(asked), `${suggestedMinutes(asked)} Min.`], [null, 'Ohne Zeit']], minutes === null ? null : suggestedMinutes(asked), (v) => { minutes = v; touched = v === null; draw(); })),
      otherActive ? h('p.caption.signal-text', {}, 'Eine Probeprüfung in einem anderen Fach läuft noch und wird damit beendet.') : null,
      btn('Probeprüfung starten', async () => {
        const run = buildRun(exam, [...chosen], count, minutes, state);
        if (run.questions.length === 0) { toast('Keine Fragen: Wähle einen Stapel mit Karten.'); return; }
        await saveActive(run);
        app.refresh();
      }, { kind: 'signal', icon: 'play', wide: true, disabled: n === 0 }),
    );
  };
  draw();
  root.append(h('div.session', {},
    h('div.row', {}, key('back', 'Zur Prüfung', () => app.go(`/pruefungen/${exam.id}`)),
      h('div.grow', {}, h('h1.title', {}, 'Probeprüfung'), h('p.caption.muted', {}, `${exam.subject} · ${examKindLabel(exam.kind)}`))),
    h('p.muted', {}, 'Wie in der echten Prüfung: Keine Rückmeldung bis zum Schluss. Dein Lernplan ändert sich dadurch nur bei dem, was falsch war.'),
    body));
  return undefined;
}

function runExam(root, exam, run) {
  const { state } = app;
  const cardsById = new Map(state.cards.map((c) => [c.id, c]));
  const host = h('div.session');
  root.append(host);
  let timerEl = null;
  let tickId = null;
  let warned = false;

  const stop = () => { if (tickId) clearInterval(tickId); window.removeEventListener('keydown', onKey); };

  const left = () => (run.limitSeconds === null ? null : run.limitSeconds - (Date.now() - run.startedAt) / 1000);

  function tick() {
    const l = left();
    if (l === null || !timerEl) return;
    timerEl.textContent = formatTime(l);
    timerEl.classList.toggle('low', l <= 60);
    if (l <= 60 && !warned) { warned = true; announce('Noch eine Minute.'); }
    if (l <= 0 && run.stage === 'RUN') { toast('Die Zeit ist um.'); hand(); }
  }

  const onKey = (e) => {
    if (document.getElementById('overlay-root').childElementCount > 0) return;
    if (run.stage !== 'RUN' || e.ctrlKey || e.metaKey || e.altKey) return;
    if (e.target.matches?.('input, textarea, select')) return;
    const q = run.questions[run.index];
    if (q.type === QuestionType.CHOICE) {
      const n = Number(e.key) - 1;
      if (Number.isInteger(n) && n >= 0 && n < q.options.length) { e.preventDefault(); setAnswer({ pick: n }); draw(); }
    }
    if (e.key === 'ArrowRight' && run.index < run.questions.length - 1) { e.preventDefault(); go(run.index + 1); }
    if (e.key === 'ArrowLeft' && run.index > 0) { e.preventDefault(); go(run.index - 1); }
  };
  window.addEventListener('keydown', onKey);

  function setAnswer(patch) {
    run.answers[run.index] = { ...(run.answers[run.index] ?? {}), ...patch };
    saveActive(run);
  }

  function go(i) {
    run.index = i;
    saveActive(run);
    draw();
  }

  const answered = (i) => {
    const a = run.answers[i];
    if (!a) return false;
    if (a.pairs) return Object.keys(a.pairs).length > 0;
    return a.pick !== undefined || (a.text ?? '').trim() !== '';
  };

  async function hand() {
    run.stage = 'MARK';
    run.finishedAt = Date.now();
    run.items = markRun(run, cardsById);
    await saveActive(run);
    if (run.items.every((x) => x.auto)) { await finish(); return; }
    draw();
  }

  async function finish() {
    stop();
    const decks = new Map(state.decks.map((d) => [d.id, d]));
    const items = run.items.map((x, position) => {
      const card = cardsById.get(x.cardId);
      const topic = card ? topicOfCard(card, exam, decks.get(card.deckId)) : 'Ohne Thema';
      return { cardId: x.cardId, position, topic, type: x.type, question: x.question, solution: x.solution, given: x.given, grade: x.grade ?? SelfGrade.SKIPPED };
    });
    const res = evaluate(items);
    const weak = [...new Set(items.filter((x) => needsRelearning(x.grade)).map((x) => x.cardId))];
    const saved = await repo.saveMockRun({
      examId: exam.id, asked: res.asked, points: res.points, percent: res.percent, grade: res.grade,
      seconds: Math.round((run.finishedAt - run.startedAt) / 1000), limitSeconds: run.limitSeconds,
      weakTopics: res.weakTopics.map((t) => t.topic), topics: res.topics, answers: items,
    }, weak, state);
    app.state.settings.delete('activeMock');
    location.hash = `/probe/${exam.id}/${saved.id}`;
  }

  function questionBody(q) {
    const a = run.answers[run.index] ?? {};
    const card = cardsById.get(q.cardIds[0]);
    if (q.type === QuestionType.MATCH) {
      const cards = q.cardIds.map((id) => cardsById.get(id)).filter(Boolean);
      const order = q.rightOrder.map((i) => cards[i]).filter(Boolean);
      return h('div.stack', {},
        h('p.label', {}, 'Ordne jedem Begriff die passende Antwort zu.'),
        cards.map((c) => field(c.front, h('select.select', {
          onchange: (e) => setAnswer({ pairs: { ...(run.answers[run.index]?.pairs ?? {}), [c.id]: e.target.value || undefined } }),
        },
        h('option', { value: '' }, 'Bitte wählen'),
        order.map((o) => h('option', { value: o.id, selected: a.pairs?.[c.id] === o.id }, cleanCloze(o.back)))))));
    }
    if (!card) return h('p.muted', {}, 'Diese Karte gibt es nicht mehr. Die Frage zählt als ausgelassen.');
    const front = h('div.flash', { css: { minHeight: '120px' } },
      h('div.flash-head', {}, h('span', {}, `FRAGE ${run.index + 1}`), statusTags(card)),
      card.front ? h('div.flash-text', {}, card.front) : null, maybeImage(card.frontImage, 'Bild auf der Vorderseite'));
    if (q.type === QuestionType.CHOICE) {
      return h('div.stack', {}, front, h('div.options', { role: 'radiogroup', 'aria-label': 'Antworten' }, q.options.map((text, i) => h('button.option', {
        type: 'button', role: 'radio', 'aria-checked': String(a.pick === i), onclick: () => { setAnswer({ pick: i }); draw(); },
      }, h('span.mono', {}, String(i + 1)), h('span.grow', {}, text)))));
    }
    if (q.type === QuestionType.CLOZE) {
      const parts = q.text.split(BLANK);
      return h('div.stack', {}, h('div.flash', { css: { minHeight: '120px' } },
        h('div.flash-head', {}, h('span', {}, `FRAGE ${run.index + 1}`), statusTags(card)),
        h('p.label.muted.pre', {}, card.front),
        h('div.flash-text', {}, parts[0], h('span.cloze-gap', {}, BLANK), parts.slice(1).join(BLANK))),
      field('Was gehört in die Lücke?', h('input.input', { value: a.text ?? '', autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false', oninput: (e) => setAnswer({ text: e.target.value }) })));
    }
    const open = q.type === QuestionType.FLIP;
    return h('div.stack', {}, front,
      field(open ? 'Deine Antwort (zum Vergleichen am Ende)' : 'Deine Antwort', h('textarea.textarea', { rows: open ? 4 : 2, value: a.text ?? '', autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false', oninput: (e) => setAnswer({ text: e.target.value }) })));
  }

  function drawRun() {
    const q = run.questions[run.index];
    const total = run.questions.length;
    timerEl = run.limitSeconds === null ? null : h('div.timer', { role: 'timer', 'aria-label': 'Restzeit' }, formatTime(left()));
    const last = run.index === total - 1;
    fill(host, 
      h('div.row-between', {},
        h('div.grow', {}, h('h1.label', {}, `Probeprüfung · ${exam.subject}`), h('p.mono.muted', {}, `Frage ${run.index + 1} von ${total}`)),
        timerEl),
      questionBody(q),
      h('div.row-between', {},
        btn('Zurück', () => go(run.index - 1), { kind: 'quiet', icon: 'back', disabled: run.index === 0 }),
        last ? btn('Abgeben', confirmHand, { kind: 'signal', icon: 'check' }) : btn('Weiter', () => go(run.index + 1), { icon: 'next' })),
      h('nav.qnav', { 'aria-label': 'Fragen' }, run.questions.map((_, i) => h(`button${answered(i) ? '.answered' : ''}`, {
        type: 'button', 'aria-current': String(i === run.index), 'aria-label': `Frage ${i + 1}${answered(i) ? ', beantwortet' : ''}`, onclick: () => go(i),
      }, String(i + 1)))),
      h('div.row', {},
        btn('Abgeben', confirmHand, { kind: 'quiet', icon: 'check' }),
        btn('Abbrechen', async () => {
          const yes = await ask('Probeprüfung abbrechen?', 'Deine Antworten gehen verloren. Sie zählt dann nicht.', 'Abbrechen', { no: 'Weiterschreiben', danger: true });
          if (!yes) return;
          stop();
          await app.set('activeMock', null);
          app.go(`/pruefungen/${exam.id}`);
        }, { kind: 'quiet', icon: 'cross' })),
    );
    host.querySelector('input, textarea, select')?.focus({ preventScroll: true });
  }

  async function confirmHand() {
    const open = run.questions.filter((_, i) => !answered(i)).length;
    if (open > 0) {
      const yes = await ask('Jetzt abgeben?', `${plural(open, 'Frage ist', 'Fragen sind')} noch offen und ${open === 1 ? 'zählt' : 'zählen'} dann als ausgelassen.`, 'Abgeben', { no: 'Weiterschreiben' });
      if (!yes) return;
    }
    hand();
  }

  function drawMark() {
    const open = run.items.filter((x) => !x.auto);
    const missing = open.filter((x) => x.grade === null).length;
    const gradeKeys = (item) => h('div.seg', { role: 'group', 'aria-label': 'Selbst bewerten' },
      [['WRONG', 'Falsch'], ['PARTLY', 'Teilweise'], ['RIGHT', 'Richtig']].map(([g, label]) => h('button.plate', {
        type: 'button', 'aria-pressed': String(item.grade === g),
        onclick: () => { item.grade = g; saveActive(run); drawMark(); },
      }, label)));
    const wrong = run.items.filter((x) => x.auto && x.grade !== SelfGrade.RIGHT);
    fill(host, 
      h('h1.title', {}, 'Selbst bewerten'),
      h('p.muted', {}, 'Vergleiche deine Antwort mit der Lösung. Sei ehrlich: Die Prognose ist nur so gut wie deine Bewertung.'),
      open.map((item) => h('div.panel', {}, h('div.stack', {},
        h('p.label', {}, item.question),
        h('div', {}, h('p.caption.muted', {}, 'Deine Antwort'), h('p.pre', {}, item.given || '(nichts notiert)')),
        h('div', {}, h('p.caption.muted', {}, 'Lösung'), h('p.pre', {}, item.solution)),
        gradeKeys(item)))),
      wrong.length > 0 ? h('details', {}, h('summary.label.muted', { css: { minHeight: '44px', cursor: 'pointer' } }, `Vom Computer als falsch gewertet (${wrong.length})`),
        h('p.caption.muted', {}, 'Hat die Prüfung dich falsch verstanden? Dann werte es hier um.'),
        wrong.map((item) => h('div.panel', { css: { marginTop: '8px' } }, h('div.stack', {},
          h('p.label', {}, item.question),
          h('p.pre', {}, `Du: ${item.given || '(nichts)'}`),
          h('p.pre', {}, `Lösung: ${item.solution}`),
          gradeKeys(item))))) : null,
      btn(missing > 0 ? `Noch ${missing} zu bewerten` : 'Ergebnis zeigen', finish, { kind: 'signal', wide: true, disabled: missing > 0 }),
    );
  }

  function draw() {
    if (run.stage === 'RUN') drawRun();
    else drawMark();
  }

  if (run.stage === 'RUN' && left() !== null && left() <= 0) hand();
  else draw();
  tickId = setInterval(tick, 500);
  return stop;
}

function showResult(root, exam, run) {
  const items = run.answers ?? [];
  const weak = items.filter((a) => needsRelearning(a.grade));
  const stillDue = app.state.cards.filter((c) => weak.some((w) => w.cardId === c.id) && c.dueEpochDay <= app.today).length;
  const bySign = { RIGHT: 'Richtig', PARTLY: 'Teilweise', WRONG: 'Falsch', SKIPPED: 'Ausgelassen' };
  root.append(h('div.session', {},
    h('div.row', {}, key('back', 'Zur Prüfung', () => app.go(`/pruefungen/${exam.id}`)),
      h('div.grow', {}, h('h1.title', {}, 'Auswertung'), h('p.caption.muted', {}, `${exam.subject} · ${plural(run.asked, 'Frage', 'Fragen')}`))),
    h('div.counters', {},
      h('div.counter', {}, h('b', {}, String(run.grade)), h('span', {}, 'Note (geschätzt)')),
      h('div.counter', {}, h('b', {}, `${Math.trunc(run.percent)} %`), h('span', {}, 'Punkte')),
      h('div.counter', {}, h('b', {}, formatTime(run.seconds ?? 0)), h('span', {}, run.limitSeconds ? `von ${formatTime(run.limitSeconds)}` : 'Zeit'))),
    h('p.label', {}, `${GRADE_WORDS[run.grade]}. `, h('span.caption.muted', {}, 'Nach dem IHK-Notenschlüssel; offene Fragen hast du selbst bewertet, also nur eine Schätzung.')),
    h('section.stack', {},
      h('h2.heading', {}, 'Nach Thema'),
      (run.topics ?? []).map((t) => h('div.stack', { vars: { '--gap': '4px' } },
        h('div.row-between', {}, h('span.label', {}, t.topic), h('span.mono', {}, `${Math.trunc(t.percent)} % · ${t.asked}`)),
        bar(t.percent, t.percent < 67 ? 'var(--signal)' : 'var(--subject-0)'))),
      (run.weakTopics ?? []).length === 0 ? h('p.good-text', {}, 'Kein Thema unter 67 %. Stark!') : null),
    h('div.row', {},
      weak.length > 0 && stillDue > 0 ? linkBtn(`Nachlernrunde · ${stillDue}`, `#/lernen/nach/${run.id}`, { kind: 'signal', icon: 'play' }) : null,
      linkBtn('Neue Probeprüfung', `#/probe/${exam.id}`, { icon: 'timer' })),
    h('section.stack', {},
      h('h2.heading', {}, 'Alle Fragen'),
      weak.length > 0 ? h('p.caption.muted', {}, 'Was nicht richtig war, ist ab heute wieder fällig.') : h('p.muted', {}, 'Alles richtig.'),
      h('div.rows', {}, items.map((a) => h('div.list-row', { css: { alignItems: 'flex-start', cursor: 'default' } },
        h('div.list-main', {},
          h('div.list-title.pre', {}, a.question),
          a.given ? h('div.list-sub.pre', {}, `Du: ${a.given}`) : null,
          a.grade !== 'RIGHT' ? h('div.list-sub.pre', {}, `Lösung: ${a.solution}`) : null),
        h(`span.tag${a.grade === 'RIGHT' ? '' : '.tag-signal'}`, {}, bySign[a.grade])))))));
}
