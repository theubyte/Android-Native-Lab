// Stapel: the shelf, one deck with its cards, editors, import and export of text files.
import { h, fill, btn, key, icon, field, sectionHead, subjectColor, colorPicker, openPanel, ask, toast, saveFile, pickFile, readText, shrinkImage, plural, checkbox, linkBtn, bar } from './dom.js';
import { app } from '../app.js';
import * as repo from '../data/repo.js';
import { deckCounts, knownSubjects, scheduleOf } from '../core/study.js';
import * as Csv from '../core/cardcsv.js';
import { suggest, fromText } from '../core/cardsuggester.js';
import { exportPack } from '../core/contentpack.js';
import { dateShort } from '../core/dates.js';
import { isNew } from '../core/repetition.js';
import { mastery } from '../core/progress.js';
import { sourceLine } from './texts.js';

const low = (s) => String(s ?? '').trim().toLowerCase();

let subjectFilter = null;

function subjectInput(value, onInput) {
  const listId = `s${Math.random().toString(36).slice(2, 8)}`;
  const input = h('input.input', { value: value ?? '', list: listId, autocomplete: 'off', placeholder: 'z. B. Mathe', oninput: (e) => onInput(e.target.value) });
  const list = h('datalist', { id: listId }, knownSubjects(app.state).map((s) => h('option', { value: s.name })));
  return { input, list };
}

export function editDeck(deck = null, preset = {}) {
  const state = app.state;
  const draft = { name: deck?.name ?? preset.name ?? '', subject: deck?.subject ?? preset.subject ?? '', colorIndex: deck?.colorIndex ?? state.decks.length % 8 };
  return openPanel(deck ? 'Stapel bearbeiten' : 'Neuer Stapel', (close) => {
    const subject = subjectInput(draft.subject, (v) => { draft.subject = v; });
    const name = h('input.input', { value: draft.name, 'data-autofocus': '', required: true, placeholder: 'z. B. Bruchrechnen', oninput: (e) => { draft.name = e.target.value; } });
    const error = h('p.caption.signal-text', { role: 'alert' });
    return h('form.stack', {
      onsubmit: async (e) => {
        e.preventDefault();
        if (!draft.name.trim()) { error.textContent = 'Der Stapel braucht einen Namen.'; name.focus(); return; }
        const row = await repo.saveDeck({ ...(deck ?? {}), name: draft.name.trim(), subject: draft.subject.trim(), colorIndex: draft.colorIndex });
        close(row);
        if (deck) app.refresh(); else app.go(`/stapel/${row.id}`);
      },
    },
    field('Name', name),
    field('Fach', subject.input, 'Prüfungen im selben Fach steuern die Wiederholung.'),
    subject.list,
    h('div.field', {}, h('div.field-label', {}, 'Farbe'), colorPicker(draft.colorIndex, (i) => { draft.colorIndex = i; })),
    error,
    deck ? h('p.caption.muted', {}, `Beim Löschen gehen auch die ${plural(deckCounts(deck, state, app.today).total, 'Karte', 'Karten')} verloren.`) : null,
    h('div.drawer-actions', {},
      deck ? btn('Löschen', async () => {
        const yes = await ask('Stapel löschen?', `„${deck.name}“ und alle Karten darin werden gelöscht. Das lässt sich nicht rückgängig machen.`, 'Löschen', { danger: true });
        if (!yes) return;
        await repo.deleteDeck(deck.id, state);
        close();
        app.go('/stapel');
      }, { kind: 'quiet', icon: 'trash' }) : null,
      btn('Abbrechen', () => close(), { kind: 'quiet' }),
      h('button.btn.btn-signal', { type: 'submit' }, 'Speichern')));
  });
}

function imagePicker(label, currentId, onChange) {
  let id = currentId;
  const preview = h('div');
  const showPreview = async () => {
    fill(preview);
    if (id) {
      const url = await repo.imageUrl(id);
      if (url) preview.append(h('img', { src: url, alt: `${label}: gewähltes Bild`, css: { maxHeight: '120px', borderRadius: '8px', background: '#fff' } }));
    }
    fill(actions, 
      btn(id ? 'Ersetzen' : `Bild ${label}`, choose, { icon: 'image', kind: id ? 'quiet' : null }),
      id ? btn('Entfernen', () => { id = null; onChange(null); showPreview(); }, { kind: 'quiet' }) : '',
    );
  };
  const choose = async () => {
    const file = await pickFile('image/*');
    if (!file) return;
    try {
      const { mime, data } = await shrinkImage(file);
      id = await repo.saveImage(mime, data);
      onChange(id);
      showPreview();
    } catch (e) {
      toast(e.message);
    }
  };
  const actions = h('div.row');
  showPreview();
  return h('div.stack', { vars: { '--gap': '8px' } }, preview, actions);
}

/** Card editor. "Speichern, nächste" keeps the panel open for writing many in a row. */
export function editCard(deck, card = null) {
  const state = app.state;
  let made = 0;
  const draft = {
    front: card?.front ?? '', back: card?.back ?? '', frontImage: card?.frontImage ?? null, backImage: card?.backImage ?? null,
    topic: card?.topic ?? '', buch: card?.source?.buch ?? '', seite: card?.source?.seite ?? '', verified: card?.verified ?? null,
  };
  return openPanel(card ? 'Karte bearbeiten' : 'Neue Karte', (close) => {
    const body = h('div');
    const draw = () => {
      const front = h('textarea.textarea', { 'data-autofocus': '', rows: 3, value: draft.front, placeholder: 'Frage', oninput: (e) => { draft.front = e.target.value; } });
      const back = h('textarea.textarea', { rows: 3, value: draft.back, placeholder: 'Antwort. Mit {{c1::Wort}} legst du eine Lücke fest.', oninput: (e) => { draft.back = e.target.value; } });
      const error = h('p.caption.signal-text', { role: 'alert' });
      const save = async (more) => {
        if (!draft.front.trim() && !draft.frontImage) { error.textContent = 'Vorne fehlt die Frage.'; front.focus(); return; }
        if (!draft.back.trim() && !draft.backImage) { error.textContent = 'Hinten fehlt die Antwort.'; back.focus(); return; }
        const source = draft.buch.trim() || draft.seite.trim() ? { buch: draft.buch.trim(), seite: draft.seite.trim() } : null;
        const fields = {
          front: draft.front.trim(), back: draft.back.trim(), frontImage: draft.frontImage, backImage: draft.backImage,
          topic: draft.topic.trim(), source, verified: draft.verified,
        };
        if (card) {
          const fresh = state.cards.find((c) => c.id === card.id) ?? card;
          await repo.saveCard({ ...fresh, ...fields });
          for (const [was, now] of [[card.frontImage, draft.frontImage], [card.backImage, draft.backImage]]) {
            if (was && was !== now) await repo.deleteImage(was);
          }
          close(true);
          app.refresh();
          return;
        }
        await repo.saveCard(repo.newCardRow(deck.id, fields.front, fields.back, { frontImage: fields.frontImage, backImage: fields.backImage, topic: fields.topic, source }));
        made++;
        if (more) {
          toast(`Karte ${made} gespeichert.`, { ms: 1800 });
          Object.assign(draft, { front: '', back: '', frontImage: null, backImage: null });
          draw();
        } else {
          close(true);
          app.refresh();
        }
      };
      fill(body, h('form.stack', { onsubmit: (e) => { e.preventDefault(); save(!card); } },
        field('Vorderseite · Frage', front),
        imagePicker('vorne', draft.frontImage, (id) => { draft.frontImage = id; }),
        field('Rückseite · Antwort', back),
        imagePicker('hinten', draft.backImage, (id) => { draft.backImage = id; }),
        h('details', {},
          h('summary.label.muted', { css: { minHeight: '44px', paddingTop: '10px', cursor: 'pointer' } }, 'Thema und Quelle'),
          h('div.stack', {},
            field('Thema', h('input.input', { value: draft.topic, oninput: (e) => { draft.topic = e.target.value; } })),
            field('Buch', h('input.input', { value: draft.buch, oninput: (e) => { draft.buch = e.target.value; } })),
            field('Seite', h('input.input', { value: draft.seite, inputmode: 'numeric', oninput: (e) => { draft.seite = e.target.value; } })),
            card?.verified === false || card?.verified === true || card?.packId
              ? checkbox('Inhalt ist geprüft', draft.verified === true, (v) => { draft.verified = v; }, 'Mit dem Buch verglichen und für richtig befunden.') : null)),
        error,
        h('div.drawer-actions', {},
          card ? btn('Löschen', async () => {
            const yes = await ask('Karte löschen?', 'Die Karte und ihr Lernverlauf werden gelöscht.', 'Löschen', { danger: true });
            if (!yes) return;
            await repo.deleteCard(card, state);
            close(true);
            app.refresh();
          }, { kind: 'quiet', icon: 'trash' }) : null,
          btn(card ? 'Abbrechen' : made > 0 ? 'Fertig' : 'Abbrechen', () => { close(made > 0); if (made > 0) app.refresh(); }, { kind: 'quiet' }),
          !card ? btn('Speichern', () => save(false), {}) : null,
          h('button.btn.btn-signal', { type: 'submit' }, card ? 'Speichern' : 'Speichern, nächste'))));
      front.focus();
    };
    draw();
    return body;
  });
}

function suggestPanel(deck) {
  return openPanel('Karten aus Text', (close) => {
    const out = h('div.stack');
    const text = h('textarea.textarea', { rows: 8, 'data-autofocus': '', placeholder: 'Eigene Notizen einfügen. Begriffe mit Doppelpunkt, Aufzählungen und Sätze wie „X ist …“ werden erkannt.' });
    const run = (extra) => {
      const found = extra ? fromText(text.value) : suggest(text.value);
      const chosen = new Set(found.map((_, i) => i));
      fill(out, found.length === 0 ? h('p.muted', {}, 'Nichts erkannt. Schreibe Begriffe als „Begriff: Bedeutung“ oder ganze Sätze.') : h('div.stack', {},
        sectionHead('Vorschläge', found.length),
        found.map((s, i) => checkbox(s.front, true, (v) => { if (v) chosen.add(i); else chosen.delete(i); }, s.back)),
        h('div.drawer-actions', {}, btn('Abbrechen', () => close(), { kind: 'quiet' }), btn('Karten anlegen', async () => {
          const t0 = Date.now();
          const rows = found.filter((_, i) => chosen.has(i)).map((s, i) => repo.newCardRow(deck.id, s.front, s.back, { createdAt: t0 + i }));
          await repo.saveCards(rows);
          toast(`${plural(rows.length, 'Karte', 'Karten')} angelegt.`);
          close();
          app.refresh();
        }, { kind: 'signal' }))));
    };
    return h('div.stack', {},
      field('Dein Text', text, 'Nur du bestätigst, was eine Karte wird. Die Vorschläge kommen ohne Internet zustande.'),
      h('div.row', {}, btn('Vorschlagen', () => run(false)), btn('Auch Lücken vorschlagen', () => run(true), { kind: 'quiet' })),
      out);
  });
}

async function importText(deck) {
  const file = await pickFile('.txt,.tsv,.csv,text/plain,text/csv,text/tab-separated-values');
  if (!file) return;
  const parsed = Csv.parse(await readText(file));
  const cards = Csv.dropTitleRow(parsed.cards);
  // A named deck in the file wins over the open one; a front twice in a deck stays once.
  const targetOf = (c) => (c.deck ? app.state.decks.find((d) => low(d.name) === low(c.deck)) ?? c.deck : deck);
  const seen = new Set();
  const keyOf = (c) => {
    const t = targetOf(c);
    return `${typeof t === 'string' ? low(t) : t.id}|${low(c.front)}`;
  };
  for (const x of app.state.cards) seen.add(`${x.deckId}|${low(x.front)}`);
  const fresh = cards.filter((c) => {
    const k = keyOf(c);
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
  const twins = cards.length - fresh.length;
  const newDecks = [...new Set(fresh.map(targetOf).filter((t) => typeof t === 'string').map(low))];
  await openPanel('Karten einlesen', (close) => h('div.stack', {},
    h('p', {}, file.name),
    h('p', {}, `${plural(fresh.length, 'neue Karte', 'neue Karten')} für „${deck.name}“${newDecks.length > 0 ? ` und ${plural(newDecks.length, 'neuen Stapel', 'neue Stapel')} aus der Datei` : ''}.`),
    twins > 0 ? h('p.muted', {}, `${plural(twins, 'Karte gibt', 'Karten gibt')} es hier schon; sie bleiben, wie sie sind.`) : null,
    parsed.skipped > 0 ? h('p.muted', {}, `${plural(parsed.skipped, 'Zeile', 'Zeilen')} ohne Vorder- und Rückseite übersprungen.`) : null,
    parsed.mediaDropped > 0 ? h('p.muted', {}, `${plural(parsed.mediaDropped, 'Karte verwies', 'Karten verwiesen')} auf Bilder oder Ton; die kann eine Textdatei nicht mitbringen.`) : null,
    fresh.length > 0 ? h('div.rows', {}, fresh.slice(0, 5).map((c) => h('div.list-row', {}, h('div.list-main', {}, h('div.list-title.clip', {}, c.front), h('div.list-sub.clip', {}, c.back))))) : null,
    h('div.drawer-actions', {},
      btn('Abbrechen', () => close(), { kind: 'quiet' }),
      fresh.length > 0 ? btn('Einlesen', async () => {
        const t0 = Date.now();
        const made = new Map();
        const rows = [];
        for (const [i, c] of fresh.entries()) {
          let t = targetOf(c);
          if (typeof t === 'string') {
            if (!made.has(low(t))) made.set(low(t), await repo.saveDeck({ name: t, colorIndex: app.state.decks.length + made.size }));
            t = made.get(low(t));
          }
          rows.push(repo.newCardRow(t.id, c.front, c.back, { createdAt: t0 + i }));
        }
        await repo.saveCards(rows);
        toast(`${plural(fresh.length, 'Karte', 'Karten')} eingelesen.`);
        close();
        app.refresh();
      }, { kind: 'signal' }) : null)));
}

function fileName(name, ext) {
  return `${name.replace(/[^\p{L}\p{N}]+/gu, '-').replace(/^-|-$/g, '') || 'stapel'}.${ext}`;
}

async function exportAsPack(deck, cards) {
  const withImages = [];
  for (const c of cards) {
    withImages.push({ ...c, frontImageData: await repo.imageDataUrl(c.frontImage), backImageData: await repo.imageDataUrl(c.backImage) });
  }
  const pack = exportPack({ id: `eigen.${deck.id}`, titel: deck.name, version: '1' }, [deck], withImages);
  saveFile(fileName(deck.name, 'paket.json'), JSON.stringify(pack, null, 1));
}

function exportMenu(deck, cards) {
  const plain = cards.map((c) => ({ front: c.front, back: c.back }));
  const hasImages = cards.some((c) => c.frontImage || c.backImage);
  return openPanel('Stapel ausgeben', (close) => h('div.stack', {},
    hasImages ? h('p.muted', {}, 'Textdateien nehmen keine Bilder mit. Das Inhaltspaket schon.') : null,
    btn('Tab-getrennt (.txt)', () => { saveFile(fileName(deck.name, 'txt'), Csv.write(plain), 'text/plain;charset=utf-8'); close(); }, { icon: 'download' }),
    h('p.caption.muted', {}, 'Für die Android-App und die üblichen Kartenprogramme.'),
    btn('Für Excel (.csv)', () => { saveFile(fileName(deck.name, 'csv'), Csv.writeCsv(plain), 'text/csv;charset=utf-8'); close(); }, { icon: 'download' }),
    btn('Inhaltspaket (.json)', async () => { await exportAsPack(deck, cards); close(); }, { icon: 'download' }),
    h('p.caption.muted', {}, 'Mit Thema, Quelle und Prüfstatus. Lernfortschritt ist nie dabei.')));
}

function bookCard(deck) {
  const n = deckCounts(deck, app.state, app.today);
  return h('a.book', { href: `#/stapel/${deck.id}`, vars: { '--c': subjectColor(deck.colorIndex) } },
    h('div.book-plate', {}, h('div.book-name', {}, deck.name), h('div.book-sub', {}, deck.subject || 'Ohne Fach')),
    h('div.book-foot', {},
      h('span', {}, `${n.total} K.`),
      n.due + n.fresh > 0 ? h('span.book-due', {}, n.due > 0 ? `${n.due} fällig` : `${n.fresh} neu`) : h('span', {}, 'fertig')));
}

export async function renderDecks(root) {
  const { state } = app;
  const subjects = [...new Set(state.decks.map((d) => d.subject).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'de'));
  if (subjectFilter !== null && !subjects.includes(subjectFilter)) subjectFilter = null;
  const shown = subjectFilter === null ? state.decks : state.decks.filter((d) => d.subject === subjectFilter);
  const colorOf = (name) => state.decks.find((d) => d.subject === name)?.colorIndex ?? 0;
  root.append(h('div.stack.stack-lg', {},
    h('div.row-between', {},
      h('div.section-head', {}, h('h1.title', {}, 'Stapel'), h('span.count', {}, String(shown.length).padStart(2, '0'))),
      h('div.row', {}, linkBtn('Inhaltspakete', '#/pakete', { kind: 'quiet', icon: 'book' }), btn('Neuer Stapel', () => editDeck(), { kind: 'signal', icon: 'plus' }))),
    subjects.length > 0 ? h('div.seg', { role: 'group', 'aria-label': 'Fächer' },
      h('button.plate', { type: 'button', 'aria-pressed': String(subjectFilter === null), onclick: () => { subjectFilter = null; app.refresh(); } }, 'Alle'),
      subjects.map((s) => h('button.plate', { type: 'button', 'aria-pressed': String(subjectFilter === s), onclick: () => { subjectFilter = s; app.refresh(); } },
        h('span.lamp', { vars: { '--lamp': subjectColor(colorOf(s)) }, css: { display: 'inline-block', marginRight: '8px' } }), s))) : null,
    h('div.shelf', {},
      shown.map(bookCard),
      h('button.book.book-new', { type: 'button', onclick: () => editDeck() }, icon('plus'), 'Neuer Stapel'))));
}

function cardRow(deck, card, today) {
  const s = scheduleOf(card);
  let state;
  if (isNew(s)) state = 'NEU';
  else if (card.dueEpochDay <= today) state = 'FÄLLIG';
  else state = `FÄLLIG ${dateShort(card.dueEpochDay)}`;
  const line = sourceLine(card);
  return h('button.list-row', { type: 'button', onclick: () => editCard(deck, card), 'aria-label': `Karte bearbeiten: ${card.front || 'Bildkarte'}` },
    h('span.list-main', {},
      h('span.list-title.clip', {}, card.front || 'Bildkarte'),
      h('span.list-sub.clip', {}, card.back),
      line ? h('span.list-sub', { css: { display: 'block' } }, line) : null),
    h('span.stack', { vars: { '--gap': '4px' }, css: { alignItems: 'flex-end' } },
      h('span.mono', { css: { fontSize: '11px' }, class: card.dueEpochDay <= today ? 'signal-text' : 'muted' }, state),
      card.verified === false ? h('span.tag.tag-warn', {}, 'Ungeprüft') : null,
      card.frontImage || card.backImage ? h('span.tag', {}, 'Bild') : null));
}

export async function renderDeck(root, id) {
  const { state, today } = app;
  const deck = state.decks.find((d) => d.id === id);
  if (!deck) {
    root.append(h('div.empty', {}, h('h1.title', {}, 'Stapel nicht gefunden'), linkBtn('Zu den Stapeln', '#/stapel')));
    return;
  }
  const cards = state.cards.filter((c) => c.deckId === id);
  const n = deckCounts(deck, state, today);
  const pack = deck.packId ? state.packs.find((p) => p.id === deck.packId) : null;
  const learnable = n.due + n.fresh;
  const m = mastery(cards.map(scheduleOf));
  let query = '';
  const list = h('div.rows');
  const drawList = () => {
    const q = low(query);
    const shown = q ? cards.filter((c) => low(c.front).includes(q) || low(c.back).includes(q)) : cards;
    fill(list, shown.length > 0 ? shown.map((c) => cardRow(deck, c, today)) : h('p.muted', { css: { padding: '12px 0' } }, 'Keine Karte passt zur Suche.'));
  };
  root.append(h('div.stack.stack-lg', {},
    h('div.row', {},
      key('back', 'Zu den Stapeln', () => app.go('/stapel')),
      h('div.grow', {}, h('h1.title', {}, deck.name), h('p.caption.muted', {}, [deck.subject, pack ? `Paket: ${pack.titel}` : null].filter(Boolean).join(' · ') || 'Ohne Fach')),
      key('edit', 'Stapel bearbeiten', () => editDeck(deck))),
    cards.length === 0 ? h('div.empty', {},
      h('p.title', {}, 'Noch keine Karten'),
      h('p', {}, 'Vorne die Frage, hinten die Antwort. Kurz und eindeutig lernt es sich am besten.'),
      btn('Erste Karte', () => editCard(deck), { kind: 'signal', icon: 'plus' }),
      h('div.row', { css: { justifyContent: 'center' } },
        btn('Aus Text vorschlagen', () => suggestPanel(deck), { kind: 'quiet', icon: 'spark' }),
        btn('Datei einlesen', () => importText(deck), { kind: 'quiet', icon: 'upload' })))
      : h('div.stack', {},
        h('div.grid-2', {},
          h('div.stack', {},
            learnable > 0
              ? linkBtn(`Lernen · ${learnable}`, `#/lernen/stapel/${deck.id}`, { kind: 'signal', big: true, icon: 'play', wide: true })
              : h('p.label', {}, 'Für heute ist dieser Stapel durch.'),
            learnable === 0 ? linkBtn('Trotzdem üben', `#/lernen/stapel/${deck.id}/alle`, { icon: 'play' }) : null),
          h('div.stack', { vars: { '--gap': '6px' } },
            h('p.mono', {}, `${n.total} Karten · ${n.fresh} neu · ${n.due} fällig${n.unverified ? ` · ${n.unverified} ungeprüft` : ''}`),
            bar(m, 'var(--good)'),
            h('p.caption.muted', {}, `${m} % aus der Lernphase`))),
        h('div.row', {},
          btn('Neue Karte', () => editCard(deck), { icon: 'plus' }),
          btn('Aus Text', () => suggestPanel(deck), { kind: 'quiet', icon: 'spark' }),
          btn('Einlesen', () => importText(deck), { kind: 'quiet', icon: 'upload' }),
          btn('Ausgeben', () => exportMenu(deck, cards), { kind: 'quiet', icon: 'download' })),
        cards.length > 8 ? field('Karten suchen', h('input.input', { type: 'search', oninput: (e) => { query = e.target.value; drawList(); } })) : null,
        sectionHead('Karten', cards.length),
        list)));
  drawList();
}
