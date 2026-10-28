# Omni-Bridge Overview deck

The technical overview deck for engineering leadership and prospective adopters:
what Omni-Bridge is, where it fits against colocation, how it is built, how it is
deployed and monitored, and the latency programme with its measured results.

## Where it lives

- **Source of truth:** the deck on claude.ai, https://claude.ai/artifact/ExGPkpWkaDmUXTTeD6MqAc.
  It is edited and presented there, and its Share menu gives a viewing link,
  a PowerPoint or PDF export, and comments.
- **This folder:** a copy of the deck's files and a self-contained viewer, so the
  deck can be read from a checkout, from GitHub Pages or from any web server
  without claude.ai.

| Path | What it is |
|------|------------|
| `project/deck.json` | Slide order, section outline and the two Google Fonts faces |
| `project/slides/<id>.html` | One slide each: a single `<section>` on a 1920×1080 canvas, inline styles, speaker notes in `<aside>` |
| `build.py` | Inlines every slide into `deck.html` with the CSS and keys the viewer needs |
| `deck.html` | The viewer. Open it in a browser |

## Viewing

Open `deck.html` in a browser. It needs internet access for the two fonts
(Space Grotesk and IBM Plex Sans from Google Fonts); without it the slides fall
back to Arial.

| Key | Action |
|-----|--------|
| Arrows, Space, Page Up/Down, click | Previous or next slide |
| Home, End | First or last slide |
| N | Speaker notes |
| O | Overview grid of all slides |
| F | Full screen |

The URL hash is `#slide-N`, the same numbering as the claude.ai deck, so
`deck.html#slide-22` opens the comparison table. Printing produces one page per
slide.

## Updating this copy

The slides are edited on claude.ai. To refresh this folder, fetch the deck's
`project/` files from the artifact into `project/` here, then rebuild:

```bash
python docs/deck/build.py
```

Commit `project/` and the rebuilt `deck.html` together.
