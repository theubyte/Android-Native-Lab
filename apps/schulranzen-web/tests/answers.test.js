// The test values of AnswerCheckTest.kt and Phase9Test.kt (branch agent/lernen).
import { suite, test, eq, near, ok, no, isNull, notNull } from './harness.js';
import { check, canCheck, parseNumber, gradeOf, normalize } from '../js/core/answercheck.js';
import * as Q from '../js/core/questions.js';
import * as Csv from '../js/core/cardcsv.js';
import * as Sug from '../js/core/cardsuggester.js';
import * as Mock from '../js/core/mockexam.js';
import { buildPath, cardTarget, MIN_CARDS } from '../js/core/examplan.js';
import { newSchedule } from '../js/core/repetition.js';
import { seeded } from '../js/core/text.js';

const v = (given, expected) => check(given, expected).verdict;

suite('AnswerCheckTest');

test('exact and case differences are right', () => {
  eq(v('Kupfer', 'Kupfer'), 'RIGHT');
  eq(v('kupfer ', 'Kupfer'), 'RIGHT');
});

test('umlaut spellings and sharp s are the same', () => {
  eq(v('Waermetauscher', 'Wärmetauscher'), 'RIGHT');
  eq(v('Massstab', 'Maßstab'), 'RIGHT');
  eq(v('Wärmetauscher', 'Waermetauscher'), 'RIGHT');
});

test('articles and fillers do not matter', () => {
  eq(v('der Mischer', 'Mischer'), 'RIGHT');
  eq(v('Mischer', 'ein Mischer'), 'RIGHT');
});

test('typing slips count as typo on long words only', () => {
  eq(v('Wärmetauschr', 'Wärmetauscher'), 'TYPO');
  eq(v('Ausdehnugnsgefäß', 'Ausdehnungsgefäß'), 'TYPO');
  eq(v('Ohr', 'Rohr'), 'WRONG');
  eq(v('kalt', 'warm'), 'WRONG');
  eq(v('Rücklauf', 'Vorlauf'), 'WRONG');
});

test('typo counts as right in the grade', () => {
  eq(gradeOf('TYPO'), 'RIGHT');
  eq(gradeOf('PARTLY'), 'PARTLY');
});

test('numbers are never fuzzy', () => {
  eq(v('80 °C', '60 °C'), 'WRONG');
  eq(v('61', '60'), 'WRONG');
  eq(v('1,6 bar', '1,5 bar'), 'WRONG');
});

test('comma and dot in numbers', () => {
  eq(v('1.5 bar', '1,5 bar'), 'RIGHT');
  eq(v('1,5 bar', '1.5 bar'), 'RIGHT');
  eq(v('1500', '1.500'), 'RIGHT');
  eq(parseNumber('1,5'), 1.5);
  eq(parseNumber('1.500'), 1500);
  eq(parseNumber('1.500,5'), 1500.5);
  eq(parseNumber('1,500'), 1.5);
  eq(parseNumber('1.5'), 1.5);
  isNull(parseNumber('abc'));
});

test('units convert and are required', () => {
  eq(v('150 kPa', '1,5 bar'), 'RIGHT');
  eq(v('60°C', '60 °C'), 'RIGHT');
  eq(v('60 Grad', '60 °C'), 'RIGHT');
  eq(v('0,5 m', '50 cm'), 'RIGHT');
  eq(v('2 h', '120 min'), 'RIGHT');
  const missing = check('60', '60 °C');
  eq(missing.verdict, 'PARTLY');
  eq(missing.hint, 'UNIT_MISSING');
  const wrongUnit = check('60 bar', '60 °C');
  eq(wrongUnit.verdict, 'WRONG');
  eq(wrongUnit.hint, 'UNIT_WRONG');
  eq(v('1,5 kPa', '1,5 bar'), 'WRONG');
});

test('compound units are compared as written', () => {
  eq(v('2,5 m³/h', '2,5 m³/h'), 'RIGHT');
  eq(v('0,5 L/min', '0,5 l/min'), 'RIGHT');
  eq(v('0,5 l/s', '0,5 l/min'), 'WRONG');
});

test('hedging with two values is not an answer', () => {
  const r = check('60 oder 70 °C', '60 °C');
  eq(r.verdict, 'WRONG');
  eq(r.hint, 'TOO_MANY_VALUES');
});

test('bracket remarks on the card are optional', () => {
  eq(v('60 °C', '60 °C (Legionellenschutz)'), 'RIGHT');
});

test('value with word around it', () => {
  eq(v('60 °C', 'mindestens 60 °C'), 'RIGHT');
  eq(v('DN 20', 'DN 20'), 'RIGHT');
  eq(v('DN 25', 'DN 20'), 'WRONG');
});

test('negation changes the meaning', () => {
  const r = check('nicht zulässig', 'zulässig');
  eq(r.verdict, 'WRONG');
  eq(r.hint, 'NEGATION');
  eq(v('zulässig', 'nicht zulässig'), 'WRONG');
  eq(v('nicht zulässig', 'nicht zulässig'), 'RIGHT');
});

test('alternatives on the card each suffice', () => {
  eq(v('Stahl', 'Kupfer oder Stahl'), 'RIGHT');
  eq(v('Kupfer', 'Kupfer / Stahl'), 'RIGHT');
  eq(v('Messing', 'Kupfer oder Stahl'), 'WRONG');
});

test('lists need every item in any order', () => {
  const back = '• Vorlauf\n• Rücklauf\n• Pumpe';
  eq(v('Pumpe, Vorlauf und Rücklauf', back), 'RIGHT');
  const part = check('Pumpe, Vorlauf', back);
  eq(part.verdict, 'PARTLY');
  eq(part.matched, 2);
  eq(part.total, 3);
  eq(v('Kessel', back), 'WRONG');
});

test('phrases can be partly right and compounds joined', () => {
  eq(v('Membran Ausdehnungsgefäß', 'Membranausdehnungsgefäß'), 'RIGHT');
  eq(v('Vorlauf Rücklauf', 'Vorlauf Rücklauf Pumpe'), 'PARTLY');
  eq(v('Vorlauf', 'Vorlauf Rücklauf Pumpe'), 'WRONG');
});

test('long explanations cannot be checked by machine', () => {
  const long = 'Das Ausdehnungsgefäß nimmt die Volumenänderung des Heizwassers beim Erwärmen auf und hält den Druck in der Anlage in dem zulässigen Bereich';
  no(canCheck(long));
  eq(v('irgendwas', long), 'UNCHECKABLE');
  no(canCheck('  '));
});

test('empty answer is wrong', () => {
  eq(v('', 'Kupfer'), 'WRONG');
});

suite('QuestionMakerTest');

const card = (id, front, back, hasImage = false) => ({ id, front, back, hasImage });
const pool = [
  card(1, 'Temperatur A?', '60 °C'), card(2, 'Temperatur B?', '55 °C'), card(3, 'Temperatur C?', '70 °C'),
  card(4, 'Temperatur D?', '20 °C'), card(5, 'Werkstoff?', 'Kupfer'), card(6, 'Bauteil?', 'Mischer'), card(7, 'Bauteil 2?', 'Pumpe'),
];

test('choice holds the right answer once and alike distractors', () => {
  for (let seed = 0; seed < 20; seed++) {
    const q = notNull(Q.choice(pool[0], pool, seeded(seed)));
    eq(q.options.length, 4);
    eq(q.options[q.correct], '60 °C');
    eq(new Set(q.options).size, 4);
    ok(q.options.every((o) => o.includes('°C')), `numbers compete with numbers: ${q.options}`);
  }
});

test('choice skips answers that would also be right', () => {
  const cards = [card(1, 'a', 'Wärmetauscher'), card(2, 'b', 'Waermetauscher'), card(3, 'c', 'Waermetauschr'), card(4, 'd', 'Pumpe'), card(5, 'e', 'Mischer')];
  const q = notNull(Q.choice(cards[0], cards, seeded(1)));
  eq(q.options.length, 3);
  no(q.options.includes('Waermetauscher'));
});

test('no choice without enough distractors or for pictures', () => {
  isNull(Q.choice(pool[0], pool.slice(0, 2), seeded(1)));
  isNull(Q.choice(card(9, 'x', 'y', true), pool, seeded(1)));
  isNull(Q.choice(card(9, 'x', 'z'.repeat(200)), pool, seeded(1)));
});

test('cloze cuts a value first', () => {
  const c = notNull(Q.cloze('Der Speicher muss mindestens 60 °C halten.'));
  eq(c.answer, '60 °C');
  eq(c.text, 'Der Speicher muss mindestens _____ halten.');
  eq(notNull(Q.cloze('Im Heizkreis liegt der Druck bei 1,5 bar im Betrieb.')).answer, '1,5 bar');
});

test('cloze takes a noun and avoids the question words', () => {
  const c = notNull(Q.cloze('Das Ausdehnungsgefäß nimmt die Volumenänderung des Heizwassers auf.', 'Was macht das Ausdehnungsgefäß?'));
  no(c.answer.includes('Ausdehnungsgefäß'));
  ok(c.text.includes(Q.BLANK));
  no(c.text.includes(c.answer));
});

test('marked cloze wins and other marks are filled in', () => {
  const c = notNull(Q.cloze('{{c1::Kupfer}} und {{c2::Stahl}} sind Werkstoffe.'));
  eq(c.answer, 'Kupfer');
  eq(c.text, '_____ und Stahl sind Werkstoffe.');
});

test('too short text has no cloze', () => {
  isNull(Q.cloze('Kupfer'));
  isNull(Q.clozeQuestion(card(1, '?', 'Kupfer')));
});

test('value before a word with umlaut stays whole', () => {
  // JS \b is ASCII only; the pattern must not stop inside "Stück".
  const c = notNull(Q.cloze('Im Lager liegen immer 3 Stück bereit.'));
  eq(c.answer, '3');
});

test('exam mix asks each card once and mixes kinds', () => {
  const cards = [];
  for (let i = 1; i <= 16; i++) cards.push(card(i, `Frage ${i}?`, `Antwort Nummer ${i}`));
  const long = [...cards, card(99, 'Erkläre', 'Das ist eine sehr lange Erklärung mit vielen Worten, die keine Maschine fair vergleichen kann, weil sie frei formuliert ist und viel erklärt.')];
  const qs = Q.examMix(long, long, seeded(3));
  const asked = qs.flatMap((q) => q.cards.map((c) => c.id));
  eq(asked.length, long.length);
  eq(new Set(asked).size, long.length);
  ok(new Set(qs.map((q) => q.type)).size >= 3, `kinds: ${qs.map((q) => q.type)}`);
  eq(qs.filter((q) => q.type === 'MATCH').length, 2);
});

test('pictures stay flip questions', () => {
  const img = card(1, 'Was ist das?', 'Mischer', true);
  eq(Q.examMix([img], pool, seeded(1))[0].type, 'FLIP');
  eq(Q.question('TYPE', img, pool, seeded(1)).type, 'FLIP');
});

test('falling back from choice keeps the card', () => {
  eq(Q.question('CHOICE', card(1, 'a', 'b'), [], seeded(1)).type, 'TYPE');
});

test('match round tracks first tries and shared answers', () => {
  const cs = [card(1, 'A', 'x'), card(2, 'B', 'y'), card(3, 'C', 'y')];
  const q = notNull(Q.match(cs, seeded(1)));
  eq([...new Set(q.rightOrder)].sort(), [0, 1, 2]);
  const r = new Q.MatchRound(cs);
  no(r.pick(1, 2));
  ok(r.pick(1, 1));
  ok(r.pick(2, 3), 'two cards with the same answer accept either');
  ok(r.pick(3, 2));
  ok(r.done);
  no(r.firstTry(1));
  ok(r.firstTry(2));
  eq([...r.missed], [1]);
});

test('match needs three usable cards', () => {
  isNull(Q.match(pool.slice(0, 2), seeded(1)));
  isNull(Q.match([card(1, 'a', 'x'), card(2, 'b', 'y'), card(3, 'c', 'z'.repeat(100))], seeded(1)));
});

suite('CardCsvTest');

const plain = (cards) => cards.map((c) => ({ front: c.front, back: c.back }));

test('plain tab separated file', () => {
  const p = Csv.parse('Frage 1\tAntwort 1\nFrage 2\tAntwort 2\n');
  eq(p.delimiter, '\t');
  eq(plain(p.cards), [{ front: 'Frage 1', back: 'Antwort 1' }, { front: 'Frage 2', back: 'Antwort 2' }]);
  eq(p.skipped, 0);
});

test('anki headers quotes and html', () => {
  const p = Csv.parse('#separator:tab\n#html:true\n#tags column:3\n"Zeile <b>eins</b>"\t"A<br>B &amp; C"\tlf3\n"mehr\nzeilig"\t"mit ""Zitat"""\t\n');
  eq(p.cards[0].front, 'Zeile eins');
  eq(p.cards[0].back, 'A\nB & C');
  eq(p.cards[0].tags, 'lf3');
  eq(p.cards[1].front, 'mehr\nzeilig');
  eq(p.cards[1].back, 'mit "Zitat"');
});

test('columns can be swapped by header', () => {
  eq(plain(Csv.parse('#separator:semicolon\n#columns:Back;Front\nAntwort;Frage\n').cards), [{ front: 'Frage', back: 'Antwort' }]);
});

test('deck column is read', () => {
  eq(Csv.parse('#separator:tab\n#deck column:3\nF\tA\tLF 3\n').cards[0].deck, 'LF 3');
});

test('semicolon and comma are detected and bom and crlf removed', () => {
  eq(Csv.parse('﻿a;b\r\nc;d\r\n').delimiter, ';');
  eq(Csv.parse('﻿a;b\r\nc;d\r\n').cards[0].front, 'a');
  eq(Csv.parse('a,b\nc,d\n').delimiter, ',');
});

test('broken lines are counted not fatal', () => {
  const p = Csv.parse('nur eine Spalte\nF\tA\n\t\nkein\tProblem\n');
  eq(p.cards.length, 2);
  eq(p.skipped, 2);
});

test('media references are dropped and reported', () => {
  const p = Csv.parse('Bild <img src="a.png">\tTon [sound:b.mp3] ok\n');
  eq(p.cards[0].front, 'Bild');
  eq(p.cards[0].back, 'Ton ok');
  eq(p.mediaDropped, 1);
});

test('title row is recognised', () => {
  eq(plain(Csv.dropTitleRow(Csv.parse('Frage;Antwort\na;b\n').cards)), [{ front: 'a', back: 'b' }]);
});

test('what is written reads back unchanged', () => {
  const cards = [{ front: 'Zeile\nzwei', back: 'mit "Zitat"\tund Tab' }, { front: '60 °C', back: '• A\n• B' }, { front: 'a;b', back: 'c,d' }];
  eq(plain(Csv.dropTitleRow(Csv.parse(Csv.write(cards)).cards)), cards);
  eq(plain(Csv.dropTitleRow(Csv.parse(Csv.writeCsv(cards)).cards)), cards);
});

test('empty text is empty', () => {
  eq(Csv.parse('').cards.length, 0);
  eq(Csv.parse('#separator:tab\n').cards.length, 0);
});

suite('TextQuestionsTest');

test('questions come from the text only', () => {
  const text = 'Ein MAG ist ein Membran-Ausdehnungsgefäß im Heizkreis.\nDas Warmwasser im Speicher muss mindestens 60 °C heiß sein.\nDas war gestern schön.';
  const qs = Sug.fromText(text);
  ok(qs.some((s) => s.kind === 'DEFINITION' && s.front.includes('MAG')));
  const gap = qs.find((s) => s.kind === 'GAP' && s.back === '60 °C');
  ok(gap.front.startsWith('Ergänze:'));
  no(gap.front.includes('60 °C'));
  no(qs.some((s) => s.front.includes('gestern')));
});

test('nothing from nothing', () => eq(Sug.fromText(' \n ').length, 0));

suite('CardSuggesterTest');

test('definition with ist', () => {
  const s = Sug.suggest('Das Ausdehnungsgefäß ist ein Behälter, der die Volumenänderung des Heizwassers aufnimmt.');
  eq(s.length, 1);
  eq(s[0].front, 'Was ist das Ausdehnungsgefäß?');
  ok(s[0].back.startsWith('Ein Behälter'));
  eq(s[0].kind, 'DEFINITION');
});

test('understand and pronoun sentences are handled', () => {
  const s = Sug.suggest('Unter Legionellen versteht man Bakterien im warmen Trinkwasser. Es ist wichtig, das zu wissen.');
  eq(s.length, 1);
  eq(s[0].front, 'Was versteht man unter Legionellen?');
  eq(s[0].back, 'Bakterien im warmen Trinkwasser.');
});

test('abbreviations do not split sentences', () => {
  const parts = Sug.sentences('Ein Rohr ist z. B. aus Kupfer. Die Pumpe fördert ca. 2 m³/h. Fertig.');
  eq(parts.length, 3);
  eq(parts[0], 'Ein Rohr ist z. B. aus Kupfer.');
});

test('term lines', () => {
  const s = Sug.suggest('Mindesttemperatur Warmwasser: 60 °C am Speicheraustritt\nBeispiel: so nicht');
  eq(s.length, 1);
  eq(s[0].front, 'Mindesttemperatur Warmwasser');
  eq(s[0].back, '60 °C am Speicheraustritt');
});

test('lists under a heading', () => {
  const s = Sug.suggest('Bauteile einer Heizungsanlage:\n- Wärmeerzeuger\n- Pumpe\n- MAG: nimmt Ausdehnung auf\n\nEnde');
  const list = s.find((x) => x.kind === 'LIST');
  eq(list.front, 'Bauteile einer Heizungsanlage – nenne die 3 Punkte.');
  ok(list.back.includes('• Pumpe'));
  ok(s.some((x) => x.front === 'MAG'));
});

test('duplicates and noise are dropped', () => {
  eq(Sug.suggest('Das ist gut.\nDas ist gut.\nPumpe: fördert Wasser\nPumpe: fördert Wasser').map((x) => x.front), ['Pumpe']);
});

test('empty text gives nothing', () => eq(Sug.suggest('  \n ').length, 0));

suite('MockExamTest');

const sched = ({ lapses = 0, ease = 2.5, seen = true } = {}) => newSchedule({ reps: seen ? 3 : 0, intervalDays: 5, ease, lapses, dueEpochDay: 0, lastReviewEpochDay: seen ? 0 : null });

test('grades follow the IHK key', () => {
  eq(Mock.grade(100), 1);
  eq(Mock.grade(92), 1);
  eq(Mock.grade(91.9), 2);
  eq(Mock.grade(67), 3);
  eq(Mock.grade(50), 4);
  eq(Mock.grade(30), 5);
  eq(Mock.grade(29.9), 6);
});

test('pick takes distinct cards up to count', () => {
  const cards = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
  const p = Mock.pick(cards, 5, () => sched(), seeded(1));
  eq(p.length, 5);
  eq(new Set(p).size, 5);
  eq(Mock.pick(cards, 50, () => sched(), seeded(1)).length, 10);
  eq(Mock.pick(cards, 0, () => sched()).length, 0);
});

test('weak cards are drawn more often', () => {
  const weak = sched({ lapses: 4, ease: 1.3 });
  const strong = sched();
  ok(Mock.weight(weak) > Mock.weight(strong) * 3);
  let weakFirst = 0;
  const r = seeded(7);
  for (let i = 0; i < 500; i++) if (Mock.pick(['w', 's'], 1, (x) => (x === 'w' ? weak : strong), r)[0] === 'w') weakFirst++;
  ok(weakFirst > 350, `weak drawn ${weakFirst}/500`);
});

test('evaluation scores and weak topics', () => {
  const res = Mock.evaluate([
    { topic: 'Trinkwasser', grade: 'RIGHT' }, { topic: 'Trinkwasser', grade: 'RIGHT' },
    { topic: 'Heizung', grade: 'WRONG' }, { topic: 'Heizung', grade: 'PARTLY' },
  ]);
  eq(res.asked, 4);
  near(res.points, 2.5);
  near(res.percent, 62.5);
  eq(res.grade, 4);
  eq(res.weakTopics.map((t) => t.topic), ['Heizung']);
  near(res.weakTopics[0].percent, 25.0);
});

test('suggested minutes', () => {
  eq(Mock.suggestedMinutes(2), 5);
  eq(Mock.suggestedMinutes(10), 15);
  eq(Mock.suggestedMinutes(20), 30);
});

test('topic matcher uses stems', () => {
  const topics = ['Trinkwasserinstallation', 'Heizungsventile und Pumpen', 'Abwasser'];
  eq(Mock.topicOf('Wozu dient die Pumpe im Heizkreis?', topics, 'Stapel'), 'Heizungsventile und Pumpen');
  eq(Mock.topicOf('Was ist ein Lötfitting?', topics, 'Stapel'), 'Stapel');
  eq(Mock.topicOf('egal', [], 'Stapel'), 'Stapel');
});

suite('LearningPathTest');

const input = ({ links = 0, linksDone = 0, topics = 0, cards = 0, seen = 0, solid = 0, due = 0, mock = null, days = 10 } = {}) => ({ linksTotal: links, linksDone, topics, cards, cardsSeen: seen, cardsSolid: solid, cardsDue: due, mockBestPercent: mock, daysLeft: days });

test('fresh exam starts with material', () => {
  const p = buildPath(input({ links: 4 }));
  eq(p.next.kind, 'MATERIAL');
  eq(p.stages[0].state, 'ACTIVE');
  ok(p.stages.slice(1).every((s) => s.state === 'OPEN'));
  near(p.progress, 0, 1e-6);
});

test('progresses through stages', () => {
  const afterMaterial = buildPath(input({ links: 2, linksDone: 2, cards: 4 }));
  eq(afterMaterial.next.kind, 'CARDS');
  eq(afterMaterial.stages[1].done, 4);
  eq(afterMaterial.stages[1].total, MIN_CARDS);
  eq(buildPath(input({ cards: 12, seen: 12, solid: 3 })).next.kind, 'LEARN');
  eq(buildPath(input({ cards: 12, seen: 12, solid: 11, mock: 40.0 })).next.kind, 'MOCK');
  const waiting = buildPath(input({ cards: 12, seen: 12, solid: 11, mock: 80.0, days: 5 }));
  eq(waiting.next.kind, 'FINAL');
  eq(waiting.next.state, 'OPEN');
  const eve = buildPath(input({ cards: 12, seen: 12, solid: 11, mock: 80.0, days: 1, due: 0 }));
  ok(eve.stages.every((s) => s.state === 'DONE'));
  near(eve.progress, 1, 1e-6);
});

test('past exam has no next step', () => isNull(buildPath(input({ days: -1 })).next));

test('card target scales with topics', () => {
  eq(cardTarget(1, 0), 10);
  eq(cardTarget(6, 2), 18);
  eq(cardTarget(30, 0), 40);
});

suite('Web-App: Normalisierung');

test('normalize keeps digits and spells umlauts out', () => eq(normalize('Größe: 60 °C!'), 'groesse 60 c'));
