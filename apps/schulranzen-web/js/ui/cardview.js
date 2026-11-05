// Showing card contents: text, pictures, source and status.
import { h, openPanel } from './dom.js';
import { imageUrl } from '../data/repo.js';
import { sourceLine } from './texts.js';

/** A picture from storage; a tap opens it large. */
export function maybeImage(id, alt) {
  if (!id) return null;
  const img = h('img.flash-img', { alt: alt || 'Bild auf der Karte' });
  imageUrl(id).then((url) => { if (url) img.src = url; });
  const open = (e) => {
    e.stopPropagation();
    openPanel('Bild', () => h('img', { src: img.src, alt: alt || 'Bild auf der Karte', css: { width: '100%', borderRadius: '10px', background: '#fff' } }));
  };
  return h('button', { type: 'button', onclick: open, 'aria-label': 'Bild groß zeigen', css: { background: 'none', border: '0', padding: '0', cursor: 'zoom-in', alignSelf: 'center' } }, img);
}

export function statusTags(card) {
  return card.verified === false ? h('span.tag.tag-warn', { title: 'Inhalt noch nicht mit dem Buch verglichen' }, 'Ungeprüft') : null;
}

/** Solution side for every question kind. */
export function solution(card) {
  const line = sourceLine(card);
  return h('div.stack', { vars: { '--gap': '10px' }, class: 'flash-back' },
    card.back ? h('div.flash-text', {}, cleanCloze(card.back)) : null,
    maybeImage(card.backImage, 'Bild auf der Rückseite'),
    line ? h('p.flash-foot', {}, line) : null);
}

/** The gap marks are for the machine; a learner reads the plain sentence. */
export const cleanCloze = (text) => text.replace(/\{\{c\d+::(.+?)(?:::[^}]*)?\}\}/g, '$1');
