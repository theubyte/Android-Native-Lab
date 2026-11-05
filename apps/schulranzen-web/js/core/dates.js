// Calendar days as plain integers (days since 1970-01-01), the same "epoch day"
// the Android app stores. A review never shifts with time zones or daylight saving.

const MS_DAY = 86400000;

export function epochDayOf(y, m, d) {
  return Math.floor(Date.UTC(y, m - 1, d) / MS_DAY);
}

/** Local calendar day of a Date. */
export function toEpochDay(date = new Date()) {
  return epochDayOf(date.getFullYear(), date.getMonth() + 1, date.getDate());
}

export function fromEpochDay(day) {
  const d = new Date(day * MS_DAY);
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate(), weekday: d.getUTCDay() };
}

/** "2026-11-05" for date inputs and files. */
export function isoOf(day) {
  const p = fromEpochDay(day);
  return `${String(p.year).padStart(4, '0')}-${String(p.month).padStart(2, '0')}-${String(p.day).padStart(2, '0')}`;
}

export function parseIso(text) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(text || '').trim());
  if (!m) return null;
  const day = epochDayOf(+m[1], +m[2], +m[3]);
  const p = fromEpochDay(day);
  return p.month === +m[2] && p.day === +m[3] ? day : null;
}

const WEEKDAYS = ['Sonntag', 'Montag', 'Dienstag', 'Mittwoch', 'Donnerstag', 'Freitag', 'Samstag'];
const SHORT = ['So', 'Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa'];

export const weekday = (day) => WEEKDAYS[fromEpochDay(day).weekday];
export const weekdayShort = (day) => SHORT[fromEpochDay(day).weekday];

export function dateLong(day) {
  const p = fromEpochDay(day);
  return `${String(p.day).padStart(2, '0')}.${String(p.month).padStart(2, '0')}.${p.year}`;
}

export function dateShort(day) {
  const p = fromEpochDay(day);
  return `${String(p.day).padStart(2, '0')}.${String(p.month).padStart(2, '0')}.`;
}

/** "heute", "morgen", "in 5 Tagen", "vor 2 Tagen". */
export function countdown(today, day) {
  const n = day - today;
  if (n === 0) return 'heute';
  if (n === 1) return 'morgen';
  if (n === -1) return 'gestern';
  return n > 0 ? `in ${n} Tagen` : `vor ${-n} Tagen`;
}
