// Inhaltspakete: import with an honest preview, update without losing progress, removal.
import { h, fill, btn, icon, key, checkbox, openPanel, ask, toast, pickFile, readText, plural, sectionHead, linkBtn } from './dom.js';
import { app } from '../app.js';
import * as repo from '../data/repo.js';
import { compareVersions, parsePack } from '../core/contentpack.js';
import { dateLong, toEpochDay } from '../core/dates.js';

function summaryLines(s) {
  const out = [];
  if (s.newDecks > 0) out.push(plural(s.newDecks, 'neuer Stapel', 'neue Stapel'));
  if (s.newCards > 0) out.push(plural(s.newCards, 'neue Karte', 'neue Karten'));
  if (s.changedCards > 0) out.push(`${plural(s.changedCards, 'Karte', 'Karten')} geändert, Lernstand bleibt`);
  if (s.unchangedCards > 0) out.push(`${plural(s.unchangedCards, 'Karte', 'Karten')} unverändert`);
  if (s.keptOwnEdits > 0) out.push(`${plural(s.keptOwnEdits, 'Karte', 'Karten')} von dir geändert: dein Text bleibt`);
  if (s.skippedUnverified > 0) out.push(`${plural(s.skippedUnverified, 'ungeprüfte Karte', 'ungeprüfte Karten')} ausgelassen`);
  return out;
}

/** Preview and import. The file is checked completely before anything is stored. */
export async function previewAndImport(text, fileName) {
  const { pack, errors, warnings } = parsePack(text);
  if (!pack) {
    await openPanel('Paket nicht lesbar', (close) => h('div.stack', {},
      h('p', {}, `${fileName}: Nichts wurde übernommen.`),
      h('ul', { css: { margin: '0', paddingLeft: '20px' } }, errors.slice(0, 12).map((e) => h('li', {}, e))),
      errors.length > 12 ? h('p.muted', {}, `… und ${errors.length - 12} weitere.`) : null,
      h('div.drawer-actions', {}, btn('Schließen', () => close()))));
    return;
  }
  const options = { removeMissing: false, skipUnverified: false };
  const known = app.state.packs.find((p) => p.id === pack.id);
  await openPanel(known ? 'Paket aktualisieren' : 'Paket ansehen', (close) => {
    const body = h('div.stack');
    const draw = () => {
      const plan = repo.previewPack(pack, app.state, options);
      const s = plan.summary;
      const older = known && compareVersions(pack.version, known.version) < 0;
      const same = known && compareVersions(pack.version, known.version) === 0;
      const decks = pack.stapel.slice(0, 8);
      fill(body, 
        pack.beispiel ? h('span.tag', { css: { alignSelf: 'flex-start' } }, 'Beispielpaket') : null,
        h('div', {}, h('p.heading', {}, pack.titel), h('p.mono.muted', {}, `Version ${pack.version}${pack.stand ? ` · Stand ${pack.stand}` : ''}`),
          pack.beschreibung ? h('p', { css: { marginTop: '6px' } }, pack.beschreibung) : null,
          pack.autor ? h('p.caption.muted', {}, `Von: ${pack.autor}`) : null,
          pack.lizenz ? h('p.caption.muted', {}, `Nutzung: ${pack.lizenz}`) : null),
        known ? h('p.caption.muted', {}, `Vorhanden: Version ${known.version}.`) : null,
        older ? h('p.caption.signal-text', {}, 'Dieses Paket ist älter als das vorhandene.') : null,
        same ? h('p.caption.muted', {}, 'Gleiche Version wie vorhanden.') : null,
        h('div.panel', {}, h('ul', { css: { margin: '0', paddingLeft: '20px' } }, summaryLines(s).map((l) => h('li', {}, l)))),
        s.unverifiedCards > 0 ? h('div.notice', {}, icon('warn'), h('div', {},
          h('p.label', {}, plural(s.unverifiedCards, 'Karte ist ungeprüft', 'Karten sind ungeprüft')),
          h('p', {}, 'Noch niemand hat sie mit der Quelle verglichen. Sie können Fehler enthalten und sind beim Lernen markiert.'),
          checkbox('Ungeprüfte Karten auslassen', options.skipUnverified, (v) => { options.skipUnverified = v; draw(); }))) : null,
        s.removedCards > 0 ? h('div.panel-flat', {},
          h('p.label', {}, `${plural(s.removedCards, 'Karte fehlt', 'Karten fehlen')} in dieser Version`),
          h('p.caption.muted', {}, s.removedWithProgress > 0 ? `${s.removedWithProgress} davon hast du schon gelernt.` : 'Noch nicht gelernt.'),
          checkbox('Löschen statt behalten', options.removeMissing, (v) => { options.removeMissing = v; draw(); }, 'Sonst bleiben sie als deine eigenen Karten mit ihrem Lernstand.')) : null,
        warnings.map((w) => h('p.caption.muted', {}, w)),
        h('section.stack', {}, sectionHead('Stapel', pack.stapel.length),
          h('div.rows', {}, decks.map((d) => h('div.list-row', {}, h('div.list-main', {},
            h('div.list-title', {}, d.name),
            h('div.list-sub', {}, [d.fach, plural(d.karten.length, 'Karte', 'Karten')].filter(Boolean).join(' · ')),
            d.karten[0] ? h('div.list-sub.clip', {}, `z. B.: ${d.karten[0].vorne}`) : null,
            d.karten[0]?.quelle?.buch ? h('div.list-sub', {}, `Quelle: ${d.karten[0].quelle.buch}${d.karten[0].quelle.seite ? `, S. ${d.karten[0].quelle.seite}` : ''}`) : null)))),
          pack.stapel.length > 8 ? h('p.caption.muted', {}, `… und ${pack.stapel.length - 8} weitere.`) : null),
        h('p.caption.muted', {}, 'Dein Lernstand bleibt bei jeder Aktualisierung erhalten.'),
        h('div.drawer-actions', {},
          btn('Abbrechen', () => close(false), { kind: 'quiet' }),
          btn(known ? 'Aktualisieren' : 'Übernehmen', async () => {
            await repo.applyPack(pack, repo.previewPack(pack, app.state, options));
            toast(`„${pack.titel}“ ${known ? 'aktualisiert' : 'übernommen'}.`);
            close(true);
            app.refresh();
          }, { kind: 'signal' })),
      );
    };
    draw();
    return body;
  });
}

async function importFile() {
  const file = await pickFile('.json,application/json');
  if (!file) return;
  await previewAndImport(await readText(file), file.name);
}

/** The small sample, so a first visitor can see the flow. */
export async function importSample() {
  try {
    const res = await fetch('beispiele/app-kennenlernen.json', { cache: 'no-cache' });
    if (!res.ok) throw new Error(String(res.status));
    await previewAndImport(await res.text(), 'Beispielpaket');
  } catch {
    toast('Das Beispiel ließ sich nicht laden.');
  }
}

function packRow(pack, state) {
  const cards = state.cards.filter((c) => c.packId === pack.id);
  const unverified = cards.filter((c) => c.verified === false).length;
  return h('div.list-row', { css: { cursor: 'default' } },
    h('div.list-main', {},
      h('div.list-title', {}, pack.titel, pack.beispiel ? h('span.tag', { css: { marginLeft: '8px' } }, 'Beispiel') : null),
      h('div.list-sub', {}, `Version ${pack.version} · ${plural(cards.length, 'Karte', 'Karten')}${unverified ? ` · ${unverified} ungeprüft` : ''} · seit ${dateLong(toEpochDay(new Date(pack.importedAt)))}`)),
    key('trash', `Paket ${pack.titel} entfernen`, () => removePanel(pack, cards)));
}

function removePanel(pack, cards) {
  return openPanel('Paket entfernen', (close) => h('div.stack', {},
    h('p', {}, `„${pack.titel}“ mit ${plural(cards.length, 'Karte', 'Karten')}.`),
    btn('Karten behalten', async () => {
      await repo.removePack(pack.id, true, app.state);
      toast('Paket entfernt, Karten sind jetzt deine eigenen.');
      close();
      app.refresh();
    }, { icon: 'cards' }),
    h('p.caption.muted', {}, 'Sie bleiben mit Lernstand, werden aber nicht mehr aktualisiert.'),
    btn('Karten löschen', async () => {
      const yes = await ask('Wirklich löschen?', 'Karten und Lernstand dieses Pakets gehen verloren.', 'Löschen', { danger: true });
      if (!yes) return;
      await repo.removePack(pack.id, false, app.state);
      toast('Paket und Karten gelöscht.');
      close();
      app.refresh();
    }, { kind: 'quiet', icon: 'trash' })));
}

export async function renderPacks(root) {
  const { state } = app;
  root.append(h('div.stack.stack-lg', {},
    h('div.row', {},
      key('back', 'Zurück', () => app.go('/mehr')),
      h('h1.title.grow', {}, 'Inhaltspakete')),
    h('p.muted', {}, 'Ein Paket ist eine Datei mit fertigen Stapeln, mit Thema, Quelle und Prüfstatus. Lädst du eine neue Version, bleibt dein Lernstand erhalten.'),
    h('div.row', {},
      btn('Paket öffnen', importFile, { kind: 'signal', icon: 'upload' }),
      btn('Beispiel ansehen', importSample, { kind: 'quiet', icon: 'book' })),
    h('section.stack', {}, sectionHead('Vorhanden', state.packs.length),
      state.packs.length === 0 ? h('p.muted', {}, 'Noch kein Paket geladen.') : h('div.rows', {}, state.packs.map((p) => packRow(p, state)))),
    h('p.caption.muted', {}, 'Pakete bleiben auf diesem Gerät. Karten aus fremden Büchern nur für dich selbst verwenden und nicht weitergeben.'),
    linkBtn('Zu den Stapeln', '#/stapel', { kind: 'quiet' })));
}
