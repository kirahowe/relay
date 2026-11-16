# Anchor a reply's links only in Markdown text, not in code or tables

## Context

Sent by relay-34 on 2026-11-16, from relay-tui qxlyolmz (relay-tui's
half of relay#130). Cosmetic, not security.

With `relay/inertText`, relay-tui turns each listed broken form in a
reply into `<address>` (render.anchoredSource) before the Markdown is
parsed, without knowing where in the Markdown it stands. Inside a fenced
or indented code block, a table row, or after a backslash, the whole
address then lands on a row as text. `defangLines` catches every such
row and draws it faint and broken, with no hyperlink and nothing
followable, but the row differs from what relay sent.

## Acceptance criteria

- [ ] A listed link is anchored only in a Markdown text node, after
  parsing, so a code block, a table row or an escaped bracket shows
  relay's broken form as sent.
- [ ] A test holds each of those four places to that.

## Plan

Anchor in the renderer's text nodes rather than in the source.

## Log
