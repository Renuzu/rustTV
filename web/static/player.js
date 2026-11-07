// ruSTv player glue: picks the right engine (mpegts.js / hls.js / native),
// handles touch gestures (brightness, volume, seek, channel switch) and keyboard.
(function () {
  "use strict";
  let mp = null, hls = null, video = null, box = null, opts = {}, cbs = {};
  let hideTimer = null, info = null, infoTimer = null, bright = 1, fitMode = "contain", zoom = 1;

  function hasMse() { return !!(window.MediaSource || window.ManagedMediaSource); }
  function nativeHls(v) { return !!v.canPlayType("application/vnd.apple.mpegurl"); }

  function destroy() {
    if (mp) { try { mp.pause(); mp.unload(); mp.detachMediaElement(); mp.destroy(); } catch (e) {} mp = null; }
    if (hls) { try { hls.destroy(); } catch (e) {} hls = null; }
    if (video) { video.removeAttribute("src"); try { video.load(); } catch (e) {} }
  }

  function fail(msg) { if (cbs.error) cbs.error(String(msg || "error")); }

  function playHls(url) {
    if (window.Hls && Hls.isSupported()) {
      hls = new Hls({ enableWorker: true, lowLatencyMode: false });
      hls.on(Hls.Events.ERROR, function (_, d) {
        if (!d.fatal) return;
        if (d.type === Hls.ErrorTypes.MEDIA_ERROR) { try { hls.recoverMediaError(); return; } catch (e) {} }
        fail("HLS: " + d.details);
      });
      hls.loadSource(url);
      hls.attachMedia(video);
    } else if (nativeHls(video)) {
      video.src = url;
    } else {
      fail("HLS not supported in this browser");
      return;
    }
    video.play().catch(function () {});
  }

  function playTs(url, live, fallback) {
    const ok = window.mpegts && mpegts.isSupported() && hasMse();
    if (!ok) { if (fallback) playHls(fallback); else fail("MPEG-TS not supported in this browser"); return; }
    mp = mpegts.createPlayer({ type: "mpegts", isLive: live, url: url }, {
      enableWorker: true, lazyLoad: false, liveBufferLatencyChasing: live,
      liveBufferLatencyMaxLatency: 6, liveBufferLatencyMinRemain: 1.5, autoCleanupSourceBuffer: true,
    });
    mp.on(mpegts.Events.ERROR, function (type, detail) {
      if (fallback) { const f = fallback; fallback = null; destroy(); playHls(f); }
      else fail("TS: " + type + " " + detail);
    });
    mp.attachMediaElement(video);
    mp.load();
    const p = mp.play(); if (p && p.catch) p.catch(function () {});
  }

  function abs(u) { return u ? new URL(u, location.href).href : u; }

  function start(container, v, o) {
    destroy();
    o = o || {};
    o.tsUrl = abs(o.tsUrl); o.hlsUrl = abs(o.hlsUrl); o.fileUrl = abs(o.fileUrl);
    box = container; video = v; opts = o || {};
    video.style.filter = bright !== 1 ? "brightness(" + bright + ")" : "";
    applyFit();
    if (o.live) {
      const mode = o.mode || "auto";
      if (mode === "hls") playHls(o.hlsUrl);
      else if (mode === "ts") playTs(o.tsUrl, true, o.hlsUrl);
      else if (hasMse() && window.mpegts && mpegts.isSupported()) playTs(o.tsUrl, true, o.hlsUrl);
      else playHls(o.hlsUrl);
    } else if (o.tsFile) {
      playTs(o.fileUrl, false, null);
    } else {
      video.src = o.fileUrl;
      if (o.startAt > 0) {
        video.addEventListener("loadedmetadata", function once() {
          video.removeEventListener("loadedmetadata", once);
          try { video.currentTime = o.startAt; } catch (e) {}
        });
      }
      video.play().catch(function () {});
    }
    showUi();
  }

  // ---------------------------------------------------------- picture format
  // contain = fit, cover = fill/crop, fill = stretch, none = original size, 16:9 / 4:3 = forced ratio
  function applyFit() {
    if (!video || !box) return;
    const st = video.style;
    st.width = st.height = st.left = st.top = "";
    st.transform = zoom === 1 ? "" : "scale(" + zoom + ")";
    const ratio = fitMode === "16:9" ? 16 / 9 : fitMode === "4:3" ? 4 / 3 : 0;
    if (ratio) {
      const W = box.clientWidth, H = box.clientHeight;
      let w = W, h = W / ratio;
      if (h > H) { h = H; w = H * ratio; }
      st.objectFit = "fill";
      st.width = w + "px"; st.height = h + "px";
      st.left = (W - w) / 2 + "px"; st.top = (H - h) / 2 + "px";
    } else {
      st.objectFit = fitMode;
    }
  }
  window.addEventListener("resize", applyFit);

  // ---------------------------------------------------------- overlay
  function showUi() {
    if (!box) return;
    box.classList.add("ui");
    clearTimeout(hideTimer);
    hideTimer = setTimeout(function () { if (box && !box.classList.contains("keep")) box.classList.remove("ui"); }, 4000);
  }
  function toggleUi() {
    if (!box) return;
    if (box.classList.contains("ui")) { box.classList.remove("ui"); clearTimeout(hideTimer); } else showUi();
  }
  function bubble(text, keep) {
    if (!box) return;
    if (!info || !box.contains(info)) { info = document.createElement("div"); info.className = "gest-info"; box.appendChild(info); }
    info.textContent = text; info.style.display = "block";
    clearTimeout(infoTimer);
    if (!keep) infoTimer = setTimeout(function () { info.style.display = "none"; }, 900);
  }
  function fmt(s) {
    s = Math.max(0, Math.floor(s));
    const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = s % 60;
    return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(x).padStart(2, "0");
  }

  // ---------------------------------------------------------- gestures
  let g = null;
  function gestures(layer) {
    layer.addEventListener("touchstart", function (e) {
      if (e.touches.length === 2 && opts.gestures && opts.gestures.pinch) {
        const a = e.touches[0], b = e.touches[1];
        g = { pinch: true, d0: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY), z0: zoom };
        return;
      }
      if (e.touches.length !== 1) { g = null; return; }
      const t = e.touches[0], r = layer.getBoundingClientRect();
      g = { x: t.clientX, y: t.clientY, w: r.width, h: r.height, left: t.clientX - r.left < r.width / 2,
            mode: null, vol: video ? video.volume : 1, br: bright, seek: 0 };
    }, { passive: true });
    layer.addEventListener("touchmove", function (e) {
      if (!g || !video) return;
      if (g.pinch) {
        if (e.touches.length < 2) return;
        e.preventDefault();
        const a = e.touches[0], b = e.touches[1];
        zoom = Math.min(4, Math.max(0.5, g.z0 * Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY) / g.d0));
        video.style.transform = zoom === 1 ? "" : "scale(" + zoom + ")";
        bubble("🔍 " + Math.round(zoom * 100) + "%", true);
        return;
      }
      const t = e.touches[0], dx = t.clientX - g.x, dy = t.clientY - g.y, ge = opts.gestures || {};
      if (!g.mode) {
        if (Math.abs(dy) > 20 && Math.abs(dy) > Math.abs(dx)) g.mode = g.left ? (ge.bright ? "bright" : "none") : (ge.volume ? "volume" : "none");
        else if (Math.abs(dx) > 20) g.mode = opts.live ? (ge.zap && opts.canZap ? "zap" : "none") : (ge.seek ? "seek" : "none");
      }
      if (!g.mode || g.mode === "none") return;
      e.preventDefault();
      if (g.mode === "volume") {
        const v = Math.min(1, Math.max(0, g.vol - dy / (g.h * 0.7)));
        video.volume = v; video.muted = v === 0;
        bubble("🔊 " + Math.round(v * 100) + "%", true);
      } else if (g.mode === "bright") {
        bright = Math.min(1.6, Math.max(0.15, g.br - dy / (g.h * 0.7)));
        video.style.filter = "brightness(" + bright + ")";
        bubble("☀ " + Math.round(bright * 100) + "%", true);
      } else if (g.mode === "seek") {
        const d = dx / g.w * 120;
        g.seek = Math.min(video.duration || 1e9, Math.max(0, video.currentTime + d));
        bubble((d >= 0 ? "⏩ +" : "⏪ -") + fmt(Math.abs(d)) + "   " + fmt(g.seek), true);
      } else if (g.mode === "zap") {
        bubble(dx < 0 ? "▶" : "◀", true);
      }
    }, { passive: false });
    layer.addEventListener("touchend", function (e) {
      if (!g) return;
      if (g.pinch) {
        if (e.touches.length === 0) { g = null; infoTimer = setTimeout(function () { if (info) info.style.display = "none"; }, 600); }
        e.preventDefault();
        return;
      }
      const t = e.changedTouches[0], dx = t.clientX - g.x;
      if (!g.mode) toggleUi();
      else if (g.mode === "seek") { try { video.currentTime = g.seek; } catch (x) {} }
      else if (g.mode === "zap" && Math.abs(dx) > g.w * 0.2 && cbs.zap) cbs.zap(dx < 0 ? 1 : -1);
      if (info) infoTimer = setTimeout(function () { info.style.display = "none"; }, 600);
      g = null;
      e.preventDefault();
    });
    layer.addEventListener("click", function () { toggleUi(); });
    layer.addEventListener("dblclick", function () { fullscreen(); });
    layer.addEventListener("mousemove", function () { showUi(); });
  }

  document.addEventListener("keydown", function (e) {
    if (!video || !box || !document.body.contains(box)) return;
    if (e.target && /INPUT|SELECT|TEXTAREA/.test(e.target.tagName)) return;
    switch (e.key) {
      case " ": e.preventDefault(); video.paused ? video.play() : video.pause(); break;
      case "ArrowRight": if (!opts.live) video.currentTime += 10; break;
      case "ArrowLeft": if (!opts.live) video.currentTime -= 10; break;
      case "ArrowUp": case "PageUp": case "ChannelUp": if (cbs.zap) { e.preventDefault(); cbs.zap(-1); } break;
      case "ArrowDown": case "PageDown": case "ChannelDown": if (cbs.zap) { e.preventDefault(); cbs.zap(1); } break;
      case "f": fullscreen(); break;
    }
    showUi();
  });

  function fullscreen() {
    if (!box) return;
    const d = document;
    if (d.fullscreenElement || d.webkitFullscreenElement) { (d.exitFullscreen || d.webkitExitFullscreen).call(d); return; }
    if (box.requestFullscreen) box.requestFullscreen().catch(function () {});
    else if (video && video.webkitEnterFullscreen) video.webkitEnterFullscreen(); // iPhone
  }

  window.rustvPlayer = {
    start: start,
    stop: function () { destroy(); box = null; },
    attachGestures: function (layer) { gestures(layer); },
    setCallbacks: function (zap, ended, error) {
      cbs = { zap: zap, ended: ended, error: error };
    },
    bindVideo: function (v) {
      v.addEventListener("ended", function () { if (cbs.ended) cbs.ended(); });
      v.addEventListener("error", function () {
        if (mp || hls) return; // engines report their own errors
        const c = v.error ? v.error.code : 0;
        fail(c === 4 ? "format not supported by this browser" : "playback error " + c);
      });
    },
    time: function () { return video ? video.currentTime || 0 : 0; },
    duration: function () { return video && isFinite(video.duration) ? video.duration : 0; },
    setFit: function (m) { fitMode = m; zoom = 1; applyFit(); },
    pip: function () {
      if (!video) return;
      if (document.pictureInPictureElement) document.exitPictureInPicture();
      else if (video.requestPictureInPicture) video.requestPictureInPicture().catch(function () {});
      else if (video.webkitSetPresentationMode) video.webkitSetPresentationMode("picture-in-picture");
    },
    fullscreen: fullscreen,
    speed: function (x) { if (video) video.playbackRate = x; },
    keepUi: function (on) { if (box) { box.classList.toggle("keep", on); if (on) box.classList.add("ui"); else showUi(); } },
    showUi: showUi,
  };
})();
