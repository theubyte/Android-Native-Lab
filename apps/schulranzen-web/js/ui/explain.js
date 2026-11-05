// Optional Erklärhilfe with the learner's own Claude API key, off by default. There is no
// server to hide a key behind, and a key in app code would be public, so only the learner
// can bring one. It is held in memory unless they tick "merken", never enters a backup, and
// each request is started by hand and shows what leaves the device.
import { h, fill, btn, checkbox, field, openPanel } from './dom.js';
import { app } from '../app.js';
import { getSetting, setSetting } from '../data/repo.js';
import { cleanCloze } from './cardview.js';
import { ENDPOINT, MODEL, MODEL_THRIFTY, describeStatus, readReply, requestFor } from '../core/claudeapi.js';

/** Only in memory unless the learner asked to keep it. */
let sessionKey = null;

export const explainEnabled = () => app.setting('claudeEnabled') === true;
export const explainAvailable = () => explainEnabled();

export async function loadKey() {
  return sessionKey ?? await getSetting('claudeKey');
}

export async function storeKey(key, remember) {
  sessionKey = key || null;
  await setSetting('claudeKey', remember && key ? key : null);
}

export async function forgetKey() {
  sessionKey = null;
  await setSetting('claudeKey', null);
}

async function ask(card, key) {
  const model = app.setting('claudeModel', MODEL);
  const { headers, body } = requestFor(model, key, card.front, cleanCloze(card.back));
  let res;
  try {
    res = await fetch(ENDPOINT, { method: 'POST', headers, body: JSON.stringify(body), referrerPolicy: 'no-referrer', credentials: 'omit' });
  } catch {
    return { error: navigator.onLine === false ? 'Keine Verbindung. Die Erklärhilfe braucht Internet.' : 'Der Dienst ist nicht erreichbar.' };
  }
  if (!res.ok) return { error: describeStatus(res.status) };
  return readReply(await res.json());
}

export function explainPanel(card) {
  return openPanel('Erklären lassen', (close) => {
    const out = h('div.stack');
    const run = async () => {
      const key = await loadKey();
      if (!key) {
        fill(out, h('p.signal-text', {}, 'Noch kein Schlüssel. Trag ihn unter Mehr ein.'));
        return;
      }
      fill(out, h('p.muted', { role: 'status' }, 'Claude schreibt …'));
      const reply = await ask(card, key);
      fill(out, reply.error
        ? h('p.signal-text', { role: 'alert' }, reply.error)
        : h('div.stack', {}, h('p.pre', {}, reply.text), h('p.caption.muted', {}, 'Von einer KI, kann Fehler enthalten. Im Zweifel gilt dein Buch.')));
    };
    return h('div.stack', {},
      h('p.caption.muted', {}, 'Diese Karte geht an Anthropic. Sonst nichts:'),
      h('div.panel', {}, h('p.label.pre', {}, card.front), h('p.pre.muted', {}, cleanCloze(card.back))),
      h('div.row', {}, btn('Jetzt senden', run, { kind: 'signal', icon: 'spark' }), btn('Abbrechen', () => close(), { kind: 'quiet' })),
      out);
  });
}

/** The settings block on the "Mehr" screen. */
export function explainSettings(refresh) {
  const on = explainEnabled();
  if (!on) {
    return h('div.stack', {},
      h('p.muted', {}, 'Aus. Die App kommt ohne aus und geht ohne das nie ins Internet.'),
      btn('Einrichten …', () => setupPanel().then(refresh), { icon: 'spark' }));
  }
  return h('div.stack', {},
    h('p', {}, 'An, mit deinem eigenen Schlüssel.'),
    h('div.row', {},
      btn('Ändern', () => setupPanel().then(refresh), { kind: 'quiet' }),
      btn('Ausschalten und Schlüssel löschen', async () => {
        await forgetKey();
        await app.set('claudeEnabled', null);
        refresh();
      }, { kind: 'quiet', icon: 'trash' })));
}

function setupPanel() {
  return openPanel('Erklärhilfe einrichten', (close) => {
    let remember = false;
    let accepted = explainEnabled();
    let thrifty = app.setting('claudeModel', MODEL) === MODEL_THRIFTY;
    const key = h('input.input', { type: 'password', 'data-autofocus': '', autocomplete: 'off', spellcheck: 'false', placeholder: 'sk-ant-…' });
    const error = h('p.caption.signal-text', { role: 'alert' });
    return h('form.stack', {
      onsubmit: async (e) => {
        e.preventDefault();
        const value = key.value.trim();
        if (!accepted) { error.textContent = 'Bitte erst den Hinweis bestätigen.'; return; }
        if (!value && !(await loadKey())) { error.textContent = 'Der Schlüssel fehlt.'; key.focus(); return; }
        if (value) await storeKey(value, remember);
        await app.set('claudeEnabled', true);
        await app.set('claudeModel', thrifty ? MODEL_THRIFTY : MODEL);
        close();
      },
    },
    h('div.notice', {}, h('div', {},
      h('p.label', {}, 'Nur für Leute mit eigenem API-Schlüssel'),
      h('ul', { css: { margin: '6px 0 0', paddingLeft: '20px' } },
        h('li', {}, 'Du zahlst die Anfragen selbst bei Anthropic.'),
        h('li', {}, 'Die Karte, die du erklären lässt, geht direkt an Anthropic.'),
        h('li', {}, 'Der Schlüssel liegt nur in diesem Browser und kommt in keine Sicherung. Wer dieses Gerät benutzt, könnte ihn auslesen.'),
        h('li', {}, 'Lege für die App einen eigenen Schlüssel mit niedrigem Limit an, den du jederzeit widerrufen kannst.')))),
    checkbox('Verstanden', accepted, (v) => { accepted = v; }),
    field('API-Schlüssel', key),
    checkbox('Auf diesem Gerät merken', remember, (v) => { remember = v; }, 'Sonst fragt die App nach jedem Neustart.'),
    checkbox('Günstiges Modell (Haiku)', thrifty, (v) => { thrifty = v; }),
    error,
    h('div.drawer-actions', {}, btn('Abbrechen', () => close(), { kind: 'quiet' }), h('button.btn.btn-signal', { type: 'submit' }, 'Einschalten')));
  });
}
