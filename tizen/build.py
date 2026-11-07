#!/usr/bin/env python3
"""Packs the Samsung TV app (Tizen .wgt) from tizen/tv.  Usage: build.py <version> <out.wgt>"""
import os, sys, zipfile

version, out = sys.argv[1], sys.argv[2]
here = os.path.dirname(os.path.abspath(__file__))
src = os.path.join(here, "tv")
if os.path.exists(out):
    os.remove(out)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    cfg = open(os.path.join(here, "config.xml"), encoding="utf-8").read().replace('version="1.0.0"', f'version="{version}"')
    z.writestr("config.xml", cfg)
    z.write(os.path.join(here, "icon.png"), "icon.png")
    for f in sorted(os.listdir(src)):
        data = open(os.path.join(src, f), encoding="utf-8").read().replace("__VERSION__", version)
        z.writestr(f, data)
print("wrote", out, os.path.getsize(out), "bytes")
