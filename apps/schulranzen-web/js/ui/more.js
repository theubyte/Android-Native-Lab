// "Mehr": backup and restore, storage status, installing, appearance, help, about.
import { h, btn, linkBtn, plates, icon, openPanel, ask, toast, saveFile, pickFile, readText, plural } from './dom.js';
import { app, applyTheme } from '../app.js';
import * as repo from '../data/repo.js';
import { deleteEverything } from '../data/db.js';
import { merge, parseBackup } from '../core/backup.js';
import { dateLong, isoOf, toEpochDay } from '../core/dates.js';
import { APP_VERSION } from '../version.js';
import { askPersist, dataAtRisk, isStandalone, offlineReady, storageStatus } from '../platform.js';
import { installHint } from './banners.js';
import { explainSettings } from './explain.js';

const section = (title, ...children) => h('section.panel', {}, h('div.stack', {}, h('h2.heading', {}, title), children));

function mb(bytes) {
  if (bytes === null || bytes === undefined) return '–';
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1).replace('.', ',')} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(1).replace('.', ',')} GB`;
}

async function doBackup() {
  try {
    const backup = await repo.makeBackup(APP_VERSION);
    const saved = await saveFile(`schulranzen-sicherung-${isoOf(toEpochDay())}.json`, JSON.stringify(backup));
    if (!saved) return;
    await app.set('lastBackupAt', Date.now());
    toast('Sicherung gespeichert. Lege sie an einen sicheren Ort.');
    app.refresh();
  } catch (e) {
    toast(`Sicherung fehlgeschlagen: ${e.message}`);
  }
}

async function doRestore() {
  const file = await pickFile('.json,application/json');
  if (!file) return;
  const { backup, errors } = parseBackup(await readText(file));
  if (!backup) {
    await openPanel('Sicherung nicht lesbar', (close) => h('div.stack', {},
      h('p', {}, 'Nichts wurde verändert.'),
      errors.map((e) => h('p.muted', {}, e)),
      h('div.drawer-actions', {}, btn('Schließen', () => close()))));
    return;
  }
  const d = backup.data;
  await openPanel('Sicherung laden', (close) => h('div.stack', {},
    h('p', {}, file.name),
    h('p.mono.muted', {}, `Vom ${dateLong(toEpochDay(new Date(backup.createdAt)))}: ${plural(d.decks.length, 'Stapel', 'Stapel')}, ${plural(d.cards.length, 'Karte', 'Karten')}, ${plural(d.exams.length, 'Prüfung', 'Prüfungen')}`),
    btn('Zusammenführen', async () => {
      const local = await repo.readForMerge();
      const m = merge(local, d);
      await repo.replaceAll(m.data);
      toast(`Zusammengeführt: ${m.added} neu, ${m.updated} aktualisiert.`);
      close();
      location.reload();
    }, { kind: 'signal' }),
    h('p.caption.muted', {}, 'Für zwei Geräte: Nichts geht verloren, bei gleichen Karten gilt der neuere Lernstand.'),
    btn('Alles ersetzen', async () => {
      const yes = await ask('Alles ersetzen?', 'Alle Daten auf diesem Gerät werden durch die Sicherung ersetzt.', 'Ersetzen', { danger: true });
      if (!yes) return;
      await repo.replaceAll(d);
      close();
      location.reload();
    }, { kind: 'quiet', icon: 'warn' })));
}

export async function renderMore(root) {
  const status = await storageStatus();
  const offline = await offlineReady();
  const last = app.setting('lastBackupAt');
  const theme = app.setting('theme', 'auto');
  const hint = installHint();
  const risk = dataAtRisk() && !isStandalone();
  const refresh = () => app.refresh();

  root.append(h('div.stack.stack-lg', {},
    h('h1.title', {}, 'Mehr'),
    h('nav.row', { 'aria-label': 'Weitere Bereiche' },
      linkBtn('Inhaltspakete', '#/pakete', { icon: 'book' }),
      linkBtn('Fehlerheft', '#/fehler', { icon: 'error' }),
      linkBtn('Statistik', '#/statistik', { icon: 'chart' })),
    h('div.grid-2', {},
      h('div.stack', {},
        section('Sicherung',
          h(`div.notice${risk ? '.notice-strong' : ''}`, {}, icon('warn'), h('div', {},
            h('p.label', {}, risk ? 'Safari kann deine Daten löschen' : 'Deine Daten liegen nur hier'),
            h('p', {}, risk
              ? 'Safari löscht Daten von Webseiten nach einigen Wochen ohne Besuch. Lege die App auf den Home-Bildschirm und sichere regelmäßig.'
              : 'Kein Konto, kein Server. Wird dieser Browser zurückgesetzt, ist alles weg, außer du hast eine Sicherung.'))),
          h('p.mono', {}, last ? `Letzte Sicherung: ${dateLong(toEpochDay(new Date(last)))}` : 'Noch keine Sicherung'),
          h('div.row', {}, btn('Jetzt sichern', doBackup, { kind: 'signal', icon: 'download' }), btn('Sicherung laden', doRestore, { icon: 'upload' })),
          h('p.caption.muted', {}, 'So bringst du deine Daten auch auf ein anderes Gerät. Die Datei enthält Karten, Bilder und Lernstand, aber keinen API-Schlüssel.')),
        section('Speicher',
          h('p', {}, status.persisted === true ? 'Dauerhafter Speicher ist aktiv.' : status.persisted === false ? 'Der Browser darf die Daten bei Platzmangel löschen.' : 'Dieser Browser sagt nichts über dauerhaften Speicher.'),
          status.usage !== null ? h('p.mono.muted', {}, `Belegt: ${mb(status.usage)}${status.quota ? ` von ${mb(status.quota)}` : ''}`) : null,
          status.persisted === false ? h('div.row', {}, btn('Dauerhaft anfragen', async () => {
            const ok = await askPersist();
            toast(ok ? 'Dauerhafter Speicher ist aktiv.' : 'Der Browser hat abgelehnt. Installieren hilft oft, und: regelmäßig sichern.');
            refresh();
          })) : null,
          h('p', {}, offline ? 'Läuft offline.' : 'Offline-Betrieb wird eingerichtet oder ist hier nicht möglich (nur über HTTPS).')),
        hint ? section('Als App installieren',
          h('p', {}, hint.text),
          hint.action ? hint.action() : null) : null),
      h('div.stack', {},
        section('Darstellung',
          plates('Farbschema', [['auto', 'Wie das Gerät'], ['light', 'Hell'], ['dark', 'Dunkel']], theme, async (v) => {
            applyTheme(v);
            await app.set('theme', v);
          })),
        section('Tastatur',
          h('table.table-simple', {},
            h('tbody', {},
              [['Leertaste', 'Karte umdrehen'], ['1 2 3 4', 'Nochmal, Schwer, Gut, Leicht; bei Multiple Choice die Antwort'], ['Enter', 'Prüfen, dann den Vorschlag übernehmen'], ['U', 'Letzte Antwort zurücknehmen'], ['Esc', 'Sitzung beenden, Fenster schließen'], ['← →', 'Probeprüfung: vorige und nächste Frage']]
                .map(([k, t]) => h('tr', {}, h('td', {}, h('span.kbd', {}, k)), h('td', {}, t)))))),
        section('Erklärhilfe (Option für Fortgeschrittene)', explainSettings(refresh)),
        section('Über diese App',
          h('p', {}, `Schulranzen für den Browser, Version ${APP_VERSION}.`),
          h('p', {}, 'Kein Konto, kein Server, keine Analyse, keine fremden Cookies. Alles liegt in deinem Browser. Nur die Erklärhilfe sendet etwas, wenn du sie einschaltest und benutzt.'),
          h('p.caption.muted', {}, 'Schriften: Bricolage Grotesque, Inter, IBM Plex Mono, alle unter SIL Open Font License.'),
          h('div.row', {}, h('a', { href: 'fonts/OFL-bricolagegrotesque.txt', target: '_blank', rel: 'noopener' }, 'Lizenz Bricolage'), h('a', { href: 'fonts/OFL-inter.txt', target: '_blank', rel: 'noopener' }, 'Lizenz Inter'), h('a', { href: 'fonts/OFL-ibmplexmono.txt', target: '_blank', rel: 'noopener' }, 'Lizenz Plex Mono'))),
        section('Alles löschen',
          h('p.muted', {}, 'Löscht alle Stapel, Karten, Prüfungen und den Lernstand auf diesem Gerät.'),
          h('div.row', {}, btn('Alles löschen', async () => {
            const yes = await ask('Wirklich alles löschen?', 'Das lässt sich ohne Sicherung nicht rückgängig machen.', 'Alles löschen', { danger: true });
            if (!yes) return;
            await deleteEverything();
            try { localStorage.clear(); } catch { /* nothing kept there */ }
            location.hash = '/heute';
            location.reload();
          }, { kind: 'quiet', icon: 'trash' })))))));
}
