(function () {
  "use strict";
  var KEY = "rustv.tv.server", FKEY = "rustv.tv.framed";
  var $ = function (id) { return document.getElementById(id); };
  var timer = null;

  try { tizen.tvinputdevice.registerKey("MediaPlayPause"); } catch (e) {}

  function clean(u) {
    u = (u || "").trim();
    if (!u) return "";
    if (!/^https?:\/\//i.test(u)) u = "http://" + u;
    return u.replace(/\/+$/, "");
  }

  function showSetup(msg) {
    clearTimeout(timer);
    $("wait").style.display = "none";
    $("frame").style.display = "none";
    $("setup").style.display = "block";
    $("url").value = localStorage.getItem(KEY) || "";
    $("framed").checked = localStorage.getItem(FKEY) === "1";
    $("msg").textContent = msg || "";
    $("url").focus();
  }

  function open(url) {
    $("setup").style.display = "none";
    $("wait").style.display = "block";
    $("waitUrl").textContent = url;
    $("change").focus();
    // check the server first, so a wrong address leads back here
    var x = new XMLHttpRequest();
    x.open("GET", url + "/api/info");
    x.timeout = 8000;
    x.onload = function () {
      if (x.status !== 200) { showSetup("Server antwortet nicht richtig (" + x.status + ")."); return; }
      timer = setTimeout(function () {
        if (localStorage.getItem(FKEY) === "1") {
          $("wait").style.display = "none";
          $("frame").style.display = "block";
          $("frame").src = url + "/";
          $("frame").focus();
        } else {
          location.href = url + "/";
        }
      }, 1500);
    };
    x.onerror = x.ontimeout = function () { showSetup("Keine Verbindung zu " + url + ". Adresse prüfen."); };
    x.send();
  }

  $("go").onclick = function () {
    var u = clean($("url").value);
    if (!u) { $("msg").textContent = "Bitte eine Adresse eingeben."; return; }
    localStorage.setItem(KEY, u);
    localStorage.setItem(FKEY, $("framed").checked ? "1" : "0");
    open(u);
  };
  $("change").onclick = function () { showSetup(""); };

  // simple remote-control navigation for this page
  document.addEventListener("keydown", function (e) {
    var order = [$("url"), $("framed"), $("go")];
    if ($("setup").style.display === "block") {
      var i = order.indexOf(document.activeElement);
      if (e.keyCode === 40) { e.preventDefault(); order[Math.min(i + 1, order.length - 1)].focus(); }
      if (e.keyCode === 38) { e.preventDefault(); order[Math.max(i - 1, 0)].focus(); }
      if (e.keyCode === 13 && document.activeElement === $("url")) { e.preventDefault(); $("go").click(); }
    }
    if (e.keyCode === 10009) { // Return
      if ($("setup").style.display === "block" && localStorage.getItem(KEY)) { open(localStorage.getItem(KEY)); return; }
      try { tizen.application.getCurrentApplication().exit(); } catch (x) {}
    }
  });

  var saved = localStorage.getItem(KEY);
  if (saved) open(saved); else showSetup("");
})();
