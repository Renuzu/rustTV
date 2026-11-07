/* rustTV for TVs (Samsung Tizen).
 * Plain JavaScript, no framework: lists only draw the rows you can see,
 * data is loaded per category, video uses Samsung's own player (AVPlay). */
(function () {
  "use strict";

  // ------------------------------------------------------------------ keys
  var K = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, OK: 13, BACK: 10009, ESC: 27, BS: 8,
    RED: 403, GREEN: 404, YELLOW: 405, BLUE: 406, CHUP: 427, CHDOWN: 428,
    PLAYPAUSE: 10252, PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, RW: 412 };
  try {
    ["ColorF0Red", "ColorF1Green", "ColorF2Yellow", "ColorF3Blue", "ChannelUp", "ChannelDown",
     "MediaPlayPause", "MediaPlay", "MediaPause", "MediaStop", "MediaFastForward", "MediaRewind",
     "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"].forEach(function (k) {
      try { tizen.tvinputdevice.registerKey(k); } catch (e) {}
    });
  } catch (e) {}

  // ------------------------------------------------------------------ helpers
  var $app = document.getElementById("app");
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function load(k, d) { try { var v = localStorage.getItem("rtv." + k); return v ? JSON.parse(v) : d; } catch (e) { return d; } }
  function save(k, v) { try { localStorage.setItem("rtv." + k, JSON.stringify(v)); } catch (e) {} }
  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function hm(ts) { var d = new Date(ts * 1000); return pad(d.getHours()) + ":" + pad(d.getMinutes()); }
  function now() { return Math.floor(Date.now() / 1000); }
  function clock() { var d = new Date(); return pad(d.getHours()) + ":" + pad(d.getMinutes()); }
  function b64(s) { try { return decodeURIComponent(escape(atob(s || ""))); } catch (e) { return s || ""; } }
  var toastT = null;
  function toast(msg) {
    var t = document.getElementById("toast");
    t.textContent = msg; t.style.display = "block";
    clearTimeout(toastT); toastT = setTimeout(function () { t.style.display = "none"; }, 2500);
  }
  function img(u, cls) { return u ? '<img class="' + (cls || "") + '" src="' + esc(u) + '" onerror="this.style.visibility=\'hidden\'">' : '<div class="noimg"></div>'; }
  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }

  // ------------------------------------------------------------------ provider (Xtream Codes)
  var acc = load("acc", null);
  function base() {
    var u = (acc.server || "").trim();
    if (!/^https?:\/\//i.test(u)) u = "http://" + u;
    return u.replace(/\/(player_api\.php|get\.php)?\/*$/, "");
  }
  function api(action, extra, cb) {
    var url = base() + "/player_api.php?username=" + encodeURIComponent(acc.user) + "&password=" + encodeURIComponent(acc.pass) +
      (action ? "&action=" + action : "") + (extra || "");
    var x = new XMLHttpRequest();
    x.open("GET", url); x.timeout = 30000;
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
  function idOf(kind, e) { return String(kind === "series" ? e.series_id : e.stream_id); }
  function iconOf(e) { return e.stream_icon || e.cover || ""; }
  function streamUrl(kind, e) {
    var u = encodeURIComponent(acc.user), p = encodeURIComponent(acc.pass);
    if (kind === "live") return base() + "/live/" + u + "/" + p + "/" + e.stream_id + ".ts";
    if (kind === "movie") return base() + "/movie/" + u + "/" + p + "/" + e.stream_id + "." + (e.container_extension || "mp4");
    return base() + "/series/" + u + "/" + p + "/" + e.id + "." + (e.container_extension || "mp4");
  }
  // entries of one category ("*" = all); favourites / history live in local storage
  function entries(kind, cat, cb) {
    if (cat === "fav") return cb(null, favs(kind));
    if (cat === "hist") return cb(null, load("hist." + kind, []));
    cached(kind + ":" + cat, KINDS[kind].list, cat === "*" ? "" : "&category_id=" + encodeURIComponent(cat), function (e, list) {
      if (e) return cb(e, []);
      if (cat !== "*") list = list.filter(function (x) { return String(x.category_id) === String(cat); });
      cb(null, list);
    });
  }
  function slim(kind, e) { // what we keep of an entry for favourites / history
    var o = { name: e.name, stream_icon: e.stream_icon, cover: e.cover, category_id: e.category_id, container_extension: e.container_extension };
    if (kind === "series") o.series_id = e.series_id; else o.stream_id = e.stream_id;
    return o;
  }
  function favs(kind) { return load("fav." + kind, []); }
  function isFav(kind, e) { var id = idOf(kind, e); return favs(kind).some(function (x) { return idOf(kind, x) === id; }); }
  function toggleFav(kind, e) {
    var id = idOf(kind, e), l = favs(kind), had = l.some(function (x) { return idOf(kind, x) === id; });
    l = l.filter(function (x) { return idOf(kind, x) !== id; });
    if (!had) l.unshift(slim(kind, e));
    save("fav." + kind, l);
    toast(had ? "Aus Favoriten entfernt" : "Zu Favoriten hinzugefügt");
  }
  function addHist(kind, e) {
    var id = idOf(kind, e), l = load("hist." + kind, []).filter(function (x) { return idOf(kind, x) !== id; });
    l.unshift(slim(kind, e)); save("hist." + kind, l.slice(0, 40));
  }

  // ------------------------------------------------------------------ video (AVPlay on Samsung, <video> elsewhere)
  var hasAv = false;
  try { hasAv = !!(window.webapis && webapis.avplay); } catch (e) {}
  var avObj = document.getElementById("av"), vid = document.getElementById("vid");
  if (hasAv) document.documentElement.classList.add("tv");
  var player = {
    url: null, onState: null,
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
          try { av.setDisplayMethod("PLAYER_DISPLAY_MODE_LETTER_BOX"); } catch (e) {}
          av.setListener({
            onbufferingstart: function () { self.state("buffer"); },
            onbufferingcomplete: function () { self.state("play"); },
            onstreamcompleted: function () { self.state("end"); },
            onerror: function (e) { self.state("error", e); }
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
      if (hasAv) { try { var s = webapis.avplay.getState(); if (s === "PLAYING") webapis.avplay.pause(); else webapis.avplay.play(); } catch (e) {} }
      else { if (vid.paused) vid.play(); else vid.pause(); }
    },
    paused: function () { if (hasAv) { try { return webapis.avplay.getState() === "PAUSED"; } catch (e) { return false; } } return vid.paused; },
    time: function () { if (hasAv) { try { return webapis.avplay.getCurrentTime(); } catch (e) { return 0; } } return (vid.currentTime || 0) * 1000; },
    duration: function () { if (hasAv) { try { return webapis.avplay.getDuration(); } catch (e) { return 0; } } return (vid.duration || 0) * 1000; },
    seek: function (ms) {
      if (hasAv) { try { if (ms > 0) webapis.avplay.jumpForward(ms); else webapis.avplay.jumpBackward(-ms); } catch (e) {} }
      else vid.currentTime = Math.max(0, vid.currentTime + ms / 1000);
    },
    state: function (s, e) { if (this.onState) this.onState(s, e); }
  };

  // ------------------------------------------------------------------ screens
  var stack = [], screen = null;
  function show(s, replace) {
    if (screen && screen.leave) screen.leave();
    if (replace) stack.pop();
    stack.push(s); screen = s;
    if (s.enter) s.enter();
    s.render();
  }
  function back() {
    if (stack.length <= 1) { try { tizen.application.getCurrentApplication().exit(); } catch (e) {} return; }
    if (screen.leave) screen.leave();
    stack.pop(); screen = stack[stack.length - 1];
    if (screen.enter) screen.enter();
    screen.render();
  }
  function top(title, sub) {
    return '<div class="top"><div class="crumb">' + esc(title) + (sub ? ' <span>› ' + esc(sub) + '</span>' : "") + '</div>' +
      '<div class="topr"><span class="clock">' + clock() + '</span></div></div>';
  }
  setInterval(function () { var c = document.querySelector(".clock, .pclock"); if (c) c.textContent = clock(); }, 20000);

  // ---- login
  function Login(msg) {
    var f = 0, a = acc || { name: "", server: "", user: "", pass: "" };
    return {
      render: function () {
        $app.innerHTML = '<div class="form"><h1>rust<b>TV</b></h1><p>Melde dich mit den Daten deines IPTV-Anbieters an (Xtream Codes).</p>' +
          '<input class="inp" id="i0" placeholder="Server, z. B. http://anbieter.tv:8080" value="' + esc(a.server) + '">' +
          '<input class="inp" id="i1" placeholder="Benutzername" value="' + esc(a.user) + '">' +
          '<input class="inp" id="i2" type="password" placeholder="Passwort" value="' + esc(a.pass) + '">' +
          '<div class="err" id="err">' + esc(msg || "") + '</div><div class="btn" id="i3">Anmelden</div></div>';
        this.focus();
      },
      focus: function () {
        for (var i = 0; i < 4; i++) document.getElementById("i" + i).classList.toggle("f", i === f);
        var el = document.getElementById("i" + f);
        if (f < 3) el.focus(); else document.activeElement && document.activeElement.blur && document.activeElement.blur();
      },
      key: function (k) {
        if (k === K.DOWN) { f = Math.min(3, f + 1); this.focus(); return true; }
        if (k === K.UP) { f = Math.max(0, f - 1); this.focus(); return true; }
        if (k === K.OK && f === 3) { this.submit(); return true; }
        if (k === K.OK && f < 3) { f++; this.focus(); return true; }
        return false;
      },
      submit: function () {
        var v = function (i) { return document.getElementById("i" + i).value.trim(); };
        var cand = { server: v(0), user: v(1), pass: v(2) };
        if (!cand.server || !cand.user) { document.getElementById("err").textContent = "Bitte Server und Benutzername eingeben."; return; }
        document.getElementById("err").textContent = "Prüfe Anmeldung …";
        var old = acc; acc = cand; cache = {};
        api(null, "", function (e, j) {
          var ui = j && j.user_info;
          if (e || !ui || String(ui.auth) !== "1") {
            acc = old;
            document.getElementById("err").textContent = e || "Benutzername oder Passwort falsch.";
            return;
          }
          cand.exp = ui.exp_date || "";
          acc = cand; save("acc", acc);
          stack = []; show(Home());
        });
      }
    };
  }

  // ---- home
  function Home() {
    var f = 0; // 0..2 big tiles, 3..6 small row
    function exp() {
      if (!acc.exp || acc.exp === "null") return "";
      var d = new Date(parseInt(acc.exp, 10) * 1000);
      return " · gültig bis " + pad(d.getDate()) + "." + pad(d.getMonth() + 1) + "." + d.getFullYear();
    }
    return {
      render: function () {
        $app.innerHTML = '<div class="top"><div class="logo">rust<b>TV</b></div><div class="topr"><span>' + esc(acc.user) + esc(exp()) + '</span><span class="clock">' + clock() + '</span></div></div>' +
          '<div class="home-main">' +
          '<div class="tile live" data-i="0"><h2>Live-TV</h2><p>' + "Sender &amp; Programm" + '</p></div>' +
          '<div class="tile" data-i="1"><h2>Filme</h2><p class="muted">' + "nach Kategorien" + '</p></div>' +
          '<div class="tile" data-i="2"><h2>Serien</h2><p class="muted">' + "mit Staffeln" + '</p></div></div>' +
          '<div class="home-row"><div class="small" data-i="3">★ Favoriten</div><div class="small" data-i="4">Zuletzt gesehen</div>' +
          '<div class="small" data-i="5">Suche</div><div class="small" data-i="6">Einstellungen</div></div>' +
          '<div class="hints"><span><b>◀ ▶ ▲ ▼</b> auswählen</span><span><b>OK</b> öffnen</span><span><b>Zurück</b> beenden</span></div>';
        this.focus();
      },
      focus: function () {
        Array.prototype.forEach.call($app.querySelectorAll("[data-i]"), function (el) { el.classList.toggle("f", +el.getAttribute("data-i") === f); });
      },
      key: function (k) {
        if (k === K.RIGHT) { if (f < 2 || (f >= 3 && f < 6)) f++; }
        else if (k === K.LEFT) { if ((f > 0 && f <= 2) || f > 3) f--; }
        else if (k === K.DOWN) { if (f <= 2) f = 3 + Math.min(f, 3); }
        else if (k === K.UP) { if (f >= 3) f = Math.min(f - 3, 2); }
        else if (k === K.OK) {
          if (f === 0) show(Live());
          else if (f === 1) show(Grid("movie"));
          else if (f === 2) show(Grid("series"));
          else if (f === 3) show(Live("fav"));
          else if (f === 4) show(Live("hist"));
          else if (f === 5) show(Search());
          else if (f === 6) show(Settings());
          return true;
        } else return false;
        this.focus(); return true;
      }
    };
  }

  // ---- category list helper (left column used by live + grids)
  function catList(kind, cb) {
    cached(kind + ":cats", KINDS[kind].cats, "", function (e, cats) {
      var l = [{ id: "search", name: "Suche (alles)" }, { id: "fav", name: "★ Favoriten" }, { id: "hist", name: "Zuletzt gesehen" }, { id: "*", name: "Alle" }];
      (cats || []).forEach(function (c) { l.push({ id: String(c.category_id), name: c.category_name }); });
      cb(e, l);
    });
  }
  // draws only the visible rows of a list
  function windowed(items, sel, rows, draw) {
    var start = clamp(sel - Math.floor(rows / 2), 0, Math.max(0, items.length - rows)), out = "";
    for (var i = start; i < Math.min(items.length, start + rows); i++) out += draw(items[i], i, i === sel);
    return out;
  }

  // ---- live TV: categories | channels | preview + programme
  var PREVIEW = { x: 0, y: 0, w: 0, h: 0 };
  function Live(startCat) {
    var cats = [], ci = 0, chans = [], hi = 0, zone = "list", loading = true, err = "";
    var epgT = null, prevT = null, epg = null, lastPreview = null, catName = "";
    var self = {
      enter: function () {
        player.onState = null;
        if (cats.length) return;
        catList("live", function (e, l) {
          cats = l; err = e || "";
          var want = startCat || load("live.cat", "*");
          ci = 3;
          for (var i = 0; i < cats.length; i++) if (cats[i].id === want) ci = i;
          self.loadCat();
        });
      },
      leave: function () { clearTimeout(epgT); clearTimeout(prevT); player.stop(); lastPreview = null; },
      loadCat: function () {
        loading = true; chans = []; hi = 0; self.render();
        var c = cats[ci]; if (!c || c.id === "search") return;
        catName = c.name;
        if (c.id !== "fav" && c.id !== "hist") save("live.cat", c.id);
        entries("live", c.id, function (e, l) {
          if (cats[ci] !== c) return;
          loading = false; err = e || ""; chans = l; hi = 0;
          if (!chans.length && !err) zone = "cats";
          self.render(); self.schedule();
        });
      },
      render: function () {
        var c = cats[ci];
        $app.innerHTML = top("Live-TV", c ? c.name : "") +
          '<div class="cols"><div class="panel cats">' +
          windowed(cats, ci, 11, function (x, i, s) {
            return '<div class="cat' + (s ? (zone === "cats" ? " f sel" : " sel") : "") + '">' + esc(x.name) + '</div>';
          }) + '</div><div class="panel chs">' +
          (loading ? '<div class="empty">Lade …</div>' : err ? '<div class="empty red">' + esc(err) + '</div>' :
            !chans.length ? '<div class="empty">Keine Sender in dieser Kategorie.</div>' :
            windowed(chans, hi, 8, function (x, i, s) {
              return '<div class="ch' + (s && zone === "list" ? " f" : "") + '"><span class="no">' + (i + 1) + '</span>' + img(x.stream_icon) +
                '<div class="t"><div class="n">' + esc(x.name) + '</div><div class="s">' + (s && epg && epg[0] ? esc(epg[0].title) : "&nbsp;") + '</div></div>' +
                (isFav("live", x) ? '<span class="star">★</span>' : "") + '</div>';
            })) +
          '</div><div class="side"><div class="preview" id="pv">' + (chans.length ? "Vorschau startet …" : "") + '</div>' +
          '<div class="panel epg" id="epg">' + self.epgHtml() + '</div></div></div>' +
          '<div class="hints"><span><b>OK</b> Vollbild</span><span><b>◀ ▶</b> Kategorien / Sender</span>' +
          '<span><i class="key k-red"></i>Favorit</span><span><i class="key k-blue"></i>Suche</span><span><b>Zurück</b> Startseite</span></div>';
        var pv = document.getElementById("pv");
        if (pv) { var r = pv.getBoundingClientRect(); PREVIEW = { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) }; }
        if (lastPreview) player.rect(PREVIEW.x, PREVIEW.y, PREVIEW.w, PREVIEW.h);
      },
      epgHtml: function () {
        if (!epg) return '<div class="empty">' + (chans.length ? "Programm lädt …" : "") + '</div>';
        if (!epg.length) return '<div class="empty">Kein Programm verfügbar.</div>';
        var a = epg[0], b = epg[1], pr = clamp((now() - a.s) / Math.max(1, a.e - a.s) * 100, 0, 100);
        return '<h4>JETZT · ' + hm(a.s) + ' – ' + hm(a.e) + '</h4><div class="title">' + esc(a.title) + '</div>' +
          '<div class="bar"><i style="width:' + pr + '%"></i></div><div class="desc">' + esc(a.desc) + '</div>' +
          (b ? '<h4>DANACH · ' + hm(b.s) + '</h4><div class="title" style="font-size:26px;font-weight:500">' + esc(b.title) + '</div>' : "");
      },
      schedule: function () { // after a short pause on a channel: programme + preview
        clearTimeout(epgT); clearTimeout(prevT); epg = null;
        var ch = chans[hi]; if (!ch) return;
        epgT = setTimeout(function () {
          loadEpg(ch, function (l) { if (chans[hi] === ch) { epg = l; var el = document.getElementById("epg"); if (el) el.innerHTML = self.epgHtml(); self.render(); } });
        }, 350);
        prevT = setTimeout(function () {
          if (chans[hi] !== ch || zone !== "list") return;
          lastPreview = ch;
          var pv = document.getElementById("pv"); if (pv) pv.textContent = "";
          player.play(streamUrl("live", ch), PREVIEW.x, PREVIEW.y, PREVIEW.w, PREVIEW.h);
        }, 900);
      },
      key: function (k) {
        if (zone === "cats") {
          if (k === K.UP) { ci = Math.max(0, ci - 1); self.render(); return true; }
          if (k === K.DOWN) { ci = Math.min(cats.length - 1, ci + 1); self.render(); return true; }
          if (cats[ci] && cats[ci].id === "search" && (k === K.OK || k === K.RIGHT)) { show(Search()); return true; }
          if (k === K.OK || k === K.RIGHT) { zone = "list"; player.stop(); lastPreview = null; self.loadCat(); return true; }
          return false;
        }
        if (k === K.LEFT) { zone = "cats"; clearTimeout(prevT); self.render(); return true; }
        if (!chans.length) return false;
        if (k === K.UP || k === K.DOWN || k === K.CHUP || k === K.CHDOWN) {
          var d = (k === K.UP || k === K.CHUP) ? -1 : 1;
          hi = (hi + d + chans.length) % chans.length; self.render(); self.schedule(); return true;
        }
        if (k === 33 || k === 34) { hi = clamp(hi + (k === 33 ? -8 : 8), 0, chans.length - 1); self.render(); self.schedule(); return true; }
        if (k === K.OK) { show(Player("live", chans, hi, catName)); return true; }
        if (k === K.RED) { toggleFav("live", chans[hi]); self.render(); return true; }
        return false;
      }
    };
    return self;
  }
  var epgCache = {};
  function loadEpg(ch, cb) {
    var id = ch.stream_id, c = epgCache[id];
    if (c && c.t > now() - 300) return cb(c.l);
    api("get_short_epg", "&stream_id=" + id + "&limit=3", function (e, j) {
      var l = ((j && j.epg_listings) || []).map(function (p) {
        return { title: b64(p.title), desc: b64(p.description), s: parseInt(p.start_timestamp, 10), e: parseInt(p.stop_timestamp, 10) };
      }).filter(function (p) { return p.e > now(); });
      epgCache[id] = { t: now(), l: l };
      cb(l);
    });
  }

  // ---- movies / series: categories | info + poster grid
  function Grid(kind) {
    var cats = [], ci = 0, items = [], gi = 0, zone = "grid", loading = true, err = "", infoT = null, plot = {};
    var COLS = 6, ROWS = 2;
    var self = {
      enter: function () {
        player.onState = null;
        if (cats.length) { self.render(); return; }
        catList(kind, function (e, l) {
          cats = l; err = e || "";
          var want = load(kind + ".cat", "*");
          ci = 0; for (var i = 0; i < cats.length; i++) if (cats[i].id === want) ci = i;
          if (!want || (ci === 0 && want !== "search")) ci = 3;
          self.loadCat();
        });
      },
      leave: function () { clearTimeout(infoT); },
      loadCat: function () {
        loading = true; items = []; gi = 0; self.render();
        var c = cats[ci]; if (!c || c.id === "search") return;
        if (c.id !== "fav" && c.id !== "hist") save(kind + ".cat", c.id);
        entries(kind, c.id, function (e, l) {
          if (cats[ci] !== c) return;
          loading = false; err = e || ""; items = l; gi = 0;
          if (!items.length && !err) zone = "cats";
          self.render(); self.details();
        });
      },
      render: function () {
        var c = cats[ci], x = items[gi];
        var startRow = Math.max(0, Math.floor(gi / COLS) - (ROWS - 1)), html = "";
        for (var i = startRow * COLS; i < Math.min(items.length, (startRow + ROWS + 1) * COLS); i++) {
          var it = items[i];
          html += '<div class="poster' + (i === gi && zone === "grid" ? " f" : "") + '"><div class="img" style="background-image:url(\'' +
            esc(iconOf(it)).replace(/'/g, "%27") + '\')"></div><div class="pn">' + esc(it.name) + '</div></div>';
        }
        var meta = [];
        if (x) {
          if (x.year || x.releaseDate) meta.push(esc(x.year || String(x.releaseDate).slice(0, 4)));
          if (x.rating && x.rating !== "0") meta.push("★ " + esc(x.rating));
          if (x.genre) meta.push(esc(x.genre));
        }
        $app.innerHTML = '<div class="cols" style="top:40px">' +
          '<div class="cats"><div class="crumb" style="padding:0 18px 20px">' + KINDS[kind].title + '</div><div class="panel" style="height:calc(100% - 80px)">' +
          windowed(cats, ci, 10, function (y, i, s) { return '<div class="cat' + (s ? (zone === "cats" ? " f sel" : " sel") : "") + '">' + esc(y.name) + '</div>'; }) +
          '</div></div><div class="gridwrap"><div class="panel info">' +
          (x ? img(iconOf(x)) + '<div style="min-width:0;flex:1"><h3>' + esc(x.name) + '</h3><div class="meta">' + meta.join(" · ") +
            (isFav(kind, x) ?  ' · <span style="color:#fff">★ Favorit</span>' : "") + '</div><div class="plot">' + esc(x.plot || plot[idOf(kind, x)] || "") + '</div></div>'
            : '<div class="empty">' + (loading ? "Lade …" : err ? esc(err) : "Keine Einträge in dieser Kategorie.") + '</div>') +
          '</div><div class="grid">' + html + '</div></div></div>' +
          '<div class="hints"><span><b>OK</b> ' + (kind === "series" ? "Staffeln" : "abspielen") + '</span><span><b>◀</b> Kategorien</span>' +
          '<span><i class="key k-red"></i>Favorit</span><span><i class="key k-blue"></i>Suche</span><span><b>Zurück</b> Startseite</span><span class="muted">' + (items.length ? (gi + 1) + " / " + items.length : "") + '</span></div>';
      },
      details: function () { // plot for movies comes from a second request – only after a short pause
        clearTimeout(infoT);
        var x = items[gi]; if (!x || kind !== "movie" || plot[idOf(kind, x)] != null) return;
        infoT = setTimeout(function () {
          api("get_vod_info", "&vod_id=" + x.stream_id, function (e, j) {
            var i = (j && j.info) || {};
            plot[idOf(kind, x)] = i.plot || i.description || "";
            if (!x.year && i.releasedate) x.year = String(i.releasedate).slice(0, 4);
            if (!x.genre && i.genre) x.genre = i.genre;
            if (items[gi] === x) self.render();
          });
        }, 600);
      },
      key: function (k) {
        if (zone === "cats") {
          if (k === K.UP) { ci = Math.max(0, ci - 1); self.render(); return true; }
          if (k === K.DOWN) { ci = Math.min(cats.length - 1, ci + 1); self.render(); return true; }
          if (cats[ci] && cats[ci].id === "search" && (k === K.OK || k === K.RIGHT)) { show(Search()); return true; }
          if (k === K.OK || k === K.RIGHT) { zone = "grid"; self.loadCat(); return true; }
          return false;
        }
        if (!items.length) { if (k === K.LEFT) { zone = "cats"; self.render(); return true; } return false; }
        if (k === K.LEFT) { if (gi % COLS === 0) zone = "cats"; else gi--; }
        else if (k === K.RIGHT) { if (gi < items.length - 1) gi++; }
        else if (k === K.UP) { if (gi >= COLS) gi -= COLS; }
        else if (k === K.DOWN) { gi = Math.min(items.length - 1, gi + COLS); }
        else if (k === 33 || k === 34) { gi = clamp(gi + (k === 33 ? -COLS * 3 : COLS * 3), 0, items.length - 1); }
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
    var seasons = [], si = 0, eps = {}, ei = 0, zone = "eps", loading = true, err = "";
    var self = {
      enter: function () {
        player.onState = null;
        if (!loading) { self.render(); return; }
        api("get_series_info", "&series_id=" + s.series_id, function (e, j) {
          loading = false; err = e || "";
          eps = (j && j.episodes) || {};
          if (Array.isArray(eps)) { var o = {}; eps.forEach(function (l, i) { o[String(i + 1)] = l; }); eps = o; }
          seasons = Object.keys(eps).sort(function (a, b) { return a - b; });
          self.render();
        });
      },
      list: function () { return eps[seasons[si]] || []; },
      render: function () {
        var l = self.list();
        $app.innerHTML = top("Serien", s.name) + '<div class="cols"><div class="panel seasons">' +
          (loading ? '<div class="empty">Lade …</div>' : err ? '<div class="empty red">' + esc(err) + '</div>' :
            windowed(seasons, si, 10, function (x, i, sel) { return '<div class="cat' + (sel ? (zone === "seasons" ? " f sel" : " sel") : "") + '">Staffel ' + esc(x) + '</div>'; })) +
          '</div><div class="panel eps">' +
          windowed(l, ei, 9, function (x, i, sel) {
            return '<div class="ep' + (sel && zone === "eps" ? " f" : "") + '">' + esc((x.episode_num || i + 1) + ". " + (x.title || "")) + '</div>';
          }) + '</div></div>' +
          '<div class="hints"><span><b>OK</b> abspielen</span><span><b>◀</b> Staffeln</span><span><b>Zurück</b> Serien</span></div>';
      },
      key: function (k) {
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
        else if (k === K.OK && l[ei]) { addHist("series", s); show(Player("episode", l, ei, s.name)); return true; }
        else return false;
        self.render(); return true;
      }
    };
    return self;
  }

  // ---- full-screen player
  function Player(kind, list, idx, sub) {
    var osdT = null, osd = true, epg = null, state = "buffer", tick = null;
    var e = list[idx];
    var self = {
      enter: function () {
        document.body.classList.add("playing");
        player.onState = function (s) {
          state = s;
          if (s === "error") { toast("Wiedergabe fehlgeschlagen"); }
          if (s === "end" && kind !== "live") { back(); return; }
          self.render();
        };
        self.start();
        tick = setInterval(function () { if (osd && kind !== "live") self.render(); }, 1000);
      },
      leave: function () {
        clearTimeout(osdT); clearInterval(tick); player.onState = null; player.stop();
        document.body.classList.remove("playing");
      },
      start: function () {
        e = list[idx]; state = "buffer"; epg = null;
        if (kind === "live") { addHist("live", e); loadEpg(e, function (l) { if (list[idx] === e) { epg = l; self.render(); } }); }
        if (kind === "movie") addHist("movie", e);
        var pos = kind !== "live" ? load("pos." + kind + "." + (e.stream_id || e.id), 0) : 0;
        player.play(streamUrl(kind === "episode" ? "series" : kind, e), 0, 0, 1920, 1080, pos > 30000 ? pos - 5000 : 0);
        self.showOsd();
      },
      savePos: function () {
        if (kind === "live") return;
        var t = player.time(), d = player.duration();
        save("pos." + kind + "." + (e.stream_id || e.id), d && t > d - 60000 ? 0 : t);
      },
      showOsd: function () {
        osd = true; self.render();
        clearTimeout(osdT); osdT = setTimeout(function () { osd = false; self.render(); }, 5000);
      },
      render: function () {
        var h = '<div class="pclock">' + clock() + '</div>';
        if (state === "buffer") h += '<div class="spinner">Lädt …</div>';
        if (osd) {
          if (kind === "live") {
            var a = epg && epg[0], b = epg && epg[1];
            h += '<div class="osd"><div class="num">' + (idx + 1) + '</div>' + img(e.stream_icon) +
              '<div class="t"><div class="n">' + esc(e.name) + '</div>' +
              (a ? '<div class="now">' + hm(a.s) + ' &nbsp;' + esc(a.title) + ' &nbsp;<span class="muted">' + hm(a.e) + '</span></div>' +
                '<div class="bar"><i style="width:' + clamp((now() - a.s) / Math.max(1, a.e - a.s) * 100, 0, 100) + '%"></i></div>' : '<div class="now muted">' + esc(sub || "") + '</div>') +
              (b ? '<div class="next">Danach ' + hm(b.s) + ' · ' + esc(b.title) + '</div>' : "") + '</div>' +
              '<div class="help"><b>▲ ▼ / CH±</b> Sender<br><b>OK</b> Info<br><b>Zurück</b> zur Liste</div></div>';
          } else {
            var t = player.time() / 1000, d = player.duration() / 1000;
            var f = function (x) { x = Math.max(0, Math.floor(x)); return Math.floor(x / 3600) + ":" + pad(Math.floor(x / 60) % 60) + ":" + pad(x % 60); };
            h += '<div class="osd"><div class="t"><div class="n">' + esc(kind === "episode" ? (sub + " · " + (e.title || "")) : e.name) + '</div>' +
              '<div class="bar"><i style="width:' + (d ? clamp(t / d * 100, 0, 100) : 0) + '%"></i></div>' +
              '<div class="next">' + f(t) + ' / ' + (d ? f(d) : "–") + (player.paused() ? " · Pause" : "") + '</div></div>' +
              '<div class="help"><b>◀ ▶</b> 10 s · <b>▲ ▼</b> 1 min<br><b>OK</b> Pause / weiter<br><b>Zurück</b> beenden</div></div>';
          }
        }
        $app.innerHTML = h;
      },
      zap: function (d) {
        if (list.length < 2) return;
        idx = (idx + d + list.length) % list.length; player.stop(); self.start();
      },
      key: function (k) {
        if (kind === "live") {
          if (k === K.UP || k === K.CHUP) { self.zap(-1); return true; }
          if (k === K.DOWN || k === K.CHDOWN) { self.zap(1); return true; }
          if (k === K.OK) { if (osd) { osd = false; self.render(); } else self.showOsd(); return true; }
          if (k === K.RED) { toggleFav("live", e); return true; }
          return false;
        }
        if (k === K.OK || k === K.PLAYPAUSE || k === K.PLAY || k === K.PAUSE) { player.toggle(); self.showOsd(); return true; }
        if (k === K.RIGHT || k === K.FF) { player.seek(10000); self.showOsd(); return true; }
        if (k === K.LEFT || k === K.RW) { player.seek(-10000); self.showOsd(); return true; }
        if (k === K.UP) { player.seek(60000); self.showOsd(); return true; }
        if (k === K.DOWN) { player.seek(-60000); self.showOsd(); return true; }
        if (k === K.STOP) { self.savePos(); back(); return true; }
        return false;
      },
      beforeBack: function () { self.savePos(); }
    };
    return self;
  }

  // ---- search across live, movies, series (all categories)
  var allLists = null, allWait = [];
  function loadAll(cb) { // every channel, movie and series – loaded once, on first search
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
      enter: function () { player.onState = null; if (q) self.run(); },
      render: function () {
        $app.innerHTML = top("Suche", "Sender, Filme und Serien") + '<div style="padding:0 84px"><input class="inp' + (ri < 0 ? " f" : "") + '" id="q" placeholder="Suchbegriff eingeben" value="' + esc(q) + '">' +
          '<div class="panel" style="height:700px">' + (busy ? '<div class="empty">Suche in allen Kategorien … (beim ersten Mal kann das etwas dauern)</div>' :
            !res.length ? '<div class="empty">' + (q.length > 1 ? "Nichts gefunden." : "Mindestens 2 Buchstaben eingeben. Gesucht wird in allen Kategorien.") + '</div>' :
            windowed(res, Math.max(ri, 0), 7, function (x, i, sel) {
              return '<div class="ch' + (sel && ri >= 0 ? " f" : "") + '">' + img(iconOf(x.e)) + '<div class="t"><div class="n">' + esc(x.e.name) + '</div><div class="s">' + x.label + '</div></div></div>';
            })) + '</div></div><div class="hints"><span><b>▼</b> zu den Ergebnissen</span><span><b>OK</b> öffnen</span><span><b>Zurück</b> ' + (ri >= 0 ? "zum Suchfeld" : "Startseite") + '</span>' +
          (res.length ? '<span class="muted">' + res.length + (res.length >= 300 ? "+" : "") + ' Treffer</span>' : "") + '</div>';
        var el = document.getElementById("q");
        el.oninput = function () { q = el.value; clearTimeout(typeT); typeT = setTimeout(self.run, 500); };
        if (ri < 0) el.focus(); else el.blur();
      },
      run: function () {
        var el = document.getElementById("q"); if (el) q = el.value;
        q = q.trim(); save("search.q", q);
        if (q.length < 2) { res = []; self.render(); return; }
        if (!allLists) { busy = true; self.render(); }
        loadAll(function () {
          var words = q.toLowerCase().split(/\s+/); res = [];
          [["live", "Live-TV"], ["movie", "Film"], ["series", "Serie"]].forEach(function (p) {
            var l = allLists[p[0]];
            for (var i = 0; i < l.length && res.length < 300; i++) {
              var n = String(l[i].name || "").toLowerCase(), ok = true;
              for (var w = 0; w < words.length; w++) if (n.indexOf(words[w]) < 0) { ok = false; break; }
              if (ok) res.push({ kind: p[0], e: l[i], label: p[1] });
            }
          });
          busy = false; ri = -1; self.render();
        });
      },
      key: function (k) {
        if (ri < 0) {
          if (k === K.OK) { clearTimeout(typeT); self.run(); return true; }
          if (k === K.DOWN && res.length) { ri = 0; self.render(); return true; }
          return false;
        }
        if (k === K.UP) { if (ri === 0) ri = -1; else ri--; self.render(); return true; }
        if (k === K.DOWN) { ri = Math.min(res.length - 1, ri + 1); self.render(); return true; }
        if (k === K.OK) {
          var r = res[ri], same = res.filter(function (x) { return x.kind === r.kind; }).map(function (x) { return x.e; });
          if (r.kind === "series") show(Series(r.e)); else show(Player(r.kind, same, same.indexOf(r.e), "Suche"));
          return true;
        }
        if (k === K.RED) { toggleFav(res[ri].kind, res[ri].e); return true; }
        return false;
      },
      beforeBack: function () { if (ri >= 0) { ri = -1; self.render(); return true; } return false; }
    };
    return self;
  }

  // ---- settings
  function Settings() {
    var f = 0, items;
    var self = {
      render: function () {
        items = [
          ["Konto", esc(acc.user) + " @ " + esc(base())],
          ["Anmeldung ändern / anderes Konto", "›"],
          ["Favoriten und Verlauf löschen", "›"],
          ["Version", "rustTV für Fernseher " + VERSION],
          ["‹ Zurück zur Startseite", ""]
        ];
        $app.innerHTML = top("Einstellungen") + '<div style="padding:10px 84px">' + items.map(function (x, i) {
          return '<div class="row' + (i === f ? " f" : "") + '"><span>' + x[0] + '</span><span class="muted">' + x[1] + '</span></div>';
        }).join("") + '</div><div class="hints"><span><b>OK</b> auswählen</span><span><b>Zurück</b> Startseite</span></div>';
      },
      key: function (k) {
        if (k === K.UP) { f = Math.max(0, f - 1); self.render(); return true; }
        if (k === K.DOWN) { f = Math.min(items.length - 1, f + 1); self.render(); return true; }
        if (k === K.OK) {
          if (f === 1) { stack = []; show(Login()); }
          if (f === 2) { ["live", "movie", "series"].forEach(function (x) { save("fav." + x, []); save("hist." + x, []); }); toast("Gelöscht"); }
          if (f === items.length - 1) back();
          return true;
        }
        return false;
      }
    };
    return self;
  }

  var VERSION = "__VERSION__";

  // ------------------------------------------------------------------ key dispatch
  function goBack() {
    try { if (screen && screen.beforeBack && screen.beforeBack() === true) return; } catch (e) {}
    try { back(); } catch (e) { stack = []; show(Home()); }
  }
  document.addEventListener("keydown", function (ev) {
    var k = ev.keyCode;
    var typing = document.activeElement && document.activeElement.tagName === "INPUT";
    if (k === K.BACK || k === K.ESC || (k === K.BS && !typing)) {
      ev.preventDefault();
      if (typing) document.activeElement.blur();
      goBack();
      return;
    }
    if (k === K.BLUE && screen && !screen.isSearch) { ev.preventDefault(); show(Search()); return; }
    if (typing && (k === K.LEFT || k === K.RIGHT || (k >= 48 && k <= 90) || k === K.BS || k === 32)) return; // let the text field have it
    try { if (screen && screen.key && screen.key(k)) ev.preventDefault(); } catch (e) { toast("Fehler: " + e.message); }
  });
  // some Tizen versions report the Return key only as a hardware-key event
  window.addEventListener("tizenhwkey", function (ev) { if (ev.keyName === "back") goBack(); });

  // start
  if (acc && acc.server) show(Home()); else show(Login());
})();
