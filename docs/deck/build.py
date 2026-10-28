#!/usr/bin/env python3
"""Build docs/deck/deck.html, a self-contained viewer for the Omni-Bridge deck.

The deck's source is project/deck.json (order, sections, fonts) and one
project/slides/<id>.html per slide, in the Claude Slides format: a single
<section> on a 1920x1080 canvas with inline styles. On claude.ai the type's own
runtime renders them; this script inlines every slide into one page with the
little CSS and JavaScript the format needs, so the deck opens from a checkout,
from GitHub Pages or from any web server without claude.ai.

    python docs/deck/build.py            # writes docs/deck/deck.html

Keys in the viewer: arrows, space and page keys move; Home and End jump; N
shows the speaker notes; O shows the overview grid; F goes full screen. The
URL hash is #slide-N, the same numbering the claude.ai deck uses.
"""
import html
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent
PROJECT = ROOT / "project"
OUT = ROOT / "deck.html"

# Icons used by the deck, as 24x24 stroke paths (x-icon names of the Slides format).
ICONS = {
    "Globe": '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/>',
    "Lightning": '<path d="M13 2 4 14h7l-1 8 9-12h-7z"/>',
    "Database": '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/>',
    "Activity": '<path d="M3 12h4l3-8 4 16 3-8h4"/>',
    "Clock": '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    "Check": '<path d="M5 12l5 5L20 7"/>',
    "Warning": '<path d="M12 3 2 21h20zM12 10v5M12 18v.5"/>',
    "Lock": '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
    "Settings": '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1"/>',
    "Chart": '<path d="M4 20V10M10 20V4M16 20v-8M22 20H2"/>',
    "Code": '<path d="m8 7-5 5 5 5M16 7l5 5-5 5M14 4l-4 16"/>',
    "Cloud": '<path d="M7 18a4 4 0 0 1-.5-8 6 6 0 0 1 11.4 1.5A3.5 3.5 0 0 1 17.5 18z"/>',
    "Link": '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1.5 1.5M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1.5-1.5"/>',
    "Search": '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m21 21-5.5-5.5"/>',
    "Users": '<circle cx="9" cy="8" r="3.5"/><circle cx="17" cy="9" r="2.5"/><path d="M2.5 20a6.5 6.5 0 0 1 13 0M15 20a5 5 0 0 1 6.5-4.8"/>',
}

CSS = """
html,body{margin:0;height:100%;background:#1a1a1a;overflow:hidden;font-family:'IBM Plex Sans',Arial,sans-serif}
#stage{position:absolute;left:50%;top:50%;width:1920px;height:1080px;transform-origin:0 0}
section{position:absolute;left:0;top:0;width:1920px;height:1080px;box-sizing:border-box;overflow:hidden;visibility:hidden;flex-direction:column}
section.on{visibility:visible}
section *{box-sizing:border-box}
h1,h2,h3,p,ul,ol{margin:0}
h1{font-size:96px;font-weight:600;line-height:1.1}
h2{font-size:64px;font-weight:600;line-height:1.15}
h3{font-size:44px;font-weight:600;line-height:1.2}
p,li{font-size:32px;line-height:1.4}
section div{display:flex;flex-direction:column}
a{color:inherit}
table{width:100%;border-collapse:collapse}
th,td{padding:0.35em 0.6em;border:1px solid #d5d2c8;vertical-align:top;text-align:left}
th{font-weight:600}
hr{border:0;border-top:1px solid currentColor;width:100%}
x-icon{display:inline-flex;align-items:center;justify-content:center}
x-icon svg{width:100%;height:100%}
svg.x-connector{position:absolute;left:0;top:0;width:100%;height:100%;overflow:visible;pointer-events:none}
aside{display:none}
#hud{position:fixed;right:16px;bottom:12px;color:#8fa0b3;font-size:14px;opacity:0;transition:opacity .3s;pointer-events:none}
body.hud #hud{opacity:1}
#notes{position:fixed;left:0;right:0;bottom:0;max-height:38%;overflow:auto;background:#0f1b2dee;color:#f6f5f0;font-size:16px;line-height:1.5;padding:16px 24px;display:none;border-top:2px solid #4fc3b0}
body.notes #notes{display:block}
#grid{position:fixed;inset:0;overflow:auto;background:#1a1a1a;display:none;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:16px;padding:16px}
body.grid #grid{display:grid}
#grid .thumb{position:relative;aspect-ratio:16/9;overflow:hidden;cursor:pointer;border:2px solid transparent;border-radius:4px}
#grid .thumb.cur{border-color:#4fc3b0}
#grid .thumb iframe{position:absolute;left:0;top:0;width:1920px;height:1080px;border:0;pointer-events:none;transform-origin:0 0}
#grid .thumb span{position:absolute;left:6px;bottom:4px;color:#f6f5f0;font-size:12px;background:#0f1b2dcc;padding:1px 6px;border-radius:3px}
@media print{
  html,body{overflow:visible;background:#fff;height:auto}
  #stage{position:static;transform:none!important;width:auto;height:auto}
  section{position:relative;visibility:visible;page-break-after:always;break-after:page}
  #hud,#notes,#grid{display:none!important}
  @page{size:1920px 1080px;margin:0}
}
"""

JS = r"""
(function(){
  var secs=[].slice.call(document.querySelectorAll('#stage > section'));
  var n=secs.length, cur=0, hud=document.getElementById('hud'), notes=document.getElementById('notes');
  var grid=document.getElementById('grid'), hudTimer;
  function fit(){
    var s=Math.min(innerWidth/1920, innerHeight/1080);
    var st=document.getElementById('stage');
    st.style.transform='translate(-50%,-50%) scale('+s+')';
    st.style.transformOrigin='center center';
    st.style.left='50%'; st.style.top='50%';
  }
  function show(i, push){
    cur=Math.max(0, Math.min(n-1, i));
    secs.forEach(function(s,k){ s.classList.toggle('on', k===cur); });
    hud.textContent=(cur+1)+' / '+n;
    var a=secs[cur].querySelector('aside');
    notes.textContent=a?a.textContent:'(no notes)';
    document.title=DECK_TITLE+' — '+(cur+1)+'/'+n;
    if(push!==false && location.hash!=='#slide-'+(cur+1)) history.replaceState(null,'','#slide-'+(cur+1));
    [].forEach.call(grid.children,function(t,k){ t.classList.toggle('cur',k===cur); });
    flash();
  }
  function flash(){ document.body.classList.add('hud'); clearTimeout(hudTimer); hudTimer=setTimeout(function(){document.body.classList.remove('hud');},1500); }
  function fromHash(){ var m=/slide-(\d+)/.exec(location.hash); show(m?parseInt(m[1],10)-1:0, false); }
  function buildGrid(){
    if(grid.children.length) return;
    secs.forEach(function(s,k){
      var t=document.createElement('div'); t.className='thumb';
      var f=document.createElement('iframe'); f.setAttribute('loading','lazy');
      var css=document.getElementById('deckcss').textContent;
      var fonts=[].map.call(document.querySelectorAll('link[rel=stylesheet]'),function(l){return l.outerHTML;}).join('');
      f.srcdoc='<!doctype html><html><head><meta charset="utf-8">'+fonts+'<style>'+css+' section{visibility:visible;position:static}</style></head><body>'+s.outerHTML+'</body></html>';
      var lab=document.createElement('span'); lab.textContent=(k+1)+' '+(s.querySelector('h1,h2')||{textContent:s.id}).textContent.slice(0,60);
      t.appendChild(f); t.appendChild(lab);
      t.onclick=function(){ document.body.classList.remove('grid'); show(k); };
      grid.appendChild(t);
    });
    scaleGrid();
  }
  function scaleGrid(){
    [].forEach.call(grid.querySelectorAll('.thumb'),function(t){ var f=t.querySelector('iframe'); f.style.transform='scale('+(t.clientWidth/1920)+')'; });
  }
  addEventListener('resize', function(){ fit(); if(document.body.classList.contains('grid')) scaleGrid(); });
  addEventListener('hashchange', fromHash);
  addEventListener('keydown', function(e){
    if(e.metaKey||e.ctrlKey||e.altKey) return;
    switch(e.key){
      case 'ArrowRight': case 'ArrowDown': case ' ': case 'PageDown': case 'Enter': show(cur+1); break;
      case 'ArrowLeft': case 'ArrowUp': case 'PageUp': case 'Backspace': show(cur-1); break;
      case 'Home': show(0); break;
      case 'End': show(n-1); break;
      case 'n': case 'N': document.body.classList.toggle('notes'); break;
      case 'o': case 'O': case 'Escape':
        if(e.key==='Escape' && !document.body.classList.contains('grid')) return;
        if(!document.body.classList.contains('grid')) buildGrid();
        document.body.classList.toggle('grid'); if(document.body.classList.contains('grid')) scaleGrid(); break;
      case 'f': case 'F': if(document.fullscreenElement) document.exitFullscreen(); else document.documentElement.requestFullscreen(); break;
      default: return;
    }
    e.preventDefault();
  });
  document.getElementById('stage').addEventListener('click', function(e){
    if(e.target.closest('a')) return;
    show(e.clientX > innerWidth/2 ? cur+1 : cur-1);
  });
  addEventListener('mousemove', flash);
  fit(); fromHash();
})();
"""


def connector_svg(m):
    """Replace an <x-connector x1 y1 x2 y2 head style> with an inline SVG arrow."""
    attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1)))
    try:
        x1, y1, x2, y2 = (float(attrs[k]) for k in ("x1", "y1", "x2", "y2"))
    except KeyError:
        return ""
    style = dict((k.strip(), v.strip()) for k, v in
                 (p.split(":", 1) for p in attrs.get("style", "").split(";") if ":" in p))
    color = style.get("color", "#000")
    width = float(re.sub(r"[^\d.]", "", style.get("border-width", "2px")) or 2)
    dash = ' stroke-dasharray="%g %g"' % (width * 4, width * 3) if style.get("border-style") == "dashed" else ""
    head = attrs.get("head", "end")
    size = max(10.0, width * 3.5)
    # Shorten the line under each arrowhead so the tip lands on the endpoint.
    dx, dy = x2 - x1, y2 - y1
    length = (dx * dx + dy * dy) ** 0.5 or 1.0
    ux, uy = dx / length, dy / length
    sx, sy, ex, ey = x1, y1, x2, y2
    heads = []
    if head in ("end", "both"):
        ex, ey = x2 - ux * size, y2 - uy * size
        heads.append((x2, y2, ux, uy))
    if head == "both":
        sx, sy = x1 + ux * size, y1 + uy * size
        heads.append((x1, y1, -ux, -uy))
    parts = ['<line x1="%g" y1="%g" x2="%g" y2="%g" stroke="%s" stroke-width="%g"%s/>'
             % (sx, sy, ex, ey, color, width, dash)]
    for tx, ty, hx, hy in heads:
        bx, by = tx - hx * size, ty - hy * size
        px, py = -hy * size * 0.45, hx * size * 0.45
        parts.append('<polygon points="%g,%g %g,%g %g,%g" fill="%s"/>'
                     % (tx, ty, bx + px, by + py, bx - px, by - py, color))
    return '<svg class="x-connector" aria-hidden="true">%s</svg>' % "".join(parts)


def icon_svg(m):
    attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1)))
    name = attrs.get("name", "")
    body = ICONS.get(name, '<circle cx="12" cy="12" r="9"/>')
    style = html.escape(attrs.get("style", ""), quote=True)
    return ('<x-icon style="%s" aria-label="%s"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" '
            'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">%s</svg></x-icon>'
            % (style, html.escape(name, quote=True), body))


def main():
    deck = json.loads((PROJECT / "deck.json").read_text(encoding="utf-8"))
    title = deck.get("title", "Deck")
    fonts = "".join('<link rel="stylesheet" href="%s">' % html.escape(f["href"], quote=True)
                    for f in deck.get("faces", {}).values() if "href" in f)
    slides = []
    for sid in deck["order"]:
        src = (PROJECT / "slides" / (sid + ".html")).read_text(encoding="utf-8").strip()
        src = re.sub(r"<x-connector([^>]*)>\s*</x-connector>", connector_svg, src)
        src = re.sub(r"<x-icon([^>]*)>\s*</x-icon>", icon_svg, src)
        src = re.sub(r"<!--.*?-->", "", src, flags=re.S)
        slides.append(src)
    page = ("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
            "<title>%s</title>\n%s\n<style id=\"deckcss\">%s</style>\n</head>\n<body>\n"
            "<div id=\"stage\">\n%s\n</div>\n<div id=\"hud\"></div>\n<div id=\"notes\"></div>\n<div id=\"grid\"></div>\n"
            "<script>var DECK_TITLE=%s;%s</script>\n</body>\n</html>\n"
            % (html.escape(title), fonts, CSS, "\n".join(slides), json.dumps(title), JS))
    OUT.write_text(page, encoding="utf-8")
    print("wrote %s: %d slides, %d bytes" % (OUT.relative_to(ROOT.parent.parent), len(slides), len(page.encode("utf-8"))))


if __name__ == "__main__":
    main()
