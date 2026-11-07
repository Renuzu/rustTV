// rustTV – remote-control navigation (TV browsers, Samsung Tizen, keyboard).
// Arrow keys move the focus to the nearest element in that direction,
// OK/Enter clicks it, Back/Return goes one screen back.
(function () {
  "use strict";

  // Samsung Tizen: ask the TV to also deliver media and channel keys.
  try {
    if (window.tizen && tizen.tvinputdevice) {
      ["MediaPlayPause", "MediaPlay", "MediaPause", "MediaStop", "MediaFastForward",
       "MediaRewind", "ChannelUp", "ChannelDown"].forEach(function (k) {
        try { tizen.tvinputdevice.registerKey(k); } catch (e) { /* key not available */ }
      });
    }
  } catch (e) { /* not a Tizen TV */ }

  const SEL = "a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex='-1'])";
  const BACK_CODES = [10009, 461, 8]; // Tizen Return, webOS Back, Backspace

  function visible(el) {
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    const s = getComputedStyle(el);
    return s.visibility !== "hidden" && s.display !== "none";
  }

  function scope() {
    // inside an open dialog only the dialog's elements count
    const m = document.querySelector(".modal");
    return m || document;
  }

  function candidates() {
    return Array.prototype.filter.call(scope().querySelectorAll(SEL), visible);
  }

  function move(dir) {
    const list = candidates();
    if (!list.length) return false;
    const cur = document.activeElement;
    if (!cur || cur === document.body || list.indexOf(cur) < 0) {
      // start on the main content: first tile / card, else the first element
      const first = list.filter(function (el) { return el.matches(".tile, .card, .cattile, .chip.on"); })[0] || list[0];
      first.focus();
      first.scrollIntoView({ block: "nearest", inline: "nearest" });
      return true;
    }
    const a = cur.getBoundingClientRect();
    const ax = a.left + a.width / 2, ay = a.top + a.height / 2;
    let best = null, bestScore = Infinity;
    list.forEach(function (el) {
      if (el === cur) return;
      const b = el.getBoundingClientRect();
      const bx = b.left + b.width / 2, by = b.top + b.height / 2;
      const dx = bx - ax, dy = by - ay;
      let main, side;
      if (dir === "right") { if (b.left < a.right - 4 && dx <= 4) return; main = dx; side = dy; }
      else if (dir === "left") { if (b.right > a.left + 4 && dx >= -4) return; main = -dx; side = dy; }
      else if (dir === "down") { if (b.top < a.bottom - 4 && dy <= 4) return; main = dy; side = dx; }
      else { if (b.bottom > a.top + 4 && dy >= -4) return; main = -dy; side = dx; }
      if (main <= 0) return;
      const score = main + Math.abs(side) * 2.5;
      if (score < bestScore) { bestScore = score; best = el; }
    });
    if (!best) return false;
    best.focus();
    best.scrollIntoView({ block: "nearest", inline: "nearest" });
    return true;
  }

  function goBack() {
    const bg = document.querySelector(".modal-bg");
    if (bg) {
      const cancel = bg.querySelector(".actions .ghost") || bg;
      cancel.click();
      return;
    }
    const d = document;
    if (d.fullscreenElement || d.webkitFullscreenElement) {
      (d.exitFullscreen || d.webkitExitFullscreen).call(d);
      return;
    }
    if (location.pathname !== "/" && location.pathname !== "/index.html") { history.back(); return; }
    try { tizen.application.getCurrentApplication().exit(); } catch (e) { /* not Tizen */ }
  }

  function playerActive() {
    return !!(window.rustvPlayer && window.rustvPlayer.active && window.rustvPlayer.active());
  }

  function key(type) {
    document.dispatchEvent(new KeyboardEvent("keydown", { key: type, bubbles: true }));
  }

  document.addEventListener("keydown", function (e) {
    if (e.__rustv) return;
    const t = e.target || document.body;
    const typing = /INPUT|TEXTAREA/.test(t.tagName) && t.type !== "checkbox" && t.type !== "radio";

    // TV media / channel keys → keys the player understands
    switch (e.keyCode) {
      case 427: e.preventDefault(); key("ChannelUp"); return;    // Tizen CH+
      case 428: e.preventDefault(); key("ChannelDown"); return;  // Tizen CH−
      case 10252: case 415: case 19: e.preventDefault(); key(" "); return; // play/pause
    }

    if (BACK_CODES.indexOf(e.keyCode) >= 0 || e.key === "GoBack" || e.key === "BrowserBack") {
      if (e.keyCode === 8 && typing) return; // Backspace inside a text field
      e.preventDefault();
      goBack();
      return;
    }

    const dir = { ArrowUp: "up", ArrowDown: "down", ArrowLeft: "left", ArrowRight: "right",
                  Up: "up", Down: "down", Left: "left", Right: "right" }[e.key];
    if (dir) {
      // the video player uses the arrows itself (seek / zap) while nothing else is focused
      const cur = document.activeElement;
      if (playerActive() && (!cur || cur === document.body || (window.rustvPlayer.box() && window.rustvPlayer.box().contains(cur)))) return;
      // inside a text field left/right move the cursor
      if (typing && (dir === "left" || dir === "right")) return;
      if (move(dir)) e.preventDefault();
      return;
    }

    if (e.key === "Enter") {
      const cur = document.activeElement;
      // div cards with tabindex: OK = click
      if (cur && cur.getAttribute && cur.getAttribute("tabindex") === "0" && cur.tagName === "DIV") {
        e.preventDefault();
        cur.click();
      }
    }
  });
})();
