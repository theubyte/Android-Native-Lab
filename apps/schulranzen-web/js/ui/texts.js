// Shared wording, so the same thing is called the same everywhere (and as on Android).
import { plural } from './dom.js';
import { GRADE_WORDS, SOLID_PERCENT } from '../core/mockexam.js';

export const confidenceText = {
  NONE: 'Noch keine Grundlage',
  LOW: 'Wenig Belege: nur Karten, noch keine Probeprüfung',
  MEDIUM: 'Mittel: wenige Fragen unter Prüfungsbedingungen',
  HIGH: 'Gut belegt durch Probeprüfungen',
};

export const gradeLine = (grade) => `Note ${grade} · ${GRADE_WORDS[grade]}`;

/** "Was fehlt noch": one line per gap, with what to do about it. */
export function gapText(g) {
  switch (g.kind) {
    case 'NO_CARDS': return 'Noch keine Karten. Lege welche an oder lade ein Paket.';
    case 'FEW_CARDS': return `${plural(g.count, 'Karte', 'Karten')} mehr, dann ist der Stoff gut abgedeckt.`;
    case 'NEW_CARDS': return `${plural(g.count, 'neue Karte', 'neue Karten')} noch nie gelernt.`;
    case 'DUE_CARDS': return `${plural(g.count, 'Karte', 'Karten')} fällig.`;
    case 'SHAKY_CARDS': return `${plural(g.count, 'Karte sitzt', 'Karten sitzen')} noch nicht sicher.`;
    case 'TOPICS_MISSING': return `Keine Karten zu: ${g.names.join(', ')}.`;
    case 'TOPICS_WEAK': return `Noch nicht gelernt: ${g.names.join(', ')}.`;
    case 'LINKS_OPEN': return `${plural(g.count, 'Stoff-Eintrag', 'Stoff-Einträge')} noch offen.`;
    case 'NO_MOCK': return 'Noch keine Probeprüfung geschrieben.';
    case 'OLD_MOCK': return `Letzte Probeprüfung vor ${plural(g.count, 'Tag', 'Tagen')}. Zeit für eine neue.`;
    case 'MOCK_LOW': return `Probeprüfung bei ${g.count} %. Ziel: ${Math.trunc(SOLID_PERCENT)} %.`;
    default: return '';
  }
}

export const stageTitle = {
  MATERIAL: 'Stoff durcharbeiten', CARDS: 'Karten schreiben', LEARN: 'Karten lernen', MOCK: 'Probeprüfung bestehen', FINAL: 'Letzter Durchgang',
};

export function stageDetail(s) {
  switch (s.kind) {
    case 'MATERIAL': return s.total === 0 ? 'Stoff eintragen, etwa Buchseiten' : `${s.done} von ${s.total} Einträgen erledigt`;
    case 'CARDS': return `${s.done} von ${s.total} Karten`;
    case 'LEARN': return s.total === 0 ? 'Noch keine Karten' : `${s.done} von ${s.total} Karten sitzen`;
    case 'MOCK': return s.done === 0 ? `Ziel: mindestens ${Math.trunc(SOLID_PERCENT)} %` : `Bestes Ergebnis ${s.done} % · Ziel ${s.total} %`;
    case 'FINAL': return s.state === 'OPEN' && s.progress === 0 ? 'Am Tag vor der Prüfung alles Fällige wiederholen' : `${s.done} von ${s.total} wiederholt`;
    default: return '';
  }
}

export const QUESTION_MODES = [
  ['FLIP', 'Umdrehen'], ['TYPE', 'Eintippen'], ['CHOICE', 'Multiple Choice'], ['CLOZE', 'Lückentext'], ['MATCH', 'Zuordnen'], ['MIX', 'Gemischt'],
];

export function verdictText(result, expected) {
  const parts = result.total > 1 ? `${result.matched} von ${result.total} genannt.` : '';
  switch (result.verdict) {
    case 'RIGHT': return { cls: 'ok', title: 'Richtig', text: '' };
    case 'TYPO': return { cls: 'ok', title: 'Richtig, kleiner Tippfehler', text: `So schreibt man es: ${expected}` };
    case 'PARTLY':
      if (result.hint === 'UNIT_MISSING') return { cls: 'part', title: 'Fast: die Einheit fehlt', text: '' };
      return { cls: 'part', title: 'Teilweise richtig', text: parts };
    case 'WRONG': {
      const why = { UNIT_WRONG: 'Die Einheit passt nicht.', NUMBER_WRONG: 'Die Zahl stimmt nicht.', NEGATION: 'Das „nicht“ dreht die Aussage um.', TOO_MANY_VALUES: 'Bitte nur einen Wert nennen.' }[result.hint] ?? '';
      return { cls: 'bad', title: 'Falsch', text: why };
    }
    default: return { cls: 'part', title: 'Bewerte dich selbst', text: 'Die Antwort ist zu frei für einen automatischen Vergleich.' };
  }
}

/** Which rating the checked answer suggests. */
export const suggestedRating = (verdict) => ({ RIGHT: 2, TYPO: 2, PARTLY: 1, WRONG: 0 }[verdict] ?? null);

export function sourceLine(card) {
  const s = card.source;
  if (!s || (!s.buch && !s.seite)) return null;
  return `Quelle: ${[s.buch, s.seite ? `S. ${s.seite}` : null].filter(Boolean).join(', ')}`;
}
