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

- 2026-10-17: fixed in webtool tsqqnrmu, with lmwvtlrk on top, both on webtool main. Vertical tab reads as a space again: treated as a break, it made pages too large for the read-back cap, and it hides nothing. NEL and U+2029 end the bidi paragraph. U+2028 breaks the text. One Opus review round had no in-scope defect.
