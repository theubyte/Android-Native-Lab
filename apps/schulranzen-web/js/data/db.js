// A thin promise layer over IndexedDB. Each write runs in one transaction, so a closed tab
// or a crash leaves either all of it or none of it.
const NAME = 'schulranzen';
const VERSION = 1;

let dbPromise = null;

function upgrade(db) {
  db.createObjectStore('decks', { keyPath: 'id' });
  const cards = db.createObjectStore('cards', { keyPath: 'id' });
  cards.createIndex('deckId', 'deckId');
  db.createObjectStore('exams', { keyPath: 'id' });
  const reviews = db.createObjectStore('reviews', { keyPath: 'id' });
  reviews.createIndex('cardId', 'cardId');
  db.createObjectStore('mockRuns', { keyPath: 'id' });
  db.createObjectStore('focus', { keyPath: 'id' });
  db.createObjectStore('packs', { keyPath: 'id' });
  db.createObjectStore('images', { keyPath: 'id' });
  db.createObjectStore('settings', { keyPath: 'key' });
}

export function openDb() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    if (!('indexedDB' in window)) { reject(new Error('Dieser Browser kann keine Daten speichern.')); return; }
    const req = indexedDB.open(NAME, VERSION);
    req.onupgradeneeded = () => upgrade(req.result);
    req.onerror = () => reject(req.error ?? new Error('Der Speicher ließ sich nicht öffnen.'));
    req.onblocked = () => reject(new Error('Die App ist in einem anderen Tab offen. Schließe ihn und lade neu.'));
    req.onsuccess = () => {
      const db = req.result;
      // Another tab brings a newer version: step aside instead of blocking it.
      db.onversionchange = () => { db.close(); dbPromise = null; };
      db.onclose = () => { dbPromise = null; };
      resolve(db);
    };
  });
  dbPromise.catch(() => { dbPromise = null; });
  return dbPromise;
}

const asPromise = (req) => new Promise((resolve, reject) => {
  req.onsuccess = () => resolve(req.result);
  req.onerror = () => reject(req.error);
});

export async function getAll(store) {
  const db = await openDb();
  return asPromise(db.transaction(store).objectStore(store).getAll());
}

export async function get(store, key) {
  const db = await openDb();
  return asPromise(db.transaction(store).objectStore(store).get(key));
}

/**
 * Runs [work] on one transaction over [stores]. work(tx) gets a tx.put/del/clear helper and
 * may be synchronous only: anything awaited in between would end the transaction.
 */
export async function write(stores, work) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const t = db.transaction(stores, 'readwrite');
    const api = {
      put: (store, value) => t.objectStore(store).put(value),
      del: (store, key) => t.objectStore(store).delete(key),
      clear: (store) => t.objectStore(store).clear(),
    };
    t.oncomplete = () => resolve();
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error ?? new Error('Speichern abgebrochen.'));
    try {
      work(api);
    } catch (e) {
      t.abort();
      reject(e);
    }
  });
}

export async function deleteEverything() {
  const db = await openDb();
  db.close();
  dbPromise = null;
  return new Promise((resolve, reject) => {
    const req = indexedDB.deleteDatabase(NAME);
    req.onsuccess = () => resolve();
    req.onerror = () => reject(req.error);
    req.onblocked = () => resolve();
  });
}
