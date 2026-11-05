// What the optional Erklärhilfe sends and how replies are read; no network and no storage
// here. Plain fetch instead of the Anthropic SDK: the app has no build step and loads nothing
// from a CDN, so the SDK cannot be imported.
export const ENDPOINT = 'https://api.anthropic.com/v1/messages';
export const MODEL = 'claude-opus-5-5';
/** The cheap choice for a learner paying with their own key. */
export const MODEL_THRIFTY = 'claude-haiku-5-5';
const FALLBACK_BETA = 'server-side-fallback-2026-07-01';

const SYSTEM = `Du bist ein geduldiger Lernhelfer für Lernende an Schule und Berufsschule.
Erkläre auf Deutsch, schülergerecht und fachlich richtig: erst die Kernaussage in ein bis zwei Sätzen, dann das Wichtigste in kurzen Absätzen oder Stichpunkten, mit einem Beispiel aus der Praxis, wenn es passt. Nenne Formeln mit Einheiten und rechne Beispiele vor.
Erfinde keine Normnummern oder Zahlenwerte; sag ehrlich, wenn du dir unsicher bist. Halte dich kurz (höchstens etwa 250 Wörter).
Schreibe schlichten Text ohne Markdown-Überschriften; Stichpunkte mit „•“ sind erlaubt.`;

export function requestFor(model, key, question, answer) {
  const headers = {
    'content-type': 'application/json',
    'x-api-key': key,
    'anthropic-version': '2023-06-01',
    // Anthropic's opt-in for calls straight from a web page.
    'anthropic-dangerous-direct-browser-access': 'true',
  };
  const body = {
    model,
    max_tokens: 2000,
    system: SYSTEM,
    output_config: { effort: 'low' },
    messages: [{ role: 'user', content: `Erkläre mir diese Karteikarte.\n\nFrage:\n${question}\n\nAntwort:\n${answer}` }],
  };
  // Haiku has no server-side fallback after a safety decline.
  if (model !== MODEL_THRIFTY) {
    headers['anthropic-beta'] = FALLBACK_BETA;
    body.fallbacks = 'default';
  }
  return { headers, body };
}

/** Learner-facing text for each way a call can fail; never contains the key. */
export function describeStatus(status) {
  if (status === 401 || status === 403) return 'Der Schlüssel wurde abgelehnt. Bitte unter Mehr prüfen.';
  if (status === 429) return 'Zu viele Anfragen in kurzer Zeit. Kurz warten und nochmal versuchen.';
  if (status === 529) return 'Der Dienst ist gerade überlastet. Gleich nochmal versuchen.';
  if (status >= 500) return 'Beim Dienst ist ein Fehler aufgetreten. Später nochmal versuchen.';
  return 'Die Anfrage wurde nicht angenommen.';
}

export function readReply(json) {
  if (json?.stop_reason === 'refusal') return { error: 'Dazu gibt der Assistent keine Antwort.' };
  const text = (json?.content ?? []).filter((b) => b.type === 'text').map((b) => b.text).join('\n').trim();
  return text ? { text } : { error: 'Es kam keine Antwort zurück. Nochmal versuchen.' };
}
