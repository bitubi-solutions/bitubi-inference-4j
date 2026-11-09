'use strict';
// bi4j chat: the few things HTML and htmx do not do. No framework, no inline handlers (strict CSP).
(function () {
  var stick = true; // follow the newest text unless the reader scrolled up
  var lastTextarea = null;

  function textarea() { return document.getElementById('text'); }
  function messages() { return document.getElementById('messages'); }

  function autosize(el) {
    if (!el) return;
    el.style.height = 'auto'; // CSSOM changes are allowed by the CSP; style attributes in markup are not
    el.style.height = Math.min(el.scrollHeight, 192) + 'px';
  }

  // the settings panel: a drawer opened by the header button, closed by its own button or Esc
  function panel() { return document.getElementById('settings-panel'); }
  function openPanel(button) {
    var p = panel();
    if (!p) return;
    p.classList.add('open');
    button.setAttribute('aria-expanded', 'true');
    p.focus();
  }
  function closePanel() {
    var p = panel();
    var button = document.getElementById('settings-open');
    if (!p || !p.classList.contains('open')) return false;
    p.classList.remove('open');
    if (button) { button.setAttribute('aria-expanded', 'false'); button.focus(); }
    return true;
  }
  document.addEventListener('click', function (e) {
    var opener = e.target.closest('#settings-open');
    if (opener) { var p = panel(); if (p && p.classList.contains('open')) closePanel(); else openPanel(opener); return; }
    if (e.target.closest('[data-close-panel]')) closePanel();
  });

  // copy buttons on code blocks (delegated, so they work on streamed content)
  document.addEventListener('click', function (e) {
    var copy = e.target.closest('[data-copy]');
    if (copy) {
      var code = copy.closest('.code').querySelector('pre code');
      var text = code ? code.textContent : '';
      var done = function () {
        copy.textContent = 'Copied';
        setTimeout(function () { copy.textContent = 'Copy'; }, 1500);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(done, function () { select(code); });
      } else {
        select(code);
      }
      return;
    }
    var starter = e.target.closest('[data-starter]');
    if (starter) {
      var ta = textarea();
      if (ta) { ta.value = starter.getAttribute('data-starter'); autosize(ta); ta.focus(); }
      return;
    }
    var open = e.target.closest('[data-open-dialog]');
    if (open) {
      var dialog = open.closest('li').querySelector('dialog');
      if (dialog && dialog.showModal) dialog.showModal();
    }
  });

  function select(node) {
    if (!node) return;
    var range = document.createRange();
    range.selectNodeContents(node);
    var sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
  }

  // Enter sends, Shift+Enter inserts a newline, an IME composition is left alone
  document.addEventListener('keydown', function (e) {
    var t = e.target;
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && t && t.id === 'text') {
      e.preventDefault();
      if (t.value.trim() && t.form) t.form.requestSubmit();
      return;
    }
    if (e.key === 'Escape') {
      if (closePanel()) return;
      var stop = document.getElementById('stop');
      var rename = t && t.matches && t.matches('[data-rename-input]') ? t : null;
      if (rename) {
        var li = rename.closest('li');
        if (window.htmx && li) window.htmx.ajax('GET', rename.getAttribute('data-cancel-url'), { target: li, swap: 'outerHTML' });
      } else if (stop) {
        stop.click();
      } else {
        var dialog = document.querySelector('dialog[open]');
        if (dialog) dialog.close();
      }
    }
  });

  document.addEventListener('input', function (e) {
    if (e.target && e.target.id === 'text') autosize(e.target);
  });

  // a closed dialog from the delete confirmation: the form buttons close it by themselves ("method=dialog");
  // the Delete button talks to the server, so close the dialog once the list is replaced
  document.addEventListener('click', function (e) {
    var del = e.target.closest('dialog .danger');
    if (del) { var d = del.closest('dialog'); setTimeout(function () { if (d.open) d.close(); }, 0); }
  });

  // stick-to-bottom: follow while the reader is at the bottom, stop when they scroll up
  document.addEventListener('scroll', function (e) {
    if (e.target && e.target.id === 'messages') {
      var m = e.target;
      stick = m.scrollHeight - m.scrollTop - m.clientHeight < 48;
    }
  }, true);

  function onMutation() {
    var m = messages();
    if (m && stick) m.scrollTop = m.scrollHeight;
    var ta = textarea();
    if (ta !== lastTextarea) { // the composer was replaced (after a send): keep the keyboard in it
      lastTextarea = ta;
      if (ta && !ta.disabled) ta.focus();
    }
  }
  new MutationObserver(onMutation).observe(document.body, { childList: true, subtree: true, characterData: true });
  document.addEventListener('DOMContentLoaded', onMutation);

  // the server always ends a turn with a final event; a broken stream only shows in the console
  document.addEventListener('htmx:sse:error', function (e) { console.warn('stream error', e.detail); });
})();
