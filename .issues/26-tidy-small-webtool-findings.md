# Tidy small webtool findings

## Context

webtool (~/code/projects/webtool, main = ztynurut) has no tracker of its own, so its round-12 review NITs live here.

- The "controls" case in TestParentMemoryBounded is vacuous. Use the invalid-UTF-8 body instead.
- Compact maps U+2029 to a space, which defeats Segments' bidi reset. It fails closed.
- NEL and VT might be safer as a Break.
- The claim in TestParserTokensBounded is broader than what it tests.
- The read-back cap has only 2.5% headroom.

## Acceptance criteria

- [ ] Each item fixed, or closed with a one-line reason.

## Plan

## Log
