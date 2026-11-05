// The learning loop: all question kinds, keyboard, undo. A session is defined by its route,
// so a reload (or iOS throwing the page away) simply continues with what is still open.
import { h, fill, btn, key, icon, plates, bar, toast, announce, linkBtn, plural } from './dom.js';
import { app } from '../app.js';
import * as repo from '../data/repo.js';
import { check } from '../core/answercheck.js';
import { canMatch, match as makeMatch, MatchRound, MATCH_SIZE, question, QuestionType, BLANK } from '../core/questions.js';
import { Rating, RATING_NAMES, RATINGS, preview, queue as dueQueue } from '../core/repetition.js';
import { todayPlan, scheduleOf, examDateForCard, errorBookOf } from '../core/study.js';
import { DEFAULT_MINUTES } from '../core/todayplan.js';
import { shuffled } from '../core/text.js';
import { editCard } from './decks.js';
import { minutesDoneToday } from './today.js';
import { cleanCloze, maybeImage, solution, statusTags } from './cardview.js';
import { QUESTION_MODES, suggestedRating, verdictText } from './texts.js';
import { explainPanel, explainAvailable } from './explain.js';

const TAG_COLORS = ['var(--subject-1)', 'var(--subject-2)', 'var(--subject-3)', 'var(--subject-0)'];

// Gap marks are only for the gap question; every other kind reads the plain sentence.
const toQuiz = (c, raw = false) => ({ id: c.id, front: c.front, back: raw ? c.back : cleanCloze(c.back), hasImage: !!(c.frontImage || c.backImage) });

/** The cards a route stands for, in learning order, with a title. */
function resolveSession(args, state, today) {
  const byId = new Map(state.cards.map((c) => [c.id, c]));
  const [kind, id, flag] = args;
  if (kind === 'stapel') {
    const deck = state.decks.find((d) => d.id === id);
    const cards = state.cards.filter((c) => c.deckId === id);
    const list = flag === 'alle' ? shuffled(cards) : dueQueue(cards, today, scheduleOf);
    return { title: deck?.name ?? 'Stapel', cards: list, back: `/stapel/${id}` };
  }
  if (kind === 'fehler') {
    return { title: 'Fehlerheft', cards: errorBookOf(state).map((e) => byId.get(e.cardId)).filter(Boolean), back: '/fehler' };
  }
  if (kind === 'nach') {
    const run = state.mockRuns.find((r) => r.id === id);
    const ids = (run?.answers ?? []).filter((a) => a.grade !== 'RIGHT' && a.cardId).map((a) => a.cardId);
    // Repeated visits only offer what is still due, so the round can end.
    const cards = [...new Set(ids)].map((x) => byId.get(x)).filter((c) => c && c.dueEpochDay <= today);
    return { title: 'Nachlernrunde', cards, back: run ? `/pruefungen/${run.examId}` : '/heute' };
  }
  const budget = app.setting('budgetMinutes', DEFAULT_MINUTES);
  const minutes = kind === 'alles' ? 100000 : Math.max(0, budget - minutesDoneToday(state, today));
  const plan = todayPlan(state, today, minutes);
  return { title: 'Heute', cards: plan.cards.map((s) => byId.get(s.cardId)).filter(Boolean), back: '/heute' };
}

export async function renderSession(root, args) {
  const { state, today } = app;
  const session = resolveSession(args, state, today);
  const queue = session.cards.slice();
  const total = queue.length;
  let answered = 0;
  let again = 0;
  let undoToken = null;
  let handler = null;
  const poolOf = (card) => state.cards.filter((c) => c.deckId === card.deckId).map((c) => toQuiz(c));

  const host = h('div.session');
  root.append(host);

  const onKey = (e) => {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    if (document.getElementById('overlay-root').childElementCount > 0) return;
    if (e.key === 'Escape') { e.preventDefault(); app.go(session.back); return; }
    // Keys on the question-kind plates and the top row belong to those controls.
    if ((e.code === 'Space' || e.key === 'Enter') && e.target.closest?.('.seg button, .session-top button, .flash-head button')) return;
    handler?.(e);
  };
  window.addEventListener('keydown', onKey);

  const top = () => {
    const done = total === 0 ? 0 : Math.round((answered * 100) / (answered + queue.length || 1));
    return h('div.stack', { vars: { '--gap': '8px' } },
      h('div.session-top', {},
        key('back', 'Sitzung beenden', () => app.go(session.back)),
        h('div.session-progress', {},
          h('div.row-between', {}, h('h1.label', {}, session.title), h('span.mono', { 'aria-label': `Noch ${queue.length} Karten` }, `${answered} / ${answered + queue.length}`)),
          bar(done, 'var(--signal)')),
        undoToken ? key('undo', 'Letzte Antwort zurücknehmen (U)', undoLast) : null),
      h('div.seg-scroll', {}, plates('Frageart', QUESTION_MODES, app.setting('questionMode', 'FLIP'), async (v) => { await app.set('questionMode', v); next(); })));
  };

  async function undoLast() {
    if (!undoToken) return;
    const token = undoToken;
    undoToken = null;
    await repo.undo(token, state);
    if (token.requeued) {
      const at = queue.findIndex((c) => c.id === token.before.id);
      if (at >= 0) queue.splice(at, 1);
      again--;
    }
    queue.unshift(token.before);
    answered--;
    announce('Antwort zurückgenommen.');
    next();
  }

  async function rate(card, rating) {
    const token = await repo.rate(card, rating, state);
    answered++;
    queue.shift();
    if (rating === Rating.AGAIN) {
      // Forgotten: comes back at the end of today's round.
      queue.push(token.after);
      again++;
      token.requeued = true;
    }
    undoToken = token;
    next();
  }

  function ratingRow(card, suggestion = null) {
    const examDate = examDateForCard(card, state, today);
    const days = preview(scheduleOf(card), today, examDate);
    const label = (n) => (n === 0 ? 'heute' : n === 1 ? '1 Tag' : `${n} Tage`);
    return h('div.rate-row', { role: 'group', 'aria-label': 'Wie gut wusstest du es?' }, RATINGS.map((r) => h(`button.rate${suggestion === r ? '.suggested' : ''}`, {
      type: 'button', vars: { '--c': TAG_COLORS[r] }, onclick: () => rate(card, r),
      'aria-label': `${RATING_NAMES[r]}, wieder in ${label(days[r])}${suggestion === r ? ', vorgeschlagen' : ''}`,
    }, h('b', {}, RATING_NAMES[r]), h('span', {}, label(days[r])), h('kbd', {}, String(r + 1)))));
  }

  const ratingKeys = (card, suggestion = null) => (e) => {
    if (e.target.matches?.('input, textarea, select')) return;
    if (['1', '2', '3', '4'].includes(e.key)) { e.preventDefault(); rate(card, Number(e.key) - 1); return; }
    if (e.key === 'Enter' && suggestion !== null && !e.target.matches?.('button, a')) { e.preventDefault(); rate(card, suggestion); return; }
    if (e.key === 'u' || e.key === 'U') { e.preventDefault(); undoLast(); }
  };

  const headOf = (card, label) => h('div.flash-head', {}, h('span', {}, label), h('span.row', { css: { gap: '6px' } }, statusTags(card),
    h('button.btn.btn-quiet', { type: 'button', css: { minHeight: '32px', padding: '0 8px' }, onclick: (e) => { e.stopPropagation(); editOpen(card); }, 'aria-label': 'Karte bearbeiten' }, icon('edit'))));

  // Saving redraws the screen; the session continues from the route with the new text.
  const editOpen = (card) => editCard(state.decks.find((d) => d.id === card.deckId), card);

  const frontOf = (card) => [
    card.front ? h('div.flash-text', {}, card.front) : null,
    maybeImage(card.frontImage, 'Bild auf der Vorderseite'),
  ];

  const extras = (card) => (explainAvailable() ? h('div.row', {}, btn('Erklären lassen', () => explainPanel(card), { kind: 'quiet', icon: 'spark' })) : null);

  // ---- The kinds ----------------------------------------------------------

  function showFlip(card) {
    let flipped = false;
    const face = h('div');
    const below = h('div.stack');
    const draw = () => {
      if (!flipped) {
        const panel = h('button.flash', { type: 'button', onclick: flip, 'aria-label': `Frage: ${card.front || 'Bildkarte'}. Antippen zum Umdrehen.` },
          h('div.flash-head', {}, h('span', {}, 'FRAGE'), statusTags(card)),
          frontOf(card),
          h('p.caption.muted', { css: { textAlign: 'center' } }, 'Antippen zum Umdrehen'));
        fill(face, panel);
        fill(below, btn('Antwort zeigen', flip, { kind: 'signal', icon: 'flip', wide: true }));
        handler = (e) => {
          if (e.target.matches?.('input, textarea, select')) return;
          if (e.code === 'Space' || e.key === 'Enter') { e.preventDefault(); flip(); }
          else if (e.key === 'u' || e.key === 'U') { e.preventDefault(); undoLast(); }
        };
        setTimeout(() => { if (panel.isConnected) panel.focus({ preventScroll: true }); }, 0);
      } else {
        fill(face, h('div.flash', {}, headOf(card, 'ANTWORT'), h('p.label.muted.pre', {}, card.front), h('hr.flash-divider'), solution(card)));
        fill(below, ratingRow(card), extras(card));
        handler = (e) => {
          ratingKeys(card)(e);
          if (e.code === 'Space' && !e.target.matches?.('input, textarea, select')) e.preventDefault();
        };
        setTimeout(() => below.querySelector('button')?.focus({ preventScroll: true }), 0);
        announce(`Antwort: ${cleanCloze(card.back)}`);
      }
    };
    const flip = () => { flipped = true; draw(); };
    draw();
    return [face, below];
  }

  function resultPanel(card, result, expectedShown, given) {
    const v = verdictText(result, expectedShown);
    const suggestion = suggestedRating(result.verdict);
    const ic = v.cls === 'ok' ? 'check' : v.cls === 'bad' ? 'cross' : 'minus';
    announce(`${v.title}. ${v.text}`);
    return {
      node: h(`div.verdict.${v.cls}`, { role: 'status' }, icon(ic), h('div', {}, h('b', {}, v.title), v.text ? h('p', {}, v.text) : null, given ? h('p.caption.muted', {}, `Deine Antwort: ${given}`) : null)),
      suggestion,
    };
  }

  function showInput(card, promptNodes, expected, placeholder, label) {
    const multi = expected.includes('\n');
    const input = multi
      ? h('textarea.textarea', { rows: 3, placeholder, 'aria-label': label, autocapitalize: 'off', autocomplete: 'off', spellcheck: 'false' })
      : h('input.input', { placeholder, 'aria-label': label, autocapitalize: 'off', autocomplete: 'off', spellcheck: 'false', enterkeyhint: 'done' });
    const panel = h('div.flash', {}, h('div.flash-head', {}, h('span', {}, 'FRAGE'), statusTags(card)), promptNodes, input);
    const below = h('div.stack', {}, btn('Prüfen', submit, { kind: 'signal', wide: true }));
    if (multi) below.prepend(h('p.caption.muted', {}, 'Mehrere Punkte mit Komma oder je einer Zeile. Strg+Enter prüft.'));
    function submit() {
      const given = input.value;
      const result = check(given, expected);
      const { node, suggestion } = resultPanel(card, result, expected, given);
      fill(panel, headOf(card, 'ANTWORT'), h('p.label.muted.pre', {}, card.front), h('hr.flash-divider'), solution(card));
      fill(below, node, ratingRow(card, suggestion), extras(card));
      handler = ratingKeys(card, suggestion);
      setTimeout(() => (below.querySelector('.rate.suggested') ?? below.querySelector('.rate'))?.focus({ preventScroll: true }), 0);
    }
    handler = (e) => {
      if (e.key === 'Enter' && (!multi || e.ctrlKey)) { e.preventDefault(); submit(); }
    };
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter' && e.ctrlKey && multi) { e.preventDefault(); submit(); } });
    setTimeout(() => input.focus({ preventScroll: true }), 0);
    return [panel, below];
  }

  function showChoice(card, q) {
    const opts = q.options.map((text, i) => h('button.option', { type: 'button', onclick: () => pick(i) }, h('span.mono', {}, String(i + 1)), h('span.grow', {}, text)));
    const panel = h('div.flash', { css: { minHeight: '140px' } }, h('div.flash-head', {}, h('span', {}, 'FRAGE'), statusTags(card)), frontOf(card));
    const below = h('div.stack', {}, h('div.options', { role: 'group', 'aria-label': 'Antworten' }, opts));
    function pick(i) {
      opts.forEach((o, n) => {
        o.disabled = true;
        if (n === q.correct) o.classList.add('right');
        else if (n === i) o.classList.add('wrong');
      });
      const ok = i === q.correct;
      const { node } = resultPanel(card, { verdict: ok ? 'RIGHT' : 'WRONG', hint: null, total: 1, matched: ok ? 1 : 0 }, q.options[q.correct], ok ? null : q.options[i]);
      const suggestion = ok ? Rating.GOOD : Rating.AGAIN;
      below.append(node, solutionIfLonger(card, q.options[q.correct]), ratingRow(card, suggestion), extras(card));
      handler = ratingKeys(card, suggestion);
      setTimeout(() => below.querySelector('.rate.suggested')?.focus({ preventScroll: true }), 0);
    }
    handler = (e) => {
      if (e.target.matches?.('input, textarea, select')) return;
      const n = Number(e.key) - 1;
      if (Number.isInteger(n) && n >= 0 && n < opts.length) { e.preventDefault(); pick(n); }
    };
    setTimeout(() => opts[0]?.focus({ preventScroll: true }), 0);
    return [panel, below];
  }

  const solutionIfLonger = (card, shown) => (card.back.trim() !== shown.trim() || card.backImage ? h('div.panel', {}, solution(card)) : null);

  function showCloze(card, q) {
    const parts = q.text.split(BLANK);
    const sentence = h('div.flash-text', {}, parts[0], h('span.cloze-gap', { 'aria-label': 'Lücke' }, BLANK), parts.slice(1).join(BLANK));
    return showInput(card, [h('p.label.muted.pre', {}, card.front), sentence], q.answer, 'Was gehört in die Lücke?', 'Lücke ausfüllen');
  }

  function showMatch(cards) {
    const q = makeMatch(cards.map((c) => toQuiz(c)));
    const round = new MatchRound(q.cards);
    let left = null;
    const leftButtons = new Map();
    const rightButtons = new Map();
    const finish = async () => {
      for (const c of q.cards) {
        const card = queue.find((x) => x.id === c.id);
        if (!card) continue;
        const rating = round.firstTry(c.id) ? Rating.GOOD : Rating.AGAIN;
        const token = await repo.rate(card, rating, state);
        answered++;
        queue.splice(queue.indexOf(card), 1);
        if (rating === Rating.AGAIN) { queue.push(token.after); again++; }
      }
      undoToken = null;
      toast(round.missed.size === 0 ? 'Alles auf Anhieb richtig.' : `${plural(round.missed.size, 'Paar kommt', 'Paare kommen')} nochmal.`, { ms: 2200 });
      next();
    };
    const pickRight = (id) => {
      if (left === null) { announce('Wähle zuerst links einen Begriff.'); return; }
      const leftId = left;
      const ok = round.pick(leftId, id);
      if (ok) {
        const lb = leftButtons.get(leftId);
        lb.classList.add('done');
        lb.disabled = true;
        lb.setAttribute('aria-pressed', 'false');
        const rb = rightButtons.get(id);
        // With two equal answers either is right; take the one that was tapped.
        rb.classList.add('done');
        rb.disabled = true;
        left = null;
        announce('Richtig.');
        if (round.done) { setTimeout(finish, 350); return; }
        const nextLeft = q.cards.find((c) => !round.solvedIds.has(c.id));
        leftButtons.get(nextLeft.id)?.focus({ preventScroll: true });
      } else {
        const rb = rightButtons.get(id);
        rb.classList.add('shake');
        setTimeout(() => rb.classList.remove('shake'), 400);
        announce('Passt nicht. Nochmal.');
      }
    };
    const leftCol = q.cards.map((c) => {
      const b = h('button.match-item', {
        type: 'button', 'aria-pressed': 'false',
        onclick: () => {
          left = c.id;
          leftButtons.forEach((x) => x.setAttribute('aria-pressed', 'false'));
          b.setAttribute('aria-pressed', 'true');
        },
      }, c.front);
      leftButtons.set(c.id, b);
      return b;
    });
    const rightCol = q.rightOrder.map((i) => {
      const c = q.cards[i];
      const b = h('button.match-item', { type: 'button', onclick: () => pickRight(c.id) }, c.back);
      rightButtons.set(c.id, b);
      return b;
    });
    handler = null;
    setTimeout(() => leftCol[0]?.focus({ preventScroll: true }), 0);
    return [
      h('p.label', {}, 'Tippe links einen Begriff, dann rechts das Passende.'),
      h('div.match', {}, h('div.match-col', { role: 'group', 'aria-label': 'Begriffe' }, leftCol), h('div.match-col', { role: 'group', 'aria-label': 'Antworten' }, rightCol)),
    ];
  }

  // ---- Flow -----------------------------------------------------------------

  /** In "Gemischt" each card gets a kind; every eighth step a matching block if it fits. */
  let mixStep = 0;
  function chooseKind(card) {
    const mode = app.setting('questionMode', 'FLIP');
    if (mode !== 'MIX') return mode;
    mixStep++;
    const kinds = [QuestionType.TYPE, QuestionType.CHOICE, QuestionType.CLOZE, QuestionType.FLIP];
    if (mixStep % 8 === 0 && canMatch(toQuiz(card))) return QuestionType.MATCH;
    return kinds[mixStep % kinds.length];
  }

  function next() {
    handler = null;
    if (queue.length === 0) { finishView(); return; }
    const card = queue[0];
    const kind = chooseKind(card);
    let content;
    if (kind === QuestionType.MATCH) {
      const group = queue.filter((c) => canMatch(toQuiz(c))).slice(0, MATCH_SIZE);
      // The first card of the queue is always in the block, so no card waits for long.
      if (canMatch(toQuiz(card)) && group.length >= 3 && makeMatch(group.map((c) => toQuiz(c))) !== null) {
        content = showMatch(group);
      }
    }
    if (!content) {
      const asked = kind === QuestionType.MATCH ? QuestionType.TYPE : kind;
      const q = question(asked, toQuiz(card, asked === QuestionType.CLOZE), poolOf(card));
      if (q.type === QuestionType.TYPE) content = showInput(card, frontOf(card), cleanCloze(card.back), 'Deine Antwort', 'Deine Antwort');
      else if (q.type === QuestionType.CHOICE) content = showChoice(card, q);
      else if (q.type === QuestionType.CLOZE) content = showCloze(card, q);
      else content = showFlip(card);
    }
    fill(host, top(), ...content);
  }

  function finishView() {
    handler = (e) => { if (e.key === 'Enter') app.go(session.back); };
    const box = (value, label) => h('div.counter', {}, h('b', {}, String(value).padStart(2, '0')), h('span', {}, label));
    fill(host, h('div.stack', { css: { alignItems: 'center', textAlign: 'center', padding: '32px 0' } },
      h('h1.hero', {}, answered === 0 ? 'Nichts fällig' : 'Geschafft'),
      h('p.muted', {}, answered === 0 ? 'Hier ist gerade nichts zu tun.' : 'Für heute ist das durch. Morgen geht es weiter.'),
      answered > 0 ? h('div.counters', { css: { gridTemplateColumns: 'repeat(2, 1fr)', width: 'min(280px, 100%)' } }, box(answered, 'Antworten'), box(again, 'Nochmal')) : null,
      linkBtn('Zur Übersicht', `#${session.back}`, { kind: 'signal' })));
    host.querySelector('h1').setAttribute('tabindex', '-1');
    host.querySelector('h1').focus({ preventScroll: true });
    announce('Sitzung beendet.');
  }

  next();
  return () => window.removeEventListener('keydown', onKey);
}
