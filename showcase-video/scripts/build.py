"""Wraps src/sNN-*.html scene bodies into HyperFrames sub-compositions (compositions/sNN-*.html).
Scene source format: first line `<!-- id=s02 duration=7 -->`, then <style>…</style>, markup, then <script>…</script>.
Each scene script runs inside an IIFE (scenes share one page) with shared.js helpers; it must create `tl`."""
import pathlib, re
root = pathlib.Path(__file__).resolve().parent.parent
css = (root / "src/shared.css").read_text()
js = (root / "src/shared.js").read_text()
for src in sorted((root / "src").glob("s[0-9][0-9]-*.html")):
    text = src.read_text()
    m = re.match(r"<!--\s*id=(\S+)\s+duration=(\S+)\s*-->", text)
    sid, dur = m.group(1), m.group(2)
    body = text[m.end():]
    style = "".join(re.findall(r"<style>(.*?)</style>", body, re.S))
    script = "".join(re.findall(r"<script>(.*?)</script>", body, re.S))
    markup = re.sub(r"<style>.*?</style>|<script>.*?</script>", "", body, flags=re.S).strip()
    out = f"""<!doctype html>
<html><head><meta charset="UTF-8" /></head><body>
<template>
<style>
{css}
#{sid} {{ position: absolute; inset: 0; overflow: hidden; }}
{style}
</style>
<div id="{sid}" data-composition-id="{sid}" data-width="1080" data-height="1920" data-duration="{dur}">
{markup}
</div>
<script>
(function () {{
{js}
var tl = gsap.timeline({{ paused: true }});
{script}
window.__timelines["{sid}"] = tl;
}})();
</script>
</template>
</body></html>
"""
    (root / "compositions" / src.name).write_text(out)
    print("built", src.name, sid, dur)

# Mount the built scenes in index.html on the schedule below (0.4 s crossfade overlaps).
SCHEDULE = [("s01", 0.0, 6), ("s02", 5.6, 7), ("s03", 12.2, 7), ("s04", 18.8, 7), ("s05", 25.4, 7),
            ("s06", 32.0, 7), ("s07", 38.6, 7), ("s08", 45.2, 6.4), ("s09", 51.2, 6.8)]
idx = root / "index.html"
html = idx.read_text()
start, end = "<!-- scenes:start -->", "<!-- scenes:end -->"
hosts = []
for sid, at, dur in SCHEDULE:
    f = next((p for p in (root / "compositions").glob(sid + "-*.html")), None)
    if f is None: continue
    hosts.append(f'      <div id="{sid}" data-composition-id="{sid}" data-composition-src="compositions/{f.name}" data-start="{at}" data-duration="{dur}" data-track-index="{1 + SCHEDULE.index((sid, at, dur)) % 2}" data-width="1080" data-height="1920"></div>')
block = start + "\n" + "\n".join(hosts) + "\n      " + end
if start in html:
    html = html[: html.index(start)] + block + html[html.index(end) + len(end):]
idx.write_text(html)
print("mounted", len(hosts), "scenes")
