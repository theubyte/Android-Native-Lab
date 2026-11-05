// DOM helpers. All text goes in as text nodes, never as HTML, so nothing from a card or a
// pack can become markup. Colors and sizes are set through the style object because the
// Content Security Policy forbids inline style attributes.

import { isIos } from '../platform.js';

const ICON_PATHS = {
  plus: '<path d="M12 5v14M5 12h14"/>',
  play: '<path d="M7 4.5v15l12.5-7.5z"/>',
  flip: '<path d="M4 12a8 8 0 0 1 13.7-5.6L20 9M20 4v5h-5M20 12a8 8 0 0 1-13.7 5.6L4 15M4 20v-5h5"/>',
  moon: '<path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"/>',
  back: '<path d="M15 5l-7 7 7 7"/>',
  next: '<path d="M9 5l7 7-7 7"/>',
  home: '<path d="M4 11l8-7 8 7M6 9.5V20h12V9.5"/>',
  cards: '<rect x="3.5" y="7" width="13" height="13" rx="2"/><path d="M8 7V5.5a1.5 1.5 0 0 1 1.5-1.5H19a1.5 1.5 0 0 1 1.5 1.5V15a1.5 1.5 0 0 1-1.5 1.5h-2.5"/>',
  calendar: '<rect x="3.5" y="5" width="17" height="15.5" rx="2.5"/><path d="M3.5 10h17M8 2.8V7M16 2.8V7"/>',
  timer: '<circle cx="12" cy="13.5" r="7.5"/><path d="M12 9v4.5l3 2M9.5 2.8h5"/>',
  chart: '<path d="M4 20h16M7 16.5v-5M12 16.5V6M17 16.5v-8"/>',
  more: '<circle cx="5.5" cy="12" r="1.3"/><circle cx="12" cy="12" r="1.3"/><circle cx="18.5" cy="12" r="1.3"/>',
  warn: '<path d="M12 3.5l9.5 17h-19zM12 10v4.5M12 17.6v.1"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  cross: '<path d="M6 6l12 12M18 6L6 18"/>',
  minus: '<path d="M5 12h14"/>',
  edit: '<path d="M4 20h4L19.5 8.5a2.1 2.1 0 0 0-3-3L5 17zM14.5 7.5l3 3"/>',
  trash: '<path d="M4.5 7h15M9.5 7V4.5h5V7M6.5 7l1 13h9l1-13M10 11v5.5M14 11v5.5"/>',
  download: '<path d="M12 4v11M7.5 11l4.5 4.5L16.5 11M4.5 20h15"/>',
  upload: '<path d="M12 16V5M7.5 9L12 4.5 16.5 9M4.5 20h15"/>',
  image: '<rect x="3.5" y="5" width="17" height="14" rx="2.5"/><circle cx="9" cy="10" r="1.6"/><path d="M4 17l5-4.5 3.5 3 3-2.5 4.5 4"/>',
  undo: '<path d="M8.5 5L4 9.5 8.5 14M4.5 9.5H14a5.5 5.5 0 0 1 0 11h-3"/>',
  book: '<path d="M6 3.5h12.5v17H6a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2zM8.5 3.5v17"/>',
  error: '<path d="M6 3.5h12a1.5 1.5 0 0 1 1.5 1.5v14a1.5 1.5 0 0 1-1.5 1.5H6A1.5 1.5 0 0 1 4.5 19V5A1.5 1.5 0 0 1 6 3.5zM9 8.5l6 6M15 8.5l-6 6"/>',
  shield: '<path d="M12 3l8 3v6c0 4.5-3.2 7.5-8 9-4.8-1.5-8-4.5-8-9V6zM8.5 12l2.5 2.5 4.5-5"/>',
  share: '<path d="M12 15V4M8 7.5L12 3.5l4 4M6 11H5v9.5h14V11h-1"/>',
  spark: '<path d="M12 3.5l1.9 5.6 5.6 1.9-5.6 1.9L12 18.5l-1.9-5.6L4.5 11l5.6-1.9z"/>',
  list: '<path d="M8.5 6.5H20M8.5 12H20M8.5 17.5H20M4.5 6.5v.1M4.5 12v.1M4.5 17.5v.1"/>',
};

export function icon(name) {
  const span = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  span.setAttribute('viewBox', '0 0 24 24');
  span.setAttribute('fill', name === 'play' || name === 'more' ? 'currentColor' : 'none');
  span.setAttribute('stroke', 'currentColor');
  span.setAttribute('stroke-width', '1.7');
  span.setAttribute('stroke-linecap', 'round');
  span.setAttribute('stroke-linejoin', 'round');
  span.setAttribute('aria-hidden', 'true');
  span.setAttribute('focusable', 'false');
  span.innerHTML = ICON_PATHS[name] ?? '';
  return span;
}

/** h('div.a.b', { onclick, aria-label… }, child…): children may be strings, nodes, arrays, null. */
export function h(spec, props, ...children) {
  const [tag, ...classes] = spec.split('.');
  const el = document.createElement(tag || 'div');
  if (classes.length) el.className = classes.join(' ');
  if (props !== null && props !== undefined && (typeof props !== 'object' || props instanceof Node || Array.isArray(props))) {
    children.unshift(props);
  } else if (props) {
    for (const [k, v] of Object.entries(props)) {
      if (v === null || v === undefined || v === false) continue;
      if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === 'class') el.className = `${el.className} ${v}`.trim();
      else if (k === 'value') el.value = v;
      else if (k === 'checked' || k === 'disabled' || k === 'selected' || k === 'readOnly') el[k] = !!v;
      else if (k === 'vars') for (const [n, val] of Object.entries(v)) el.style.setProperty(n, val);
      else if (k === 'css') Object.assign(el.style, v);
      else el.setAttribute(k, v === true ? '' : String(v));
    }
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue;
    if (Array.isArray(c)) append(el, c);
    else el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

export const clear = (el) => { while (el.firstChild) el.firstChild.remove(); };

/** Replaces the content; like h(), it skips null and flattens lists. */
export function fill(el, ...children) {
  clear(el);
  append(el, children);
}

export const subjectColor = (index) => `var(--subject-${((index % 8) + 8) % 8})`;

export const SUBJECT_NAMES = ['Petrol', 'Ziegel', 'Senf', 'Salbei', 'Taube', 'Pflaume', 'Ocker', 'Graphit'];

export function btn(label, onClick, opts = {}) {
  const cls = ['btn', opts.kind ? `btn-${opts.kind}` : null, opts.wide ? 'btn-wide' : null, opts.big ? 'btn-big' : null].filter(Boolean).join('.');
  return h(`button.${cls}`, { type: 'button', onclick: onClick, disabled: opts.disabled, 'aria-label': opts.aria }, opts.icon ? icon(opts.icon) : null, label);
}

export const linkBtn = (label, href, opts = {}) => {
  const cls = ['btn', opts.kind ? `btn-${opts.kind}` : null, opts.wide ? 'btn-wide' : null, opts.big ? 'btn-big' : null].filter(Boolean).join('.');
  return h(`a.${cls}`, { href }, opts.icon ? icon(opts.icon) : null, label);
};

export function key(iconName, label, onClick, opts = {}) {
  return h('button.key', { type: 'button', onclick: onClick, 'aria-label': label, title: label, 'aria-pressed': opts.pressed === undefined ? null : String(opts.pressed) }, icon(iconName));
}

export function sectionHead(title, count, level = 'h2') {
  return h('div.section-head', {}, h(`${level}.heading`, {}, title), count === undefined ? null : h('span.count', { 'aria-label': `${count} Einträge` }, String(count).padStart(2, '0')));
}

export function field(labelText, control, hint) {
  const id = `f${Math.random().toString(36).slice(2, 9)}`;
  control.id = id;
  return h('div.field', {}, h('label', { for: id }, labelText), control, hint ? h('p.caption.muted', {}, hint) : null);
}

export function checkbox(labelText, checked, onChange, hint) {
  const input = h('input', { type: 'checkbox', checked, onchange: (e) => onChange?.(e.target.checked) });
  return h('label.check', {}, input, h('span', {}, labelText, hint ? h('span.caption.muted', { css: { display: 'block' } }, hint) : null));
}

/** A row of plates, one chosen. Reads as a radio group. */
export function plates(label, options, value, onChange) {
  const group = h('div.seg', { role: 'radiogroup', 'aria-label': label });
  const buttons = options.map(([v, text]) => h('button.plate', {
    type: 'button', role: 'radio', 'aria-checked': String(v === value), tabindex: v === value ? '0' : '-1',
    onclick: () => choose(v),
  }, text));
  const choose = (v) => {
    buttons.forEach((b, i) => {
      const on = options[i][0] === v;
      b.setAttribute('aria-checked', String(on));
      b.setAttribute('tabindex', on ? '0' : '-1');
    });
    onChange(v);
  };
  group.addEventListener('keydown', (e) => {
    const i = options.findIndex((o, n) => buttons[n] === document.activeElement);
    if (i < 0) return;
    let n = null;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') n = (i + 1) % options.length;
    if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') n = (i - 1 + options.length) % options.length;
    if (n === null) return;
    e.preventDefault();
    e.stopPropagation();
    choose(options[n][0]);
    buttons[n].focus();
  });
  group.append(...buttons);
  return group;
}

export function colorPicker(value, onChange) {
  const group = h('div.swatches', { role: 'radiogroup', 'aria-label': 'Farbe' });
  const items = SUBJECT_NAMES.map((name, i) => h('button.swatch', {
    type: 'button', role: 'radio', 'aria-checked': String(i === value), 'aria-label': name, title: name, vars: { '--c': subjectColor(i) },
    onclick: () => {
      items.forEach((b, n) => b.setAttribute('aria-checked', String(n === i)));
      onChange(i);
    },
  }, h('span')));
  group.append(...items);
  return group;
}

export function bar(percent, color) {
  const p = Math.max(0, Math.min(100, percent));
  return h('div.bar', { role: 'presentation', vars: color ? { '--c': color } : {} }, h('span', { css: { width: `${p}%` } }));
}

export function ring(percent, topText, bottomText, color) {
  const r = 44;
  const c = 2 * Math.PI * r;
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 104 104');
  svg.setAttribute('aria-hidden', 'true');
  const circle = (cls, dash) => {
    const el = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
    el.setAttribute('cx', '52'); el.setAttribute('cy', '52'); el.setAttribute('r', String(r)); el.setAttribute('class', cls);
    if (dash !== undefined) { el.setAttribute('stroke-dasharray', `${dash} ${c}`); }
    return el;
  };
  svg.append(circle('ring-bg'));
  if (percent !== null) svg.append(circle('ring-fg', (c * Math.max(0, Math.min(100, percent))) / 100));
  return h('div.ring', { vars: color ? { '--c': color } : {} }, svg, h('div.ring-text', {}, h('b', {}, topText), bottomText ? h('span', {}, bottomText) : null));
}

// ---- Announcements and toasts ----------------------------------------------

export function announce(text) {
  const live = document.getElementById('live');
  live.textContent = '';
  // A change in a following task is what screen readers pick up.
  setTimeout(() => { live.textContent = text; }, 30);
}

let toastHost = null;

export function toast(text, opts = {}) {
  if (!toastHost) {
    toastHost = h('div.toasts');
    document.body.append(toastHost);
  }
  const close = () => t.remove();
  const t = h('div.toast', { role: 'status' }, h('span.grow', {}, text), opts.action ? h('button', {
    type: 'button',
    onclick: () => { close(); opts.action.run(); },
  }, opts.action.label) : null);
  toastHost.append(t);
  announce(text);
  setTimeout(close, opts.ms ?? (opts.action ? 8000 : 4000));
  return close;
}

// ---- Panels from the edge ----------------------------------------------------

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * A panel that glides in: traps focus, Escape closes, focus returns where it was.
 * build(close) returns the content. Resolves when closed, with what close() was given.
 */
export function openPanel(title, build, opts = {}) {
  return new Promise((resolve) => {
    const before = document.activeElement;
    const root = document.getElementById('overlay-root');
    const titleId = `t${Math.random().toString(36).slice(2, 8)}`;
    const app = document.getElementById('app');
    let closed = false;
    const close = (value) => {
      if (closed) return;
      closed = true;
      scrim.remove();
      document.removeEventListener('keydown', onKey, true);
      if (root.childElementCount === 0) {
        app.removeAttribute('aria-hidden');
        document.body.style.overflow = '';
      }
      if (before && before.isConnected) before.focus();
      resolve(value);
    };
    const onKey = (e) => {
      if (root.lastElementChild !== scrim) return;
      if (e.key === 'Escape') { e.stopPropagation(); e.preventDefault(); close(); return; }
      if (e.key !== 'Tab') { e.stopPropagation(); return; }
      const items = [...drawer.querySelectorAll(FOCUSABLE)].filter((x) => x.offsetParent !== null);
      if (items.length === 0) { e.preventDefault(); return; }
      const first = items[0];
      const last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      else if (!drawer.contains(document.activeElement)) { e.preventDefault(); first.focus(); }
    };
    const drawer = h('div.drawer', { role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': titleId, tabindex: '-1' },
      h('div.drawer-head', {}, h('h2.title.grow', { id: titleId }, title), key('cross', 'Schließen', () => close())),
      build(close));
    const scrim = h('div.scrim', {
      onmousedown: (e) => { if (e.target === scrim && !opts.modal) close(); },
    }, drawer);
    root.append(scrim);
    app.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = 'hidden';
    document.addEventListener('keydown', onKey, true);
    const auto = drawer.querySelector('[data-autofocus]') ?? drawer;
    auto.focus();
  });
}

/** A question with two answers. Resolves true or false. */
export function ask(title, text, yes, opts = {}) {
  return openPanel(title, (close) => h('div.stack', {},
    h('p.pre', {}, text),
    h('div.drawer-actions', {},
      btn(opts.no ?? 'Abbrechen', () => close(false), { kind: 'quiet' }),
      btn(yes, () => close(true), { kind: opts.danger ? null : 'signal', icon: opts.icon }),
    ))).then((v) => v === true);
}

// ---- Files --------------------------------------------------------------------

/**
 * Hands a file to the learner. An installed app on iOS cannot download through a link, so
 * there the share sheet ("In Dateien sichern") comes first. Resolves false on backing out.
 */
export async function saveFile(name, content, type = 'application/json') {
  const blob = content instanceof Blob ? content : new Blob([content], { type });
  if (isIos() && navigator.canShare) {
    const file = new File([blob], name, { type });
    if (navigator.canShare({ files: [file] })) {
      try {
        await navigator.share({ files: [file] });
        return true;
      } catch (e) {
        if (e.name === 'AbortError') return false;
        // Anything else: the link below still works in the browser.
      }
    }
  }
  const url = URL.createObjectURL(blob);
  const a = h('a', { href: url, download: name, css: { display: 'none' } });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
  return true;
}

/** Opens the file picker; resolves with the file, or null if the learner backed out. */
export function pickFile(accept) {
  return new Promise((resolve) => {
    const input = h('input', { type: 'file', accept, css: { display: 'none' } });
    let done = false;
    const finish = (f) => {
      if (done) return;
      done = true;
      input.remove();
      resolve(f);
    };
    input.addEventListener('change', () => finish(input.files?.[0] ?? null));
    input.addEventListener('cancel', () => finish(null));
    document.body.append(input);
    input.click();
  });
}

export function readText(file) {
  return file.text ? file.text() : new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(r.result);
    r.onerror = () => reject(r.error);
    r.readAsText(file);
  });
}

/** Scales a picture down so a phone's storage is not eaten by 12 megapixel photos. */
export async function shrinkImage(file, maxSide = 1600) {
  const url = URL.createObjectURL(file);
  try {
    const img = await new Promise((resolve, reject) => {
      const i = new Image();
      i.onload = () => resolve(i);
      i.onerror = () => reject(new Error('Das Bild ließ sich nicht laden.'));
      i.src = url;
    });
    const scale = Math.min(1, maxSide / Math.max(img.naturalWidth, img.naturalHeight));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(img.naturalWidth * scale));
    canvas.height = Math.max(1, Math.round(img.naturalHeight * scale));
    const g = canvas.getContext('2d');
    g.fillStyle = '#fff';
    g.fillRect(0, 0, canvas.width, canvas.height);
    g.drawImage(img, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.86));
    if (!blob) throw new Error('Das Bild ließ sich nicht verkleinern.');
    return { mime: 'image/jpeg', data: await blob.arrayBuffer() };
  } finally {
    URL.revokeObjectURL(url);
  }
}

export const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
