// The warnings that protect the learner's data, and the install hint.
import { h, icon, btn, linkBtn } from './dom.js';
import { app } from '../app.js';
import { canPromptInstall, dataAtRisk, isIos, isStandalone, promptInstall, isSafari } from '../platform.js';

const WEEK = 7 * 86400000;

/** True once there is unsaved work that would hurt to lose. */
export function backupDue() {
  const changed = app.setting('changedAt');
  if (!changed || app.state.cards.length === 0) return false;
  const last = app.setting('lastBackupAt');
  if (!last) return true;
  return changed > last && Date.now() - last > WEEK;
}

/** Strong on Safari, where the browser may really delete the data. */
export function backupBanner() {
  if (!backupDue()) return null;
  const last = app.setting('lastBackupAt');
  const risk = dataAtRisk() && !isStandalone();
  return h(`div.notice${risk ? '.notice-strong' : ''}`, { role: 'alert' }, icon('warn'),
    h('div.stack', { vars: { '--gap': '8px' }, class: 'grow' },
      h('p.label', {}, risk ? 'Safari kann deine Daten löschen' : 'Deine Daten sind nicht gesichert'),
      h('p', {}, risk
        ? 'Safari löscht Daten von Webseiten, die eine Weile nicht besucht wurden. Sichere deine Karten als Datei und lege die App auf den Home-Bildschirm.'
        : `Alles liegt nur in diesem Browser. ${last ? 'Deine letzte Sicherung ist älter als eine Woche.' : 'Du hast noch nie gesichert.'}`),
      h('div.row', {}, linkBtn('Jetzt sichern', '#/mehr', { icon: 'download' }))));
}

/** The right way to install on this device; null when it is installed or impossible. */
export function installHint() {
  if (isStandalone()) return null;
  if (isIos()) {
    return {
      text: isSafari()
        ? 'So wird es eine App: Tippe unten auf Teilen, dann auf „Zum Home-Bildschirm“. Das schützt auch deine Daten besser.'
        : 'Zum Installieren öffne diese Seite in Safari. Dann Teilen und „Zum Home-Bildschirm“.',
    };
  }
  if (canPromptInstall()) {
    return {
      text: 'Als App installieren: eigenes Symbol, startet ohne Browserleiste, läuft offline.',
      action: () => btn('Installieren', () => promptInstall().then(() => app.refresh()), { icon: 'download' }),
    };
  }
  if (isSafari()) return { text: 'So wird es eine App: im Menü Ablage „Zum Dock hinzufügen“ wählen.' };
  return null;
}

export function installBanner() {
  if (app.setting('installDismissed')) return null;
  const hint = installHint();
  if (!hint) return null;
  return h('div.panel-flat', {}, h('div.stack', { vars: { '--gap': '10px' } },
    h('p', {}, hint.text),
    h('div.row', {}, hint.action ? hint.action() : null, btn('Nicht mehr zeigen', async () => {
      await app.set('installDismissed', true);
      app.refresh();
    }, { kind: 'quiet' }))));
}
