#!/usr/bin/env python3
"""Builds the stand-alone Samsung TV app (Tizen .wgt) from a trunk build of web/tizen.html.

Usage: build.py <trunk-dist-dir> <version> <out.wgt>

TV web runtimes can be picky with ES modules and fetching local files, so the
module script trunk writes is turned into classic scripts and the WebAssembly
file is embedded as base64.
"""
import base64, glob, os, re, shutil, sys, tempfile, zipfile

dist, version, out = sys.argv[1], sys.argv[2], sys.argv[3]
here = os.path.dirname(os.path.abspath(__file__))
tmp = tempfile.mkdtemp()
shutil.copytree(dist, os.path.join(tmp, "app"))
app = os.path.join(tmp, "app")

html = open(os.path.join(app, "index.html"), encoding="utf-8").read()
js_name = re.search(r"from '\./([^']+\.js)'", html).group(1)
wasm_name = re.search(r"module_or_path: '\./([^']+\.wasm)'", html).group(1)

# 1) bindings: ES module -> classic script exposing window.rustvInit
js = open(os.path.join(app, js_name), encoding="utf-8").read()
if "let wasm_bindgen" in js:
    # trunk already wrote a classic (no-modules) script
    js = js + "\nwindow.rustvInit = wasm_bindgen;\n"
else:
    js = js.replace("import.meta.url", "location.href")
    js = re.sub(r"^export \{[^}]*\};?\s*$", "", js, flags=re.M)
    js = re.sub(r"^export default (\w+);?\s*$", r"window.rustvInit = \1;", js, flags=re.M)
    js = re.sub(r"^export (async function|function|class|const|let)", r"\1", js, flags=re.M)
    assert not re.search(r"^export ", js, re.M), "export left"
    js = "(function(){\n" + js + "\n})();\n"
open(os.path.join(app, "bindings.js"), "w", encoding="utf-8").write(js)
os.remove(os.path.join(app, js_name))

# 2) wasm as base64
b64 = base64.b64encode(open(os.path.join(app, wasm_name), "rb").read()).decode()
open(os.path.join(app, "wasm.js"), "w").write('window.RUSTV_WASM="' + b64 + '";\n')
os.remove(os.path.join(app, wasm_name))

# 3) boot script
open(os.path.join(app, "boot.js"), "w").write(
    """(function () {
  var s = atob(window.RUSTV_WASM), b = new Uint8Array(s.length);
  for (var i = 0; i < s.length; i++) b[i] = s.charCodeAt(i);
  window.RUSTV_WASM = null;
  window.rustvInit({ module_or_path: b }).catch(function (e) {
    document.body.innerHTML = '<p style="color:#fff;font-size:28px;padding:40px">rustTV konnte nicht starten: ' + e + '</p>';
  });
})();
""")

# 4) html: drop the inline module + preloads + integrity, load the classic scripts
html = re.sub(r'<script type="module">.*?</script>', "", html, flags=re.S)
html = re.sub(r'<link rel="(modulepreload|preload)"[^>]*>', "", html)
html = re.sub(r' integrity="[^"]*"', "", html)
html = html.replace("</body>", '<script src="bindings.js"></script>\n<script src="wasm.js"></script>\n<script src="boot.js"></script>\n</body>')
open(os.path.join(app, "index.html"), "w", encoding="utf-8").write(html)

# 5) Tizen files
cfg = open(os.path.join(here, "config.xml"), encoding="utf-8").read().replace('version="1.0.0"', f'version="{version}"')
open(os.path.join(app, "config.xml"), "w", encoding="utf-8").write(cfg)
shutil.copy(os.path.join(here, "icon.png"), os.path.join(app, "icon.png"))

if os.path.exists(out):
    os.remove(out)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(app):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, app))
print("wrote", out, os.path.getsize(out), "bytes")
