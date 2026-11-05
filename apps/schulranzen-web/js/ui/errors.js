// Fehlerheft: fills and empties itself from the answer log; nothing to tidy up.
import { h, linkBtn, sectionHead, subjectColor } from './dom.js';
import { app } from '../app.js';
import { errorBookOf } from '../core/study.js';
import { CLEAR_AFTER } from '../core/progress.js';
import { dateShort } from '../core/dates.js';
import { cleanCloze } from './cardview.js';

export async function renderErrors(root) {
  const { state } = app;
  const entries = errorBookOf(state);
  const cards = new Map(state.cards.map((c) => [c.id, c]));
  const decks = new Map(state.decks.map((d) => [d.id, d]));
  root.append(h('div.stack.stack-lg', {},
    h('div.row-between', {},
      h('div.section-head', {}, h('h1.title', {}, 'Fehlerheft'), h('span.count', {}, String(entries.length).padStart(2, '0'))),
      entries.length > 0 ? linkBtn('Jetzt üben', '#/lernen/fehler', { kind: 'signal', icon: 'play' }) : null),
    h('p.muted', {}, `Eine Karte kommt hierher, wenn sie an zwei Tagen oder dreimal falsch war. Nach ${CLEAR_AFTER} richtigen Antworten in Folge verlässt sie das Heft von selbst.`),
    entries.length === 0
      ? h('div.empty', {}, h('p.title', {}, 'Nichts drin'), h('p', {}, 'Gerade macht dir keine Karte zu schaffen.'))
      : h('section.stack', {}, sectionHead('Am schwersten zuerst'),
        h('div.rows', {}, entries.map((e) => {
          const c = cards.get(e.cardId);
          const d = decks.get(c.deckId);
          return h('a.list-row', { href: `#/stapel/${c.deckId}`, vars: { '--c': subjectColor(d?.colorIndex ?? 0) } },
            h('span.list-edge'),
            h('span.list-main', {},
              h('span.list-title.clip', {}, c.front || 'Bildkarte'),
              h('span.list-sub.clip', {}, cleanCloze(c.back)),
              h('span.list-sub', { css: { display: 'block' } }, `${d?.name ?? ''} · zuletzt falsch ${dateShort(e.lastWrongDay)}`)),
            h('span.stack', { vars: { '--gap': '2px' }, css: { alignItems: 'flex-end' } },
              h('span.mono.signal-text', {}, `${e.wrong}×`),
              e.rightInARow > 0 ? h('span.caption.muted', {}, `${e.rightInARow}/${CLEAR_AFTER} richtig`) : null));
        })))));
}
