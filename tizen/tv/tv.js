/* rustTV for TVs (Samsung Tizen).
 * Plain JavaScript, no framework: lists only draw the rows you can see,
 * data is loaded per category, video uses Samsung's own player (AVPlay). */
(function () {
  "use strict";
  var VERSION = "__VERSION__";

  // ================================================================== keys
  var K = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, OK: 13, BACK: 10009, ESC: 27, BS: 8,
    RED: 403, GREEN: 404, YELLOW: 405, BLUE: 406, CHUP: 427, CHDOWN: 428, PREVCH: 10190, INFO: 457,
    PLAYPAUSE: 10252, PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, RW: 412, PGUP: 33, PGDN: 34 };
  try {
    ["ColorF0Red", "ColorF1Green", "ColorF2Yellow", "ColorF3Blue", "ChannelUp", "ChannelDown", "PreviousChannel", "Info",
     "MediaPlayPause", "MediaPlay", "MediaPause", "MediaStop", "MediaFastForward", "MediaRewind",
     "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"].forEach(function (k) {
      try { tizen.tvinputdevice.registerKey(k); } catch (e) {}
    });
  } catch (e) {}

  // ================================================================== helpers
  var $app = document.getElementById("app"), $bg = document.getElementById("bg");
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function load(k, d) { try { var v = localStorage.getItem("rtv." + k); return v ? JSON.parse(v) : d; } catch (e) { return d; } }
  function save(k, v) { try { localStorage.setItem("rtv." + k, JSON.stringify(v)); } catch (e) {} }
  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function hm(ts) { var d = new Date(ts * 1000); return pad(d.getHours()) + ":" + pad(d.getMinutes()); }
  function dayhm(ts) {
    var d = new Date(ts * 1000), t = new Date(), y = new Date(Date.now() - 864e5);
    var day = d.toDateString() === t.toDateString() ? "" : d.toDateString() === y.toDateString() ? "gestern " : pad(d.getDate()) + "." + pad(d.getMonth() + 1) + ". ";
    return day + hm(ts);
  }
  function now() { return Math.floor(Date.now() / 1000); }
  function clock() { var d = new Date(); return pad(d.getHours()) + ":" + pad(d.getMinutes()); }
  function b64(s) { try { return decodeURIComponent(escape(atob(s || ""))); } catch (e) { return s || ""; } }
  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
  function cssUrl(u) { return u ? "url('" + String(u).replace(/'/g, "%27").replace(/\\/g, "/") + "')" : "none"; }
  var toastT = null;
  function toast(msg) {
    var t = document.getElementById("toast");
    t.textContent = msg; t.style.display = "block";
    clearTimeout(toastT); toastT = setTimeout(function () { t.style.display = "none"; }, 2600);
  }
  function img(u) { return u ? '<img src="' + esc(u) + '" onerror="this.style.visibility=\'hidden\'">' : '<div class="noimg"></div>'; }
  // the page background with a see-through hole where the preview video is
  function bgHole(r) {
    if (!r) { $bg.style.clipPath = $bg.style.webkitClipPath = ""; return; }
    var x1 = r.x, y1 = r.y, x2 = r.x + r.w, y2 = r.y + r.h;
    var p = "polygon(0 0, 0 1080px, " + x1 + "px 1080px, " + x1 + "px " + y1 + "px, " + x2 + "px " + y1 + "px, " + x2 + "px " + y2 + "px, " +
      x1 + "px " + y2 + "px, " + x1 + "px 1080px, 1920px 1080px, 1920px 0)";
    $bg.style.clipPath = p; $bg.style.webkitClipPath = p;
  }

  // ================================================================== accounts
  var accs = load("accs", null), cur = load("cur", 0);
  if (!accs) { var old = load("acc", null); accs = old ? [old] : []; save("accs", accs); }
  var acc = accs[cur] || accs[0] || null;
  function accKey() { return acc ? (acc.user + "@" + acc.server).replace(/[^\w@.:-]/g, "") : "none"; }
  function sload(k, d) { return load(accKey() + "." + k, d); }
  function ssave(k, v) { save(accKey() + "." + k, v); }

  // ================================================================== provider (Xtream Codes)
  function base() {
    var u = (acc.server || "").trim();
    if (!/^https?:\/\//i.test(u)) u = "http://" + u;
    return u.replace(/\/(player_api\.php|get\.php)?\/*$/, "");
  }
  function api(action, extra, cb) {
    var url = base() + "/player_api.php?username=" + encodeURIComponent(acc.user) + "&password=" + encodeURIComponent(acc.pass) +
      (action ? "&action=" + action : "") + (extra || "");
    var x = new XMLHttpRequest();
    x.open("GET", url); x.timeout = 40000;
    x.onload = function () {
      var j = null; try { j = JSON.parse(x.responseText); } catch (e) {}
      cb(j === null ? "Unerwartete Antwort vom Anbieter (" + x.status + ")" : null, j);
    };
    x.onerror = function () { cb("Keine Verbindung zum Anbieter"); };
    x.ontimeout = function () { cb("Anbieter antwortet nicht"); };
    x.send();
  }
  var cache = {};
  function cached(key, action, extra, cb) {
    if (cache[key]) return cb(null, cache[key]);
    api(action, extra, function (e, j) { if (!e) cache[key] = j || []; cb(e, j || []); });
  }
  var KINDS = {
    live: { cats: "get_live_categories", list: "get_live_streams", title: "Live-TV" },
    movie: { cats: "get_vod_categories", list: "get_vod_streams", title: "Filme" },
    series: { cats: "get_series_categories", list: "get_series", title: "Serien" }
  };
  function idOf(kind, e) { return String(kind === "series" ? e.series_id : kind === "episode" ? e.id : e.stream_id); }
  function iconOf(e) { return e.stream_icon || e.cover || (e.info && e.info.movie_image) || ""; }
  function streamUrl(kind, e) {
    var u = encodeURIComponent(acc.user), p = encodeURIComponent(acc.pass);
    if (kind === "live") return base() + "/live/" + u + "/" + p + "/" + e.stream_id + ".ts";
    if (kind === "movie") return base() + "/movie/" + u + "/" + p + "/" + e.stream_id + "." + (e.container_extension || "mp4");
    if (kind === "archive") return e.url;
    return base() + "/series/" + u + "/" + p + "/" + e.id + "." + (e.container_extension || "mp4");
  }
  function timeshiftUrl(ch, p) {
    var d = new Date(p.s * 1000), mins = Math.max(1, Math.round((p.e - p.s) / 60));
    var start = d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate()) + ":" + pad(d.getHours()) + "-" + pad(d.getMinutes());
    return base() + "/timeshift/" + encodeURIComponent(acc.user) + "/" + encodeURIComponent(acc.pass) + "/" + mins + "/" + start + "/" + ch.stream_id + ".ts";
  }

  // ---- personal lists (per account)
  function slim(kind, e) {
    var o = { name: e.name, stream_icon: e.stream_icon, cover: e.cover, category_id: e.category_id, container_extension: e.container_extension,
      tv_archive: e.tv_archive, rating: e.rating, added: e.added, num: e.num };
    if (kind === "series") o.series_id = e.series_id; else o.stream_id = e.stream_id;
    return o;
  }
  function favs(kind) { return sload("fav." + kind, []); }
  function isFav(kind, e) { var id = idOf(kind, e); return favs(kind).some(function (x) { return idOf(kind, x) === id; }); }
  function toggleFav(kind, e) {
    var id = idOf(kind, e), l = favs(kind), had = l.some(function (x) { return idOf(kind, x) === id; });
    l = l.filter(function (x) { return idOf(kind, x) !== id; });
    if (!had) l.unshift(slim(kind, e));
    ssave("fav." + kind, l);
    toast(had ? "Aus Favoriten entfernt" : "★ Zu Favoriten hinzugefügt");
  }
  function addHist(kind, e) {
    var id = idOf(kind, e), l = sload("hist." + kind, []).filter(function (x) { return idOf(kind, x) !== id; });
    l.unshift(slim(kind, e)); ssave("hist." + kind, l.slice(0, 40));
  }
  // continue watching: movies + episodes with a saved position
  function cwList() { return sload("cw", []); }
  function cwSave(item, posMs, durMs) {
    var l = cwList().filter(function (x) { return x.key !== item.key; });
    if (durMs && posMs < durMs - 90000 && posMs > 60000) { item.pos = posMs; item.dur = durMs; item.t = now(); l.unshift(item); }
    ssave("cw", l.slice(0, 20));
  }
  function cwPos(key) { var c = cwList().filter(function (x) { return x.key === key; })[0]; return c ? c.pos : 0; }

  // ---- category settings: hidden / locked (PIN)
  function hidden(kind) { return sload("hidden." + kind, []); }
  function locked(kind) { return sload("locked." + kind, []); }
  var unlocked = false;
  function pinSet() { return !!load("pin", ""); }

  function entries(kind, cat, cb) {
    if (cat === "fav") return cb(null, favs(kind));
    if (cat === "hist") return cb(null, sload("hist." + kind, []));
    cached(kind + ":" + cat, KINDS[kind].list, cat === "*" ? "" : "&category_id=" + encodeURIComponent(cat), function (e, list) {
      if (e) return cb(e, []);
      if (cat !== "*") list = list.filter(function (x) { return String(x.category_id) === String(cat); });
      else {
        var hid = hidden(kind).concat(unlocked ? [] : locked(kind));
        if (hid.length) list = list.filter(function (x) { return hid.indexOf(String(x.category_id)) < 0; });
      }
      cb(null, list);
    });
  }
  var SORTS = {
    live: [["prov", "Anbieter-Reihenfolge"], ["az", "Name A–Z"], ["za", "Name Z–A"]],
    movie: [["prov", "Anbieter-Reihenfolge"], ["new", "Neueste zuerst"], ["az", "Name A–Z"], ["rating", "Bewertung"]],
    series: [["prov", "Anbieter-Reihenfolge"], ["new", "Neueste zuerst"], ["az", "Name A–Z"], ["rating", "Bewertung"]]
  };
  function sortList(kind, l) {
    var s = sload("sort." + kind, "prov"), c = l.slice();
    var name = function (x) { return String(x.name || "").toLowerCase(); };
    if (s === "az") c.sort(function (a, b) { return name(a) < name(b) ? -1 : name(a) > name(b) ? 1 : 0; });
    else if (s === "za") c.sort(function (a, b) { return name(a) < name(b) ? 1 : name(a) > name(b) ? -1 : 0; });
    else if (s === "new") c.sort(function (a, b) { return (+(b.added || b.last_modified || 0)) - (+(a.added || a.last_modified || 0)); });
    else if (s === "rating") c.sort(function (a, b) { return (parseFloat(b.rating) || 0) - (parseFloat(a.rating) || 0); });
    return c;
  }

  // ================================================================== video (AVPlay on Samsung, <video> elsewhere)
  var hasAv = false;
  try { hasAv = !!(window.webapis && webapis.avplay); } catch (e) {}
  var avObj = document.getElementById("av"), vid = document.getElementById("vid");
  if (hasAv) document.documentElement.classList.add("tv");
  var MODES = [["PLAYER_DISPLAY_MODE_LETTER_BOX", "Original"], ["PLAYER_DISPLAY_MODE_FULL_SCREEN", "Bildschirm füllen"], ["PLAYER_DISPLAY_MODE_AUTO_ASPECT_RATIO", "Automatisch"]];
  var player = {
    url: null, onState: null, onSub: null,
    rect: function (x, y, w, h) {
      var el = hasAv ? avObj : vid;
      el.style.left = x + "px"; el.style.top = y + "px"; el.style.width = w + "px"; el.style.height = h + "px";
      if (hasAv) { try { webapis.avplay.setDisplayRect(x, y, w, h); } catch (e) {} }
    },
    play: function (url, x, y, w, h, startMs) {
      this.stop();
      this.url = url;
      this.rect(x, y, w, h);
      var self = this;
      if (hasAv) {
        try {
          var av = webapis.avplay;
          av.open(url);
          av.setDisplayRect(x, y, w, h);
          try { av.setDisplayMethod(MODES[load("mode", 0)][0]); } catch (e) {}
          try { av.setStreamingProperty("ADAPTIVE_INFO", "BITRATES=HIGHEST"); } catch (e) {}
          av.setListener({
            onbufferingstart: function () { self.state("buffer"); },
            onbufferingcomplete: function () { self.state("play"); },
            onstreamcompleted: function () { self.state("end"); },
            onerror: function (e) { self.state("error", e); },
            onsubtitlechange: function (d, text) { if (self.onSub) self.onSub(text); }
          });
          av.prepareAsync(function () {
            if (startMs) { try { av.seekTo(startMs); } catch (e) {} }
            av.play(); self.state("play");
          }, function (e) { self.state("error", e); });
        } catch (e) { self.state("error", e); }
      } else {
        vid.style.display = "block";
        vid.src = url;
        vid.onplaying = function () { self.state("play"); };
        vid.onwaiting = function () { self.state("buffer"); };
        vid.onended = function () { self.state("end"); };
        vid.onerror = function () { self.state("error"); };
        if (startMs) vid.currentTime = startMs / 1000;
        var p = vid.play(); if (p && p.catch) p.catch(function () {});
      }
    },
    stop: function () {
      this.url = null;
      if (hasAv) { try { webapis.avplay.stop(); webapis.avplay.close(); } catch (e) {} }
      else { vid.pause(); vid.removeAttribute("src"); try { vid.load(); } catch (e) {} vid.style.display = "none"; }
      this.rect(0, 0, 0, 0);
    },
    toggle: function () {
      if (hasAv) { try { if (webapis.avplay.getState() === "PLAYING") webapis.avplay.pause(); else webapis.avplay.play(); } catch (e) {} }
      else { if (vid.paused) vid.play(); else vid.pause(); }
    },
    paused: function () { if (hasAv) { try { return webapis.avplay.getState() === "PAUSED"; } catch (e) { return false; } } return vid.paused; },
    time: function () { if (hasAv) { try { return webapis.avplay.getCurrentTime(); } catch (e) { return 0; } } return (vid.currentTime || 0) * 1000; },
    duration: function () { if (hasAv) { try { return webapis.avplay.getDuration(); } catch (e) { return 0; } } return (vid.duration || 0) * 1000; },
    seek: function (ms) {
      if (hasAv) { try { if (ms > 0) webapis.avplay.jumpForward(ms); else webapis.avplay.jumpBackward(-ms); } catch (e) {} }
      else vid.currentTime = Math.max(0, vid.currentTime + ms / 1000);
    },
    seekTo: function (ms) { if (hasAv) { try { webapis.avplay.seekTo(ms); } catch (e) {} } else vid.currentTime = ms / 1000; },
    tracks: function (type) { // "AUDIO" or "TEXT"
      var out = [];
      if (!hasAv) return out;
      try {
        webapis.avplay.getTotalTrackInfo().forEach(function (t) {
          if (t.type !== type) return;
          var lang = "";
          try { var x = JSON.parse(t.extra_info); lang = x.language || x.track_lang || ""; } catch (e) {}
          out.push({ index: t.index, label: (lang || "Spur " + (out.length + 1)).toUpperCase() });
        });
      } catch (e) {}
      return out;
    },
    selectTrack: function (type, index) { try { webapis.avplay.setSelectTrack(type, index); } catch (e) { toast("Spur nicht verfügbar"); } },
    subtitles: function (on) { try { webapis.avplay.setSilentSubtitle(!on); } catch (e) {} },
    setMode: function (i) { save("mode", i); try { webapis.avplay.setDisplayMethod(MODES[i][0]); } catch (e) {} },
    state: function (s, e) { if (this.onState) this.onState(s, e); }
  };

  // sleep timer
  var sleepAt = 0, sleepStep = 0;
  setInterval(function () {
    if (sleepAt && now() >= sleepAt) { sleepAt = 0; sleepStep = 0; player.stop(); try { tizen.application.getCurrentApplication().exit(); } catch (e) {} }
  }, 15000);

  // ================================================================== screens + overlays
  var stack = [], screen = null, overlay = null;
  function show(s, replace) {
    closeOverlay();
    if (screen && screen.leave) screen.leave();
    if (replace) stack.pop();
    stack.push(s); screen = s;
    bgHole(null);
    if (s.enter) s.enter();
    s.render();
  }
  var exitArmed = 0;
  function back() {
    if (stack.length <= 1) {
      if (Date.now() - exitArmed < 2500) { player.stop(); try { tizen.application.getCurrentApplication().exit(); } catch (e) {} return; }
      exitArmed = Date.now(); toast("Zum Beenden nochmal Zurück drücken"); return;
    }
    if (screen.leave) screen.leave();
    stack.pop(); screen = stack[stack.length - 1];
    bgHole(null);
    if (screen.enter) screen.enter();
    screen.render();
  }
  function home() { while (stack.length > 1) { if (screen.leave) screen.leave(); stack.pop(); screen = stack[stack.length - 1]; } bgHole(null); if (screen.enter) screen.enter(); screen.render(); }
  function top(title, sub) {
    return '<div class="top"><div class="crumb">' + esc(title) + (sub ? ' <span>› ' + esc(sub) + '</span>' : "") + '</div>' +
      '<div class="topr">' + (sleepAt ? '<span class="blue">Sleep ' + Math.ceil((sleepAt - now()) / 60) + ' min</span>' : "") + '<span class="clock">' + clock() + '</span></div></div>';
  }
  setInterval(function () { var c = document.querySelector(".clock, .pclock"); if (c) c.textContent = clock(); }, 20000);

  // ---- overlay: side menu
  function Menu(title, items, onClose) {
    var f = 0, el = document.createElement("div");
    el.className = "modal"; document.body.appendChild(el);
    var m = {
      render: function () {
        el.innerHTML = "<h2>" + esc(title) + "</h2>" + items.map(function (it, i) {
          return '<div class="mi' + (i === f ? " f" : "") + '">' + esc(it.label) + (it.value != null ? "<span>" + esc(typeof it.value === "function" ? it.value() : it.value) + "</span>" : "") + "</div>";
        }).join("");
      },
      key: function (k) {
        if (k === K.UP) f = (f - 1 + items.length) % items.length;
        else if (k === K.DOWN) f = (f + 1) % items.length;
        else if (k === K.OK) { var it = items[f]; if (it.keep) { it.action(); } else { closeOverlay(); it.action(); return true; } }
        else if (k === K.GREEN || k === K.LEFT) { closeOverlay(); return true; }
        else return true;
        m.render(); return true;
      },
      close: function () { el.parentNode && el.parentNode.removeChild(el); if (onClose) onClose(); }
    };
    m.render(); return m;
  }
  // ---- overlay: PIN entry with the number keys
  function Pin(title, cb) {
    var v = "", el = document.createElement("div");
    el.className = "pin"; document.body.appendChild(el);
    var p = {
      render: function () {
        el.innerHTML = "<h2>" + esc(title) + '</h2><div class="dots">' + "●●●●".slice(0, v.length) + "○○○○".slice(v.length) + "</div><p>4 Ziffern mit den Zahlentasten · Zurück = abbrechen</p>";
      },
      key: function (k) {
        if (k >= 48 && k <= 57) { v += String(k - 48); if (v.length === 4) { closeOverlay(); cb(v); return true; } p.render(); }
        return true;
      },
      close: function () { el.parentNode && el.parentNode.removeChild(el); }
    };
    p.render(); return p;
  }
  function openOverlay(o) { closeOverlay(); overlay = o; }
  function closeOverlay() { if (overlay) { var o = overlay; overlay = null; o.close(); } }
  function askPin(then) {
    if (!pinSet()) { then(); return; }
    openOverlay(Pin("PIN eingeben", function (v) { if (v === load("pin", "")) { unlocked = true; then(); } else toast("Falsche PIN"); }));
  }
  function setPin(then) {
    openOverlay(Pin("Neue PIN festlegen", function (a) {
      openOverlay(Pin("PIN wiederholen", function (b) {
        if (a !== b) { toast("PINs stimmen nicht überein"); return; }
        save("pin", a); unlocked = true; toast("PIN gespeichert"); if (then) then();
      }));
    }));
  }

  // ---- number keys → channel number
  function NumInput(onDone) {
    var v = "", t = null, el = null;
    return function (k) {
      if (k < 48 || k > 57) return false;
      v += String(k - 48);
      if (!el) { el = document.createElement("div"); el.className = "numbox"; document.body.appendChild(el); }
      el.textContent = v;
      clearTimeout(t);
      t = setTimeout(function () { var n = parseInt(v, 10); v = ""; if (el) { el.parentNode.removeChild(el); el = null; } onDone(n); }, 1500);
      return true;
    };
  }

  // ================================================================== login (also used to add an account)
  function Login(msg, adding) {
    var f = 0, a = adding ? { name: "", server: "", user: "", pass: "" } : (acc || { name: "", server: "", user: "", pass: "" });
    return {
      render: function () {
        $app.innerHTML = '<div class="form"><h1>rust<b>TV</b></h1><p>' + (adding ? "Weiteres Konto hinzufügen." : "Melde dich mit den Daten deines IPTV-Anbieters an (Xtream Codes).") + '</p>' +
          '<input class="inp" id="i0" placeholder="Server, z. B. http://anbieter.tv:8080" value="' + esc(a.server) + '">' +
          '<input class="inp" id="i1" placeholder="Benutzername" value="' + esc(a.user) + '">' +
          '<input class="inp" id="i2" type="password" placeholder="Passwort" value="' + esc(a.pass) + '">' +
          '<input class="inp" id="i3" placeholder="Name für dieses Konto (optional)" value="' + esc(a.name || "") + '">' +
          '<div class="err" id="err">' + esc(msg || "") + '</div><div class="btn" id="i4">Anmelden</div></div>';
        this.focus();
      },
      focus: function () {
        for (var i = 0; i < 5; i++) document.getElementById("i" + i).classList.toggle("f", i === f);
        var el = document.getElementById("i" + f);
        if (f < 4) el.focus(); else if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
      },
      key: function (k) {
        if (k === K.DOWN) { f = Math.min(4, f + 1); this.focus(); return true; }
        if (k === K.UP) { f = Math.max(0, f - 1); this.focus(); return true; }
        if (k === K.OK && f === 4) { this.submit(); return true; }
        if (k === K.OK && f < 4) { f++; this.focus(); return true; }
        return false;
      },
      submit: function () {
        var v = function (i) { return document.getElementById("i" + i).value.trim(); };
        var cand = { server: v(0), user: v(1), pass: v(2), name: v(3) };
        if (!cand.server || !cand.user) { document.getElementById("err").textContent = "Bitte Server und Benutzername eingeben."; return; }
        document.getElementById("err").textContent = "Prüfe Anmeldung …";
        var old = acc; acc = cand;
        api(null, "", function (e, j) {
          var ui = j && j.user_info;
          if (e || !ui || String(ui.auth) !== "1") {
            acc = old;
            document.getElementById("err").textContent = e || "Benutzername oder Passwort falsch.";
            return;
          }
          cand.exp = ui.exp_date || "";
          if (adding || !accs.length) { accs.push(cand); cur = accs.length - 1; } else { accs[cur] = cand; }
          save("accs", accs); save("cur", cur);
          acc = cand; cache = {}; allLists = null; epgCache = {}; unlocked = false;
          stack = []; show(Home());
        });
      }
    };
  }

  // ================================================================== home
  function Home() {
    var f = 0, cw = [];
    function exp() {
      if (!acc.exp || acc.exp === "null") return "";
      var d = new Date(parseInt(acc.exp, 10) * 1000);
      return " · gültig bis " + pad(d.getDate()) + "." + pad(d.getMonth() + 1) + "." + d.getFullYear();
    }
    var self = {
      enter: function () { cw = cwList(); if (f > 7 + cw.length) f = 0; },
      render: function () {
        $app.innerHTML = '<div class="top"><div class="logo">rust<b>TV</b></div><div class="topr"><span>' + esc(acc.name || acc.user) + esc(exp()) + '</span>' +
          (sleepAt ? '<span class="blue" style="margin-left:30px">Sleep ' + Math.ceil((sleepAt - now()) / 60) + ' min</span>' : "") + '<span class="clock">' + clock() + '</span></div></div>' +
          '<div class="home-main">' +
          '<div class="tile live" data-i="0"><h2>Live-TV</h2><p>Sender &amp; Programm</p></div>' +
          '<div class="tile movie" data-i="1"><h2>Filme</h2><p>nach Kategorien</p></div>' +
          '<div class="tile series" data-i="2"><h2>Serien</h2><p>mit Staffeln</p></div></div>' +
          '<div class="home-row"><div class="small" data-i="3">★ Favoriten</div><div class="small" data-i="4">Zuletzt gesehen</div>' +
          '<div class="small" data-i="5">Suche</div><div class="small" data-i="6">Konto</div><div class="small" data-i="7">Einstellungen</div></div>' +
          (cw.length ? '<div class="cw"><h3>Weiterschauen</h3><div class="cwrow">' + cw.slice(0, 6).map(function (x, i) {
            return '<div class="cwi" data-i="' + (8 + i) + '"><div class="img" style="background-image:' + cssUrl(x.icon) + '"><div class="prog"><i style="width:' +
              clamp(x.pos / Math.max(1, x.dur) * 100, 0, 100) + '%"></i></div></div><div class="pn">' + esc(x.title) + '</div></div>';
          }).join("") + '</div></div>' : "") +
          '<div class="hints"><span><b>◀ ▶ ▲ ▼</b> auswählen</span><span><b>OK</b> öffnen</span><span><i class="key k-blue"></i>Suche</span><span><b>Zurück</b> 2× beenden</span></div>';
        self.focus();
      },
      focus: function () {
        Array.prototype.forEach.call($app.querySelectorAll("[data-i]"), function (el) { el.classList.toggle("f", +el.getAttribute("data-i") === f); });
      },
      key: function (k) {
        var nCw = Math.min(6, cw.length);
        if (k === K.RIGHT) { if (f < 2 || (f >= 3 && f < 7) || (f >= 8 && f < 7 + nCw)) f++; }
        else if (k === K.LEFT) { if ((f > 0 && f <= 2) || (f > 3 && f <= 7) || f > 8) f--; }
        else if (k === K.DOWN) { if (f <= 2) f = 3 + Math.round(f * 1.6); else if (f <= 7 && nCw) f = 8 + Math.min(nCw - 1, f - 3); }
        else if (k === K.UP) { if (f >= 8) f = 3 + Math.min(4, f - 8); else if (f >= 3) f = Math.min(2, Math.floor((f - 3) / 1.6)); }
        else if (k === K.OK) {
          if (f === 0) show(Live());
          else if (f === 1) show(Grid("movie"));
          else if (f === 2) show(Grid("series"));
          else if (f === 3) show(Live("fav"));
          else if (f === 4) show(Live("hist"));
          else if (f === 5) show(Search());
          else if (f === 6) show(Accounts());
          else if (f === 7) show(Settings());
          else if (f >= 8) resumeCw(cw[f - 8]);
          return true;
        } else if (k === K.RED && f >= 8) { cw.splice(f - 8, 1); ssave("cw", cw); f = 8; if (!cw.length) f = 0; self.render(); return true; }
        else return false;
        self.focus(); return true;
      }
    };
    return self;
  }
  function resumeCw(x) {
    if (!x) return;
    if (x.kind === "movie") show(Player("movie", [x.e], 0));
    else show(Player("episode", x.list || [x.e], x.idx || 0, x.series, x.seriesObj));
  }

  // ================================================================== shared list helpers
  function catList(kind, cb) {
    cached(kind + ":cats", KINDS[kind].cats, "", function (e, cats) {
      var hid = hidden(kind), lk = locked(kind);
      var l = [{ id: "search", name: "Suche (alles)", sp: 1 }, { id: "fav", name: "★ Favoriten", sp: 1 }, { id: "hist", name: "Zuletzt gesehen", sp: 1 }, { id: "*", name: "Alle", sp: 1 }];
      (cats || []).forEach(function (c) {
        var id = String(c.category_id);
        if (hid.indexOf(id) >= 0) return;
        l.push({ id: id, name: c.category_name, locked: lk.indexOf(id) >= 0 });
      });
      cb(e, l);
    });
  }
  function catHtml(cats, ci, focused, rows) {
    return windowed(cats, ci, rows, function (x, i, s) {
      return '<div class="cat' + (x.sp ? " special" : "") + (s ? (focused ? " f sel" : " sel") : "") + '">' + (x.locked && !unlocked ? "🔒 " : "") + esc(x.name) + '</div>';
    });
  }
  function windowed(items, sel, rows, draw) {
    var start = clamp(sel - Math.floor(rows / 2), 0, Math.max(0, items.length - rows)), out = "";
    for (var i = start; i < Math.min(items.length, start + rows); i++) out += draw(items[i], i, i === sel);
    return out;
  }
  // options for a category (green key in the category column)
  function catMenu(kind, c, refresh) {
    var items = [];
    items.push({ label: "Sortierung", value: function () { var s = sload("sort." + kind, "prov"); return SORTS[kind].filter(function (x) { return x[0] === s; })[0][1]; }, keep: true,
      action: function () { var l = SORTS[kind], s = sload("sort." + kind, "prov"), i = 0; l.forEach(function (x, j) { if (x[0] === s) i = j; }); ssave("sort." + kind, l[(i + 1) % l.length][0]); overlay.render(); } });
    if (c && !c.sp) {
      items.push({ label: "Kategorie ausblenden", action: function () { var h = hidden(kind); h.push(c.id); ssave("hidden." + kind, h); delete cache[kind + ":cats"]; toast("Ausgeblendet – zurückholen unter Einstellungen"); refresh(true); } });
      items.push({ label: c.locked ? "PIN-Sperre entfernen" : "Mit PIN sperren", action: function () {
        var go = function () {
          var l = locked(kind).filter(function (x) { return x !== c.id; });
          if (!c.locked) l.push(c.id);
          ssave("locked." + kind, l); delete cache[kind + ":cats"]; toast(c.locked ? "Sperre entfernt" : "Gesperrt"); refresh(true);
        };
        if (!pinSet()) setPin(go); else askPin(go);
      } });
    }
    openOverlay(Menu("Optionen", items, function () { refresh(false); }));
  }

  // ================================================================== live TV
  var PREVIEW = { x: 0, y: 0, w: 0, h: 0 };
  function Live(startCat) {
    var cats = [], ci = 0, chans = [], hi = 0, zone = "list", loading = true, err = "";
    var epgT = null, prevT = null, epg = null, lastPreview = null, catName = "", loadedCat = null;
    var num = NumInput(function (n) { if (chans[n - 1]) { hi = n - 1; zone = "list"; self.render(); self.schedule(); } else toast("Sender " + n + " gibt es hier nicht"); });
    var self = {
      enter: function () {
        player.onState = null;
        if (cats.length) { if (lastPreview) self.schedule(); return; }
        self.reloadCats();
      },
      reloadCats: function () {
        catList("live", function (e, l) {
          var oldId = cats[ci] && cats[ci].id;
          cats = l; err = e || "";
          var want = oldId || startCat || sload("live.cat", "*");
          ci = 3; for (var i = 0; i < cats.length; i++) if (cats[i].id === want) ci = i;
          self.loadCat();
        });
      },
      leave: function () { clearTimeout(epgT); clearTimeout(prevT); player.stop(); bgHole(null); },
      loadCat: function (force) {
        var c = cats[ci]; if (!c || c.id === "search") return;
        if (c.locked && !unlocked) { askPin(function () { self.loadCat(true); }); return; }
        if (!force && loadedCat === c.id + sload("sort.live", "")) { zone = "list"; self.render(); return; }
        loading = true; chans = []; hi = 0; self.render();
        catName = c.name;
        if (!c.sp) ssave("live.cat", c.id);
        entries("live", c.id, function (e, l) {
          if (cats[ci] !== c) return;
          loading = false; err = e || ""; chans = (c.id === "hist" || c.id === "fav") ? l : sortList("live", l); hi = 0;
          loadedCat = c.id + sload("sort.live", "");
          zone = chans.length ? "list" : "cats";
          self.render(); self.schedule();
        });
      },
      render: function () {
        var c = cats[ci];
        $app.innerHTML = top("Live-TV", c ? c.name : "") +
          '<div class="cols"><div class="panel cats">' + catHtml(cats, ci, zone === "cats", 11) + '</div><div class="panel chs">' +
          (loading ? '<div class="empty">Lade …</div>' : err ? '<div class="empty red">' + esc(err) + '</div>' :
            !chans.length ? '<div class="empty">Keine Sender in dieser Kategorie.</div>' :
            windowed(chans, hi, 8, function (x, i, s) {
              return '<div class="ch' + (s && zone === "list" ? " f" : "") + (lastPreview === x ? " playing" : "") + '"><span class="no">' + (i + 1) + '</span>' + img(x.stream_icon) +
                '<div class="t"><div class="n">' + esc(x.name) + '</div><div class="s">' + (s && epg && epg[0] ? esc(epg[0].title) : (+x.tv_archive ? "⏪ Archiv" : "&nbsp;")) + '</div></div>' +
                (isFav("live", x) ? '<span class="star">★</span>' : "") + '</div>';
            })) +
          '</div><div class="side"><div class="preview" id="pv">' + (chans.length && !lastPreview ? "Vorschau" : "") + '</div>' +
          '<div class="panel epg" id="epg">' + self.epgHtml() + '</div></div></div>' +
          '<div class="hints"><span><b>OK</b> Vollbild</span><span><b>0–9</b> Sendernummer</span><span><i class="key k-red"></i>Favorit</span>' +
          '<span><i class="key k-green"></i>Optionen</span><span><i class="key k-yellow"></i>Programm / Archiv</span><span><i class="key k-blue"></i>Suche</span></div>';
        var pv = document.getElementById("pv");
        if (pv) { var r = pv.getBoundingClientRect(); PREVIEW = { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) }; }
        if (lastPreview) { player.rect(PREVIEW.x, PREVIEW.y, PREVIEW.w, PREVIEW.h); bgHole(PREVIEW); }
      },
      epgHtml: function () {
        if (!epg) return '<div class="empty">' + (chans.length ? "Programm lädt …" : "") + '</div>';
        if (!epg.length) return '<div class="empty">Kein Programm verfügbar.</div>';
        var a = epg[0], b = epg[1], pr = clamp((now() - a.s) / Math.max(1, a.e - a.s) * 100, 0, 100);
        return '<h4>JETZT · ' + hm(a.s) + ' – ' + hm(a.e) + '</h4><div class="title">' + esc(a.title) + '</div>' +
          '<div class="bar"><i style="width:' + pr + '%"></i></div><div class="desc">' + esc(a.desc) + '</div>' +
          (b ? '<h4>DANACH · ' + hm(b.s) + '</h4><div class="title" style="font-size:26px;font-weight:500">' + esc(b.title) + '</div>' : "");
      },
      schedule: function () {
        clearTimeout(epgT); clearTimeout(prevT); epg = null;
        var ch = chans[hi]; if (!ch) return;
        epgT = setTimeout(function () {
          loadEpg(ch, function (l) { if (chans[hi] === ch && screen === self) { epg = l; self.render(); } });
        }, 350);
        if (load("preview", true)) prevT = setTimeout(function () {
          if (chans[hi] !== ch || zone !== "list" || screen !== self) return;
          lastPreview = ch;
          self.render();
          player.play(streamUrl("live", ch), PREVIEW.x, PREVIEW.y, PREVIEW.w, PREVIEW.h);
        }, 900);
      },
      key: function (k) {
        if (num(k)) return true;
        if (k === K.GREEN) { catMenu("live", zone === "cats" ? cats[ci] : null, function (changed) { if (changed) { cats = []; self.reloadCats(); } else { loadedCat = null; self.loadCat(); } }); return true; }
        if (zone === "cats") {
          if (k === K.UP) { ci = Math.max(0, ci - 1); self.render(); return true; }
          if (k === K.DOWN) { ci = Math.min(cats.length - 1, ci + 1); self.render(); return true; }
          if (k === K.PGUP || k === K.PGDN) { ci = clamp(ci + (k === K.PGUP ? -10 : 10), 0, cats.length - 1); self.render(); return true; }
          if (cats[ci] && cats[ci].id === "search" && (k === K.OK || k === K.RIGHT)) { show(Search()); return true; }
          if (k === K.OK || k === K.RIGHT) { player.stop(); lastPreview = null; bgHole(null); self.loadCat(); return true; }
          return false;
        }
        if (k === K.LEFT) { zone = "cats"; clearTimeout(prevT); self.render(); return true; }
        if (!chans.length) return false;
        if (k === K.UP || k === K.DOWN || k === K.CHUP || k === K.CHDOWN) {
          var d = (k === K.UP || k === K.CHUP) ? -1 : 1;
          hi = (hi + d + chans.length) % chans.length; self.render(); self.schedule(); return true;
        }
        if (k === K.PGUP || k === K.PGDN || k === K.FF || k === K.RW) { hi = clamp(hi + ((k === K.PGUP || k === K.RW) ? -8 : 8), 0, chans.length - 1); self.render(); self.schedule(); return true; }
        if (k === K.OK) { show(Player("live", chans, hi, catName)); return true; }
        if (k === K.RED) { toggleFav("live", chans[hi]); self.render(); return true; }
        if (k === K.YELLOW) { show(Programme(chans[hi])); return true; }
        return false;
      }
    };
    return self;
  }
  var epgCache = {};
  function loadEpg(ch, cb) {
    var id = ch.stream_id, c = epgCache[id];
    if (c && c.t > now() - 300) return cb(c.l);
    api("get_short_epg", "&stream_id=" + id + "&limit=4", function (e, j) {
      var l = parseEpg(j).filter(function (p) { return p.e > now(); });
      epgCache[id] = { t: now(), l: l };
      cb(l);
    });
  }
  function parseEpg(j) {
    return ((j && j.epg_listings) || []).map(function (p) {
      return { title: b64(p.title), desc: b64(p.description), s: parseInt(p.start_timestamp, 10), e: parseInt(p.stop_timestamp || p.end_timestamp, 10),
        arc: String(p.has_archive) === "1" };
    }).filter(function (p) { return p.s && p.e; }).sort(function (a, b) { return a.s - b.s; });
  }

  // ---- programme of one channel, with catch-up (archive)
  function Programme(ch) {
    var l = [], i = 0, loading = true;
    var self = {
      enter: function () {
        if (!loading) return;
        api("get_simple_data_table", "&stream_id=" + ch.stream_id, function (e, j) {
          loading = false; l = parseEpg(j);
          var t = now(); i = 0;
          for (var x = 0; x < l.length; x++) if (l[x].s <= t) i = x;
          self.render();
        });
      },
      render: function () {
        var t = now(), p = l[i];
        $app.innerHTML = top("Programm", ch.name) + '<div class="cols"><div class="panel eps">' +
          (loading ? '<div class="empty">Lade …</div>' : !l.length ? '<div class="empty">Kein Programm verfügbar.</div>' :
            windowed(l, i, 9, function (x, n, s) {
              var past = x.e <= t, nowp = x.s <= t && x.e > t;
              return '<div class="ep' + (s ? " f" : "") + (past ? " past" : "") + (nowp ? " now" : "") + '"><span class="tm">' + dayhm(x.s) + '</span><span style="flex:1;overflow:hidden;text-overflow:ellipsis">' +
                esc(x.title) + '</span>' + (past && x.arc && +ch.tv_archive ? '<span class="arc">⏪ ansehen</span>' : "") + '</div>';
            })) + '</div><div class="side" style="flex:0 0 560px"><div class="panel epg">' +
          (p ? '<h4>' + dayhm(p.s) + ' – ' + hm(p.e) + '</h4><div class="title">' + esc(p.title) + '</div><div class="desc" style="max-height:600px">' + esc(p.desc) + '</div>' : "") +
          '</div></div></div><div class="hints"><span><b>OK</b> ' + (+ch.tv_archive ? "ansehen (live oder aus dem Archiv)" : "live ansehen") + '</span><span><b>Zurück</b> Senderliste</span></div>';
      },
      key: function (k) {
        if (k === K.UP) { i = Math.max(0, i - 1); self.render(); return true; }
        if (k === K.DOWN) { i = Math.min(l.length - 1, i + 1); self.render(); return true; }
        if (k === K.OK) {
          var p = l[i], t = now();
          if (p && p.e <= t && p.arc && +ch.tv_archive) {
            show(Player("archive", [{ name: ch.name + " · " + p.title, url: timeshiftUrl(ch, p), stream_icon: ch.stream_icon }], 0));
          } else show(Player("live", [ch], 0));
          return true;
        }
        return false;
      }
    };
    return self;
  }

  // ================================================================== movies / series grid
  function Grid(kind) {
    var cats = [], ci = 0, items = [], gi = 0, zone = "grid", loading = true, err = "", infoT = null, plot = {}, loadedCat = null;
    var COLS = 6, ROWS = 2;
    var self = {
      enter: function () {
        player.onState = null;
        if (cats.length) return;
        self.reloadCats();
      },
      reloadCats: function () {
        catList(kind, function (e, l) {
          var oldId = cats[ci] && cats[ci].id;
          cats = l; err = e || "";
          var want = oldId || sload(kind + ".cat", "*");
          ci = 3; for (var i = 0; i < cats.length; i++) if (cats[i].id === want) ci = i;
          self.loadCat();
        });
      },
      leave: function () { clearTimeout(infoT); },
      loadCat: function (force) {
        var c = cats[ci]; if (!c || c.id === "search") return;
        if (c.locked && !unlocked) { askPin(function () { self.loadCat(true); }); return; }
        if (!force && loadedCat === c.id + sload("sort." + kind, "")) { zone = "grid"; self.render(); return; }
        loading = true; items = []; gi = 0; self.render();
        if (!c.sp) ssave(kind + ".cat", c.id);
        entries(kind, c.id, function (e, l) {
          if (cats[ci] !== c) return;
          loading = false; err = e || ""; items = (c.id === "hist" || c.id === "fav") ? l : sortList(kind, l); gi = 0;
          loadedCat = c.id + sload("sort." + kind, "");
          zone = items.length ? "grid" : "cats";
          self.render(); self.details();
        });
      },
      render: function () {
        var x = items[gi];
        var startRow = Math.max(0, Math.floor(gi / COLS) - (ROWS - 1)), html = "";
        for (var i = startRow * COLS; i < Math.min(items.length, (startRow + ROWS + 1) * COLS); i++) {
          var it = items[i], r = parseFloat(it.rating);
          html += '<div class="poster' + (i === gi && zone === "grid" ? " f" : "") + '"><div class="img" style="background-image:' + cssUrl(iconOf(it)) + '">' +
            (r ? '<span class="badge">★ ' + r.toFixed(1) + '</span>' : "") + '</div><div class="pn">' + esc(it.name) + '</div></div>';
        }
        var meta = [];
        if (x) {
          if (x.year || x.releaseDate) meta.push(esc(x.year || String(x.releaseDate).slice(0, 4)));
          if (x.rating && x.rating !== "0") meta.push("★ " + esc(x.rating));
          if (x.genre) meta.push(esc(x.genre));
          if (x.duration) meta.push(esc(x.duration));
        }
        $app.innerHTML = top(KINDS[kind].title, cats[ci] ? cats[ci].name : "") + '<div class="cols">' +
          '<div class="panel cats">' + catHtml(cats, ci, zone === "cats", 11) + '</div><div class="gridwrap"><div class="panel info">' +
          (x ? img(iconOf(x)) + '<div style="min-width:0;flex:1"><h3>' + esc(x.name) + '</h3><div class="meta">' + meta.join(" · ") +
            (isFav(kind, x) ? ' · <span style="color:#fcd34d">★ Favorit</span>' : "") + '</div><div class="plot">' + esc(x.plot || plot[idOf(kind, x)] || "") + '</div></div>'
            : '<div class="empty">' + (loading ? "Lade …" : err ? esc(err) : "Keine Einträge in dieser Kategorie.") + '</div>') +
          '</div><div class="grid">' + html + '</div></div></div>' +
          '<div class="hints"><span><b>OK</b> ' + (kind === "series" ? "Staffeln" : "abspielen") + '</span><span><i class="key k-red"></i>Favorit</span>' +
          '<span><i class="key k-green"></i>Optionen / Sortierung</span><span><i class="key k-blue"></i>Suche</span><span class="muted">' + (items.length ? (gi + 1) + " / " + items.length : "") + '</span></div>';
      },
      details: function () {
        clearTimeout(infoT);
        var x = items[gi]; if (!x || kind !== "movie" || plot[idOf(kind, x)] != null) return;
        infoT = setTimeout(function () {
          api("get_vod_info", "&vod_id=" + x.stream_id, function (e, j) {
            var i = (j && j.info) || {};
            plot[idOf(kind, x)] = i.plot || i.description || "";
            if (!x.year && i.releasedate) x.year = String(i.releasedate).slice(0, 4);
            if (!x.genre && i.genre) x.genre = i.genre;
            if (!x.duration && i.duration) x.duration = i.duration;
            if (items[gi] === x && screen === self) self.render();
          });
        }, 600);
      },
      key: function (k) {
        if (k === K.GREEN) { catMenu(kind, zone === "cats" ? cats[ci] : null, function (changed) { if (changed) { cats = []; self.reloadCats(); } else { loadedCat = null; self.loadCat(); } }); return true; }
        if (zone === "cats") {
          if (k === K.UP) { ci = Math.max(0, ci - 1); self.render(); return true; }
          if (k === K.DOWN) { ci = Math.min(cats.length - 1, ci + 1); self.render(); return true; }
          if (k === K.PGUP || k === K.PGDN) { ci = clamp(ci + (k === K.PGUP ? -10 : 10), 0, cats.length - 1); self.render(); return true; }
          if (cats[ci] && cats[ci].id === "search" && (k === K.OK || k === K.RIGHT)) { show(Search()); return true; }
          if (k === K.OK || k === K.RIGHT) { self.loadCat(); return true; }
          return false;
        }
        if (!items.length) { if (k === K.LEFT) { zone = "cats"; self.render(); return true; } return false; }
        if (k === K.LEFT) { if (gi % COLS === 0) zone = "cats"; else gi--; }
        else if (k === K.RIGHT) { if (gi < items.length - 1) gi++; }
        else if (k === K.UP) { if (gi >= COLS) gi -= COLS; }
        else if (k === K.DOWN) { gi = Math.min(items.length - 1, gi + COLS); }
        else if (k === K.PGUP || k === K.PGDN || k === K.FF || k === K.RW) { gi = clamp(gi + ((k === K.PGUP || k === K.RW) ? -COLS * 3 : COLS * 3), 0, items.length - 1); }
        else if (k === K.RED) { toggleFav(kind, items[gi]); }
        else if (k === K.OK) {
          if (kind === "series") show(Series(items[gi]));
          else show(Player("movie", items, gi));
          return true;
        } else return false;
        self.render(); self.details(); return true;
      }
    };
    return self;
  }

  // ---- series: seasons | episodes
  function Series(s) {
    var seasons = [], si = 0, eps = {}, ei = 0, zone = "eps", loading = true, err = "", info = {};
    var self = {
      enter: function () {
        player.onState = null;
        if (!loading) { self.render(); return; }
        api("get_series_info", "&series_id=" + s.series_id, function (e, j) {
          loading = false; err = e || "";
          info = (j && j.info) || {};
          eps = (j && j.episodes) || {};
          if (Array.isArray(eps)) { var o = {}; eps.forEach(function (l, i) { o[String(i + 1)] = l; }); eps = o; }
          seasons = Object.keys(eps).sort(function (a, b) { return a - b; });
          // jump to the last watched episode
          var last = sload("lastep." + s.series_id, null);
          if (last) { for (var a = 0; a < seasons.length; a++) if (seasons[a] === last.season) { si = a; ei = last.idx || 0; } }
          self.render();
        });
      },
      list: function () { return eps[seasons[si]] || []; },
      render: function () {
        var l = self.list(), watched = sload("watched." + s.series_id, {});
        $app.innerHTML = top("Serien", s.name) + '<div class="cols"><div class="panel seasons">' +
          (loading ? '<div class="empty">Lade …</div>' : err ? '<div class="empty red">' + esc(err) + '</div>' :
            windowed(seasons, si, 10, function (x, i, sel) { return '<div class="cat' + (sel ? (zone === "seasons" ? " f sel" : " sel") : "") + '">Staffel ' + esc(x) + '</div>'; })) +
          '</div><div class="panel eps">' +
          windowed(l, ei, 9, function (x, i, sel) {
            var pos = cwPos("ep:" + x.id);
            return '<div class="ep' + (sel && zone === "eps" ? " f" : "") + '"><span class="tm">' + esc("E" + (x.episode_num || i + 1)) + '</span><span style="flex:1;overflow:hidden;text-overflow:ellipsis">' +
              esc(x.title || "") + '</span>' + (watched[x.id] ? '<span class="arc">✓ gesehen</span>' : pos ? '<span class="arc">▶ weiter</span>' : "") + '</div>';
          }) + '</div><div class="side" style="flex:0 0 520px"><div class="panel epg">' + img(s.cover) +
          '<div class="title" style="margin-top:16px">' + esc(s.name) + '</div><div class="desc" style="max-height:330px">' + esc(info.plot || s.plot || "") + '</div></div></div></div>' +
          '<div class="hints"><span><b>OK</b> abspielen</span><span><b>◀</b> Staffeln</span><span><i class="key k-red"></i>Favorit</span><span><b>Zurück</b> Serien</span></div>';
        var im = $app.querySelector(".epg img"); if (im) { im.style.width = "100%"; im.style.height = "300px"; im.style.objectFit = "cover"; im.style.borderRadius = "18px"; }
      },
      key: function (k) {
        if (k === K.RED) { toggleFav("series", s); return true; }
        if (zone === "seasons") {
          if (k === K.UP) si = Math.max(0, si - 1);
          else if (k === K.DOWN) si = Math.min(seasons.length - 1, si + 1);
          else if (k === K.RIGHT || k === K.OK) { zone = "eps"; ei = 0; }
          else return false;
          self.render(); return true;
        }
        var l = self.list();
        if (k === K.LEFT) zone = "seasons";
        else if (k === K.UP) ei = Math.max(0, ei - 1);
        else if (k === K.DOWN) ei = Math.min(l.length - 1, ei + 1);
        else if (k === K.OK && l[ei]) {
          addHist("series", s);
          ssave("lastep." + s.series_id, { season: seasons[si], idx: ei });
          // the next seasons follow on, so "next episode" keeps going
          var all = [], start = 0;
          seasons.forEach(function (sn, n) { if (n === si) start = all.length + ei; if (n >= si) all = all.concat(eps[sn] || []); });
          show(Player("episode", all, start, s.name, s));
          return true;
        } else return false;
        self.render(); return true;
      }
    };
    return self;
  }

  // ================================================================== full-screen player
  function Player(kind, list, idx, sub, seriesObj) {
    var osdT = null, osd = true, epg = null, state = "buffer", tick = null, listOpen = false, li = idx, subText = "", lastLive = load("lastlive", null);
    var e = list[idx];
    var num = NumInput(function (n) { if (kind === "live" && list[n - 1]) { idx = n - 1; self.start(); } else toast("Sender " + n + " gibt es hier nicht"); });
    var self = {
      enter: function () {
        document.body.classList.add("playing");
        bgHole(null);
        player.onState = function (s) {
          state = s;
          if (s === "error") toast("Wiedergabe fehlgeschlagen");
          if (s === "end" && kind === "episode" && idx < list.length - 1) { self.markWatched(); idx++; toast("Nächste Folge"); self.start(); return; }
          if (s === "end" && kind !== "live") { self.markWatched(); back(); return; }
          self.render();
        };
        player.onSub = function (t) { subText = t || ""; var el = document.getElementById("subs"); if (el) el.innerHTML = esc(subText).replace(/\n/g, "<br>"); };
        self.start();
        tick = setInterval(function () { if (osd && kind !== "live" && !overlay) self.render(); }, 1000);
      },
      leave: function () {
        self.savePos();
        clearTimeout(osdT); clearInterval(tick); player.onState = null; player.onSub = null; player.stop();
        document.body.classList.remove("playing");
      },
      cwKey: function () { return kind === "movie" ? "mv:" + e.stream_id : "ep:" + e.id; },
      start: function () {
        e = list[idx]; state = "buffer"; epg = null; subText = "";
        if (kind === "live") {
          var cur = load("curlive", null);
          if (cur && cur.stream_id !== e.stream_id) { lastLive = cur; save("lastlive", cur); }
          save("curlive", slim("live", e));
          addHist("live", e);
          loadEpg(e, function (l) { if (list[idx] === e) { epg = l; self.render(); } });
        }
        if (kind === "movie") addHist("movie", e);
        var pos = (kind === "movie" || kind === "episode") ? cwPos(self.cwKey()) : 0;
        player.play(streamUrl(kind === "episode" ? "series" : kind, e), 0, 0, 1920, 1080, pos > 30000 ? pos - 5000 : 0);
        if (pos > 30000) toast("Weiter ab " + Math.floor(pos / 60000) + " min");
        self.showOsd();
      },
      savePos: function () {
        if (kind !== "movie" && kind !== "episode") return;
        var t = player.time(), d = player.duration();
        if (!d) return;
        var item = kind === "movie" ? { key: self.cwKey(), kind: "movie", title: e.name, icon: iconOf(e), e: slim("movie", e) }
          : { key: self.cwKey(), kind: "episode", title: sub + " · " + (e.title || ""), icon: seriesObj ? seriesObj.cover : "", e: e, list: list, idx: idx, series: sub, seriesObj: seriesObj ? slim("series", seriesObj) : null };
        if (item.list && item.list.length > 60) { item.list = item.list.slice(idx, idx + 60); item.idx = 0; }
        cwSave(item, t, d);
        if (d && t > d - 90000) self.markWatched();
      },
      markWatched: function () {
        if (kind !== "episode" || !seriesObj) return;
        var w = sload("watched." + seriesObj.series_id, {}); w[e.id] = 1; ssave("watched." + seriesObj.series_id, w);
      },
      showOsd: function () {
        osd = true; self.render();
        clearTimeout(osdT); osdT = setTimeout(function () { osd = false; if (!overlay) self.render(); }, 5000);
      },
      render: function () {
        var h = '<div class="pclock">' + clock() + '</div>' + (sleepAt ? '<div class="sleep">Sleep in ' + Math.ceil((sleepAt - now()) / 60) + ' min</div>' : "") +
          '<div class="subs" id="subs">' + esc(subText) + '</div>';
        if (state === "buffer") h += '<div class="spinner">Lädt …</div>';
        if (listOpen) {
          h += '<div class="chlist"><h3>' + esc(sub || "Sender") + '</h3>' + windowed(list, li, 9, function (x, i, s) {
            return '<div class="ch' + (s ? " f" : "") + (i === idx ? " playing" : "") + '"><span class="no">' + (i + 1) + '</span>' + img(x.stream_icon) + '<div class="t"><div class="n">' + esc(x.name) + '</div></div></div>';
          }) + '</div>';
        } else if (osd) {
          if (kind === "live") {
            var a = epg && epg[0], b = epg && epg[1];
            h += '<div class="osd"><div class="num">' + (idx + 1) + '</div>' + img(e.stream_icon) +
              '<div class="t"><div class="n">' + esc(e.name) + (isFav("live", e) ? ' <span style="color:#fcd34d">★</span>' : "") + '</div>' +
              (a ? '<div class="now">' + hm(a.s) + ' &nbsp;' + esc(a.title) + ' &nbsp;<span class="muted">' + hm(a.e) + '</span></div>' +
                '<div class="bar"><i style="width:' + clamp((now() - a.s) / Math.max(1, a.e - a.s) * 100, 0, 100) + '%"></i></div>' : '<div class="now muted">' + esc(sub || "") + '</div>') +
              (b ? '<div class="next">Danach ' + hm(b.s) + ' · ' + esc(b.title) + '</div>' : "") + '</div>' +
              '<div class="help"><b>▲▼ / CH±</b> Sender · <b>0–9</b> Nummer<br><b>OK</b> Senderliste · <b>◀</b> letzter Sender<br><i class="key k-green"></i>Optionen · <i class="key k-yellow"></i>Programm</div></div>';
          } else {
            var t = player.time() / 1000, d = player.duration() / 1000;
            var f = function (x) { x = Math.max(0, Math.floor(x)); return Math.floor(x / 3600) + ":" + pad(Math.floor(x / 60) % 60) + ":" + pad(x % 60); };
            h += '<div class="osd"><div class="t"><div class="n">' + esc(kind === "episode" ? (sub + " · " + (e.title || "")) : e.name) + '</div>' +
              '<div class="bar"><i style="width:' + (d ? clamp(t / d * 100, 0, 100) : 0) + '%"></i></div>' +
              '<div class="next">' + f(t) + ' / ' + (d ? f(d) : "–") + (player.paused() ? " · Pause" : "") + (d ? " · Ende um " + hm(now() + (d - t)) : "") + '</div></div>' +
              '<div class="help"><b>◀ ▶</b> 10 s · <b>▲ ▼</b> 1 min<br><b>OK</b> Pause / weiter<br><i class="key k-green"></i>Optionen' + (kind === "episode" ? " · <b>CH±</b> Folge" : "") + '</div></div>';
          }
        }
        $app.innerHTML = h;
      },
      zap: function (d) {
        if (list.length < 2) return;
        idx = (idx + d + list.length) % list.length; self.start();
      },
      options: function () {
        var items = [];
        var aud = player.tracks("AUDIO"), txt = player.tracks("TEXT");
        if (aud.length > 1) items.push({ label: "Tonspur", value: function () { return aud[(load("aud", 0)) % aud.length].label; }, keep: true,
          action: function () { var i = (load("aud", 0) + 1) % aud.length; save("aud", i); player.selectTrack("AUDIO", aud[i].index); overlay.render(); } });
        if (txt.length) items.push({ label: "Untertitel", value: function () { var i = load("sub", -1); return i < 0 ? "Aus" : txt[i % txt.length].label; }, keep: true,
          action: function () { var i = load("sub", -1) + 1; if (i >= txt.length) i = -1; save("sub", i); if (i < 0) player.subtitles(false); else { player.selectTrack("TEXT", txt[i].index); player.subtitles(true); } overlay.render(); } });
        items.push({ label: "Bildformat", value: function () { return MODES[load("mode", 0)][1]; }, keep: true,
          action: function () { player.setMode((load("mode", 0) + 1) % MODES.length); overlay.render(); } });
        items.push({ label: "Sleep-Timer", value: function () { return sleepAt ? Math.ceil((sleepAt - now()) / 60) + " min" : "Aus"; }, keep: true,
          action: function () { var steps = [0, 30, 60, 90, 120]; sleepStep = (sleepStep + 1) % steps.length; sleepAt = steps[sleepStep] ? now() + steps[sleepStep] * 60 : 0; overlay.render(); } });
        if (kind === "live") {
          items.push({ label: isFav("live", e) ? "Aus Favoriten entfernen" : "Zu Favoriten", action: function () { toggleFav("live", e); } });
          items.push({ label: "Programm & Archiv", action: function () { show(Programme(e)); } });
        } else {
          items.push({ label: "Von vorne starten", action: function () { player.seekTo(0); } });
          if (kind === "episode" && idx < list.length - 1) items.push({ label: "Nächste Folge", action: function () { self.markWatched(); idx++; self.start(); } });
        }
        openOverlay(Menu("Optionen", items, function () { if (screen === self) self.render(); }));
      },
      key: function (k) {
        if (k === K.GREEN) { self.options(); return true; }
        if (kind === "live") {
          if (num(k)) return true;
          if (listOpen) {
            if (k === K.UP) { li = (li - 1 + list.length) % list.length; self.render(); return true; }
            if (k === K.DOWN) { li = (li + 1) % list.length; self.render(); return true; }
            if (k === K.OK) { listOpen = false; if (li !== idx) { idx = li; self.start(); } else self.render(); return true; }
            if (k === K.LEFT || k === K.RIGHT) { listOpen = false; self.render(); return true; }
            return true;
          }
          if (k === K.UP || k === K.CHUP) { self.zap(-1); return true; }
          if (k === K.DOWN || k === K.CHDOWN) { self.zap(1); return true; }
          if (k === K.OK) { listOpen = true; li = idx; self.render(); return true; }
          if (k === K.INFO) { if (osd) { osd = false; self.render(); } else self.showOsd(); return true; }
          if (k === K.LEFT || k === K.PREVCH) {
            if (!lastLive) { toast("Noch kein vorheriger Sender"); return true; }
            var pos = -1; list.forEach(function (x, i) { if (String(x.stream_id) === String(lastLive.stream_id)) pos = i; });
            if (pos < 0) { list = [lastLive].concat(list); pos = 0; idx++; }
            idx = pos; self.start(); return true;
          }
          if (k === K.RED) { toggleFav("live", e); self.showOsd(); return true; }
          if (k === K.YELLOW) { show(Programme(e)); return true; }
          return false;
        }
        if (k === K.OK || k === K.PLAYPAUSE || k === K.PLAY || k === K.PAUSE) { player.toggle(); self.showOsd(); return true; }
        if (k === K.RIGHT || k === K.FF) { player.seek(k === K.FF ? 60000 : 10000); self.showOsd(); return true; }
        if (k === K.LEFT || k === K.RW) { player.seek(k === K.RW ? -60000 : -10000); self.showOsd(); return true; }
        if (k === K.UP) { player.seek(60000); self.showOsd(); return true; }
        if (k === K.DOWN) { player.seek(-60000); self.showOsd(); return true; }
        if (kind === "episode" && (k === K.CHUP || k === K.CHDOWN)) { self.savePos(); idx = clamp(idx + (k === K.CHUP ? 1 : -1), 0, list.length - 1); self.start(); return true; }
        if (k === K.INFO) { self.showOsd(); return true; }
        if (k === K.STOP) { back(); return true; }
        return false;
      },
      beforeBack: function () { if (listOpen) { listOpen = false; self.render(); return true; } return false; }
    };
    return self;
  }

  // ================================================================== search (everything)
  var allLists = null, allWait = [];
  function loadAll(cb) {
    if (allLists) return cb();
    allWait.push(cb); if (allWait.length > 1) return;
    var res = {}, left = 3;
    ["live", "movie", "series"].forEach(function (k) {
      entries(k, "*", function (e, l) {
        res[k] = l || [];
        if (--left === 0) { allLists = res; var w = allWait; allWait = []; w.forEach(function (f) { f(); }); }
      });
    });
  }
  function Search() {
    var q = load("search.q", ""), res = [], ri = -1, busy = false, typeT = null;
    var self = {
      isSearch: true,
      enter: function () { player.onState = null; },
      render: function () { // the text field is drawn once; only the results are redrawn
        $app.innerHTML = top("Suche", "Sender, Filme und Serien – in allen Kategorien") +
          '<div style="padding:0 84px"><input class="inp" id="q" placeholder="Suchbegriff eingeben" value="' + esc(q) + '"><div class="panel" id="res" style="height:690px"></div></div>' +
          '<div class="hints" id="sh"></div>';
        var el = document.getElementById("q");
        el.addEventListener("input", function () { q = el.value; clearTimeout(typeT); typeT = setTimeout(self.run, 450); });
        self.results();
        if (ri < 0) { el.focus(); try { el.setSelectionRange(el.value.length, el.value.length); } catch (x) {} }
        if (q && !res.length) self.run();
      },
      results: function () {
        var el = document.getElementById("q"), box = document.getElementById("res"), hint = document.getElementById("sh");
        if (!box) return;
        el.classList.toggle("f", ri < 0);
        box.innerHTML = busy ? '<div class="empty">Suche in allen Kategorien … (beim ersten Mal dauert es etwas)</div>' :
          !res.length ? '<div class="empty">' + (q.trim().length > 1 ? "Nichts gefunden." : "Mindestens 2 Buchstaben eingeben.") + '</div>' :
          windowed(res, Math.max(ri, 0), 7, function (x, i, s) {
            return '<div class="ch' + (s && ri >= 0 ? " f" : "") + '">' + img(iconOf(x.e)) + '<div class="t"><div class="n">' + esc(x.e.name) + '</div><div class="s">' + x.label + '</div></div></div>';
          });
        hint.innerHTML = '<span><b>▼</b> zu den Ergebnissen</span><span><b>OK</b> öffnen</span><span><i class="key k-red"></i>Favorit</span><span><b>Zurück</b> ' +
          (ri >= 0 ? "zum Suchfeld" : "zurück") + '</span>' + (res.length ? '<span class="blue">' + res.length + (res.length >= 300 ? "+" : "") + ' Treffer</span>' : "");
      },
      run: function () {
        var el = document.getElementById("q"); if (el) q = el.value;
        var qq = q.trim(); save("search.q", qq);
        if (qq.length < 2) { res = []; self.results(); return; }
        if (!allLists) { busy = true; self.results(); }
        loadAll(function () {
          var words = qq.toLowerCase().split(/\s+/); res = [];
          [["live", "Live-TV"], ["movie", "Film"], ["series", "Serie"]].forEach(function (p) {
            var l = allLists[p[0]];
            for (var i = 0; i < l.length && res.length < 300; i++) {
              var n = String(l[i].name || "").toLowerCase(), ok = true;
              for (var w = 0; w < words.length; w++) if (n.indexOf(words[w]) < 0) { ok = false; break; }
              if (ok) res.push({ kind: p[0], e: l[i], label: p[1] });
            }
          });
          busy = false; if (ri >= res.length) ri = res.length - 1; self.results();
        });
      },
      key: function (k) {
        var el = document.getElementById("q");
        if (ri < 0) {
          if (k === K.OK) { clearTimeout(typeT); self.run(); return true; }
          if (k === K.DOWN && res.length) { ri = 0; el.blur(); self.results(); return true; }
          return false;
        }
        if (k === K.UP) { ri--; if (ri < 0) { el.focus(); } self.results(); return true; }
        if (k === K.DOWN) { ri = Math.min(res.length - 1, ri + 1); self.results(); return true; }
        if (k === K.OK) {
          var r = res[ri], same = res.filter(function (x) { return x.kind === r.kind; }).map(function (x) { return x.e; });
          if (r.kind === "series") show(Series(r.e)); else show(Player(r.kind, same, same.indexOf(r.e), "Suche"));
          return true;
        }
        if (k === K.RED) { toggleFav(res[ri].kind, res[ri].e); return true; }
        return false;
      },
      beforeBack: function () { if (ri >= 0) { ri = -1; var el = document.getElementById("q"); el.focus(); self.results(); return true; } return false; }
    };
    return self;
  }

  // ================================================================== accounts
  function Accounts() {
    var f = 0, info = null;
    var self = {
      enter: function () {
        info = null;
        api(null, "", function (e, j) { info = (j && j.user_info) || {}; if (screen === self) self.render(); });
      },
      rows: function () {
        var r = [];
        accs.forEach(function (a, i) { r.push({ label: (i === cur ? "● " : "○ ") + (a.name || a.user) + "  ·  " + a.server, value: i === cur ? "aktiv" : "wechseln", act: function () { switchAcc(i); } }); });
        r.push({ label: "+ Konto hinzufügen", value: "", act: function () { show(Login("", true)); } });
        if (accs.length > 1) r.push({ label: "Aktives Konto entfernen", value: "", act: function () {
          accs.splice(cur, 1); cur = 0; save("accs", accs); save("cur", 0); switchAcc(0);
        } });
        r.push({ label: "Anmeldedaten ändern", value: "", act: function () { show(Login()); } });
        return r;
      },
      render: function () {
        var r = self.rows(), i = info || {};
        var exp = i.exp_date && i.exp_date !== "null" ? new Date(parseInt(i.exp_date, 10) * 1000) : null;
        $app.innerHTML = top("Konto") + '<div style="padding:0 84px">' +
          '<div class="panel" style="display:flex;gap:60px;font-size:28px;padding:30px 36px;margin-bottom:20px">' +
          (info ? '<span>Status: <b class="blue">' + esc(i.status || "–") + '</b></span><span>Gültig bis: <b class="blue">' + (exp ? pad(exp.getDate()) + "." + pad(exp.getMonth() + 1) + "." + exp.getFullYear() : "unbegrenzt") +
            '</b></span><span>Verbindungen: <b class="blue">' + esc((i.active_cons || "0") + " / " + (i.max_connections || "–")) + '</b></span>' : '<span class="muted">Lade Kontodaten …</span>') + '</div>' +
          r.map(function (x, n) { return '<div class="row' + (n === f ? " f" : "") + '"><span>' + esc(x.label) + '</span><span class="blue">' + esc(x.value) + '</span></div>'; }).join("") +
          '</div><div class="hints"><span><b>OK</b> auswählen</span><span><b>Zurück</b> Startseite</span></div>';
      },
      key: function (k) {
        var r = self.rows();
        if (k === K.UP) { f = Math.max(0, f - 1); self.render(); return true; }
        if (k === K.DOWN) { f = Math.min(r.length - 1, f + 1); self.render(); return true; }
        if (k === K.OK) { r[f].act(); return true; }
        return false;
      }
    };
    return self;
  }
  function switchAcc(i) {
    if (!accs[i]) { stack = []; show(Login()); return; }
    cur = i; save("cur", i); acc = accs[i]; cache = {}; allLists = null; epgCache = {}; unlocked = false;
    stack = []; show(Home()); toast("Konto: " + (acc.name || acc.user));
  }

  // ================================================================== settings
  function Settings() {
    var f = 0, items;
    var self = {
      render: function () {
        var nh = hidden("live").length + hidden("movie").length + hidden("series").length;
        items = [
          { l: "Vorschau beim Blättern im Live-TV", v: load("preview", true) ? "an" : "aus", a: function () { save("preview", !load("preview", true)); } },
          { l: "Bildformat", v: MODES[load("mode", 0)][1], a: function () { save("mode", (load("mode", 0) + 1) % MODES.length); } },
          { l: "Ausgeblendete Kategorien wieder zeigen", v: nh + " ausgeblendet", a: function () { ["live", "movie", "series"].forEach(function (x) { ssave("hidden." + x, []); delete cache[x + ":cats"]; }); toast("Alle Kategorien wieder sichtbar"); } },
          { l: "PIN für gesperrte Kategorien", v: pinSet() ? "ändern" : "festlegen", a: function () { if (pinSet()) askPin(function () { setPin(); }); else setPin(); } },
          { l: "Gesperrte Kategorien jetzt wieder sperren", v: unlocked ? "entsperrt" : "gesperrt", a: function () { unlocked = false; cache = {}; allLists = null; toast("Wieder gesperrt"); } },
          { l: "Favoriten, Verlauf und Weiterschauen löschen", v: "", a: function () { ["live", "movie", "series"].forEach(function (x) { ssave("fav." + x, []); ssave("hist." + x, []); }); ssave("cw", []); toast("Gelöscht"); } },
          { l: "Senderdaten neu laden", v: "", a: function () { cache = {}; allLists = null; epgCache = {}; toast("Wird beim nächsten Öffnen neu geladen"); } },
          { l: "Version", v: "rustTV für Fernseher " + VERSION, a: function () {} },
          { l: "‹ Zurück zur Startseite", v: "", a: function () { back(); } }
        ];
        $app.innerHTML = top("Einstellungen") + '<div style="padding:0 84px">' + windowed(items, f, 9, function (x, i, s) {
          return '<div class="row' + (s ? " f" : "") + '"><span>' + x.l + '</span><span class="blue">' + esc(x.v) + '</span></div>';
        }) + '</div><div class="hints"><span><b>OK</b> ändern</span><span><b>Zurück</b> Startseite</span></div>';
      },
      key: function (k) {
        if (k === K.UP) { f = Math.max(0, f - 1); self.render(); return true; }
        if (k === K.DOWN) { f = Math.min(items.length - 1, f + 1); self.render(); return true; }
        if (k === K.OK) { var it = items[f]; it.a(); if (screen === self && !overlay) self.render(); return true; }
        return false;
      }
    };
    return self;
  }

  // ================================================================== key dispatch
  var lastBack = 0;
  function goBack() {
    if (Date.now() - lastBack < 250) return; // one press can arrive as two events
    lastBack = Date.now();
    if (overlay) { closeOverlay(); return; }
    try { if (screen && screen.beforeBack && screen.beforeBack() === true) return; } catch (e) {}
    try { back(); } catch (e) { stack = []; show(Home()); }
  }
  document.addEventListener("keydown", function (ev) {
    var k = ev.keyCode;
    var typing = document.activeElement && document.activeElement.tagName === "INPUT";
    if (k === K.BACK || k === K.ESC || (k === K.BS && !typing)) {
      ev.preventDefault(); ev.stopPropagation();
      if (typing && screen && !screen.isSearch) document.activeElement.blur();
      goBack();
      return;
    }
    if (overlay) { ev.preventDefault(); overlay.key(k); return; }
    if (k === K.BLUE && screen && !screen.isSearch && acc) { ev.preventDefault(); show(Search()); return; }
    if (typing && (k === K.LEFT || k === K.RIGHT || (k >= 48 && k <= 90) || k === K.BS || k === 32 || (k >= 96 && k <= 111) || k > 180 && k < 230)) return;
    try { if (screen && screen.key && screen.key(k)) ev.preventDefault(); } catch (e) { toast("Fehler: " + e.message); }
  }, true);
  window.addEventListener("tizenhwkey", function (ev) { if (ev.keyName === "back") goBack(); });

  // start
  if (acc && acc.server) show(Home()); else show(Login());
})();
