// A very small test runner for the browser, no library.
const tests = [];
let group = '';

export function suite(name) { group = name; }
export function test(name, fn) { tests.push({ group, name, fn }); }

function show(v) {
  try { return JSON.stringify(v); } catch { return String(v); }
}

export function eq(actual, expected, note = '') {
  const a = show(actual);
  const e = show(expected);
  if (a !== e) throw new Error(`${note ? `${note}: ` : ''}erwartet ${e}, bekommen ${a}`);
}

export function near(actual, expected, tol = 1e-9, note = '') {
  if (Math.abs(actual - expected) > tol) throw new Error(`${note ? `${note}: ` : ''}erwartet ${expected}, bekommen ${actual}`);
}

export function ok(value, note = 'nicht wahr') {
  if (!value) throw new Error(note);
}

export function no(value, note = 'nicht falsch') {
  if (value) throw new Error(note);
}

export function isNull(v, note = 'nicht null') {
  if (v !== null && v !== undefined) throw new Error(`${note}: ${show(v)}`);
}

export function notNull(v, note = 'ist null') {
  if (v === null || v === undefined) throw new Error(note);
  return v;
}

export async function run(root) {
  const results = [];
  for (const t of tests) {
    try {
      await t.fn();
      results.push({ ...t, passed: true });
    } catch (e) {
      results.push({ ...t, passed: false, error: e.message });
    }
  }
  const failed = results.filter((r) => !r.passed);
  const head = document.createElement('h1');
  head.textContent = failed.length === 0 ? `Alle ${results.length} Tests bestanden` : `${failed.length} von ${results.length} Tests fehlgeschlagen`;
  head.className = failed.length === 0 ? 'good' : 'bad';
  root.append(head);
  for (const r of failed) {
    const p = document.createElement('p');
    p.className = 'bad';
    p.textContent = `FEHLER ${r.group} / ${r.name}: ${r.error}`;
    root.append(p);
  }
  let last = null;
  for (const r of results) {
    if (r.group !== last) {
      const h = document.createElement('h2');
      h.textContent = r.group;
      root.append(h);
      last = r.group;
    }
    const line = document.createElement('div');
    line.textContent = `${r.passed ? 'ok' : 'FEHLER'}  ${r.name}`;
    line.className = r.passed ? 'line' : 'line bad';
    root.append(line);
  }
  document.title = `${failed.length === 0 ? 'OK' : 'FEHLER'} ${results.length - failed.length}/${results.length}`;
  window.testResults = { total: results.length, failed: failed.map((f) => `${f.group} / ${f.name}: ${f.error}`) };
  return results;
}
