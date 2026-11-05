// Runs before the first paint so a dark setting never flashes light. The real setting lives
// in IndexedDB; this copy is only a convenience and may be missing.
(function () {
  try {
    var t = localStorage.getItem('schulranzen-theme');
    if (t === 'light' || t === 'dark') document.documentElement.setAttribute('data-theme', t);
  } catch (e) { /* private mode: follow the system */ }
}());
