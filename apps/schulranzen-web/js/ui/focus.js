// Fokus-Timer. The state is only "when did it start, how long was it paused", kept in the
// database; a sleeping phone or a reload cannot make it wrong.
import { h, fill, btn, plates, toast, announce } from './dom.js';
import { app } from '../app.js';
import { DEFAULT_FOCUS, focusAt } from '../core/progress.js';
import { logFocus } from '../data/repo.js';

const PRESETS = [
  ['25', '25 / 5', { focus: 25, shortBreak: 5, longBreak: 15, blocksUntilLong: 4 }],
  ['45', '45 / 10', { focus: 45, shortBreak: 10, longBreak: 20, blocksUntilLong: 3 }],
  ['15', '15 / 3', { focus: 15, shortBreak: 3, longBreak: 10, blocksUntilLong: 4 }],
];

const pad = (n) => String(n).padStart(2, '0');
const fmt = (s) => `${pad(Math.floor(s / 60))}:${pad(s % 60)}`;

function elapsedOf(run, now = Date.now()) {
  const paused = run.pausedTotal + (run.pausedAt ? now - run.pausedAt : 0);
  return Math.max(0, (now - run.startedAt - paused) / 1000);
}

function chime() {
  try {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    const ctx = new Ctx();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.frequency.value = 880;
    gain.gain.setValueAtTime(0.0001, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.25, ctx.currentTime + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.6);
    osc.connect(gain).connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.65);
    osc.onended = () => ctx.close();
  } catch { /* silence is fine */ }
  navigator.vibrate?.(200);
}

export async function renderFocus(root) {
  let run = app.setting('focusRun');
  let presetId = app.setting('focusPreset', '25');
  const presetOf = (id) => PRESETS.find((p) => p[0] === id)?.[2] ?? DEFAULT_FOCUS;
  let tickId = null;
  let wake = null;
  let lastPhase = null;

  const R = 90;
  const C = 2 * Math.PI * R;
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 200 200');
  svg.setAttribute('aria-hidden', 'true');
  const mk = (cls) => {
    const c = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
    c.setAttribute('cx', '100'); c.setAttribute('cy', '100'); c.setAttribute('r', String(R)); c.setAttribute('class', cls);
    return c;
  };
  const fg = mk('ring-fg');
  svg.append(mk('ring-bg'), fg);
  const timeEl = h('b', { role: 'timer', 'aria-label': 'Restzeit' }, '');
  const phaseEl = h('span.label.muted');
  const blockEl = h('span.mono.muted');
  const controls = h('div.row', { css: { justifyContent: 'center' } });
  const ringBox = h('div.focus-ring', {}, svg, h('div.focus-time', {}, phaseEl, timeEl, blockEl));
  const presetBox = h('div.field', { css: { alignItems: 'center' } });

  async function requestWake() {
    try { wake = await navigator.wakeLock?.request('screen'); } catch { wake = null; }
  }
  function releaseWake() { wake?.release?.().catch(() => {}); wake = null; }

  async function start() {
    run = { startedAt: Date.now(), pausedAt: null, pausedTotal: 0, plan: presetOf(presetId) };
    await app.set('focusRun', run);
    requestWake();
    draw();
  }
  async function togglePause() {
    if (run.pausedAt) {
      run.pausedTotal += Date.now() - run.pausedAt;
      run.pausedAt = null;
      requestWake();
    } else {
      run.pausedAt = Date.now();
      releaseWake();
    }
    await app.set('focusRun', run);
    draw();
  }
  async function finish() {
    const minutes = Math.floor(focusAt(run.plan, elapsedOf(run)).focusSeconds / 60);
    await logFocus(minutes);
    await app.set('focusRun', null);
    run = null;
    releaseWake();
    toast(minutes > 0 ? `${minutes} Minuten Fokus gespeichert.` : 'Unter einer Minute: nicht gezählt.');
    lastPhase = null;
    draw();
  }

  function draw() {
    if (!run) {
      const p = presetOf(presetId);
      timeEl.textContent = fmt(p.focus * 60);
      phaseEl.textContent = 'Bereit';
      blockEl.textContent = '';
      fg.setAttribute('stroke-dasharray', `${C} ${C}`);
      ringBox.style.setProperty('--c', 'var(--ink-faint)');
      fill(presetBox, h('div.field-label', {}, 'Rhythmus: Fokus und Pause in Minuten'),
        plates('Rhythmus', PRESETS.map((x) => [x[0], x[1]]), presetId, async (v) => { presetId = v; await app.set('focusPreset', v); draw(); }));
      fill(controls, btn('Starten', start, { kind: 'signal', icon: 'play', big: true }));
      return;
    }
    fill(presetBox);
    fill(controls, 
      btn(run.pausedAt ? 'Weiter' : 'Pause', togglePause, { icon: run.pausedAt ? 'play' : 'minus' }),
      btn('Beenden', finish, { kind: 'quiet', icon: 'check' }),
    );
    tick();
  }

  function tick() {
    if (!run) return;
    const s = focusAt(run.plan, elapsedOf(run));
    timeEl.textContent = fmt(s.secondsLeft);
    phaseEl.textContent = run.pausedAt ? 'Angehalten' : s.inBreak ? (s.longBreak ? 'Lange Pause' : 'Pause') : 'Fokus';
    blockEl.textContent = `Block ${s.block}`;
    const done = 1 - s.secondsLeft / s.phaseSeconds;
    fg.setAttribute('stroke-dasharray', `${C * (1 - done)} ${C}`);
    ringBox.style.setProperty('--c', s.inBreak ? 'var(--subject-3)' : 'var(--signal)');
    const key = `${s.block}-${s.inBreak}`;
    if (lastPhase !== null && lastPhase !== key) {
      chime();
      announce(s.inBreak ? 'Zeit für eine Pause.' : 'Pause vorbei. Weiter gehts.');
    }
    lastPhase = key;
  }

  root.append(h('div.stack.stack-lg', {},
    h('h1.title', {}, 'Fokus-Timer'),
    h('div.focus-face', {}, ringBox, presetBox, controls,
      h('p.caption.muted', { css: { textAlign: 'center', maxWidth: '420px' } }, 'Läuft weiter, auch wenn du die App verlässt. Ton und Vibration kommen nur, solange sie offen ist.'))));
  draw();
  if (run && !run.pausedAt) requestWake();
  tickId = setInterval(tick, 500);
  return () => { clearInterval(tickId); releaseWake(); };
}
