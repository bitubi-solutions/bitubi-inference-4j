'use strict';
// Blocking, in <head>: sets the theme before the first paint (no flash). The only thing it keeps in the browser is the
// choice: "light", "dark", or nothing (follow the system). A blocked or garbage storage means "system", never an error.
(function () {
  var KEY = 'bi4j.theme';
  var query = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

  function stored() {
    try {
      var v = window.localStorage.getItem(KEY);
      return v === 'light' || v === 'dark' ? v : 'system';
    } catch (e) {
      return 'system';
    }
  }

  var current = stored();

  function apply() {
    var resolved = current === 'system' ? (query && query.matches ? 'dark' : 'light') : current;
    document.documentElement.setAttribute('data-theme', resolved);
    document.documentElement.setAttribute('data-theme-mode', current);
  }

  function set(mode) {
    current = mode;
    try {
      if (mode === 'system') window.localStorage.removeItem(KEY);
      else window.localStorage.setItem(KEY, mode);
    } catch (e) {
      // the choice lasts until the page closes
    }
    apply();
  }

  window.bi4jTheme = { mode: function () { return current; }, set: set };
  apply();
  if (query && query.addEventListener) query.addEventListener('change', function () { if (current === 'system') apply(); });
})();
