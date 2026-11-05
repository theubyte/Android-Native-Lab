import { openDb } from './data/db.js';
import { registerWorker } from './platform.js';
import { startApp } from './app.js';
import { toast } from './ui/dom.js';

// A write can fail, most often because the storage is full. Nothing may fail silently.
window.addEventListener('unhandledrejection', (e) => {
  console.error(e.reason);
  const full = e.reason?.name === 'QuotaExceededError';
  toast(full ? 'Der Speicher ist voll. Lösche Bilder oder Stapel und sichere deine Daten.' : `Das ging nicht: ${e.reason?.message ?? 'unbekannter Fehler'}`, { ms: 8000 });
});

async function boot() {
  registerWorker();
  try {
    await openDb();
    await startApp();
  } catch (e) {
    console.error(e);
    const note = document.getElementById('boot-note');
    if (note) {
      note.textContent = 'Der Speicher dieses Browsers ist nicht erreichbar. Im privaten Modus geht das oft nicht. '
        + 'Öffne die App in einem normalen Fenster.';
    } else {
      document.getElementById('app').textContent = e.message;
    }
  }
}

boot();
