# Polish the morning brief text and share a timed tool runner

## Context

From the review of the morning brief (lsxtyzzw, 2026-10-17). None breaks a guarantee: the push is marked contaminated and its links are broken.

- Collapse runs of whitespace in calendar and reminder titles. A title of up to 80 characters padded with spaces can wrap on the phone so that its tail starts at the left margin and looks like a heading such as `Held calls: 3`.
- Text from cal and rem is escaped twice. The tools already print `\u{200b}`, and relay's `visible` doubles the backslash, so the phone shows `\\u{200b}`. `clip` can also cut through such an escape or a surrogate pair.
- Replace `dispatch/run-brief-tool!` with one public timed runner in relay.tools that both use, so plain external tools get a time limit too. The brief also ignores a tool's `:timeout-seconds`. Wait until relay-23's wake-context work in tools.clj has landed.

## Acceptance criteria

- [x] Titles with whitespace runs render on one short line.
- [x] An escape from cal or rem shows once on the phone.
- [x] The brief and external tools share one timed runner that honours `:timeout-seconds`.

## Plan

## Log

- 2026-10-17: Whitespace: brief/squeeze turns every run of Unicode White_Space (spaces of every kind, tabs, line breaks, U+2028) into one space and trims, on every field of a cal or rem line. Zero-width characters are not whitespace, so they still show as escapes.
- 2026-10-17: Double escaping: decode cal and rem's escapes in relay.brief (brief/unescape) and let the push escape once, rather than teaching relay.text/visible to pass through escapes it did not make. visible stays the single escaper at the edge, so the guarantee that no raw invisible or control character reaches the push is unchanged, and a literal backslash still shows as two, so a title cannot forge an escape. An escape cal's escaper never prints (\q, a trailing backslash, a code point past U+10FFFF) makes the section unreadable rather than guessing.
- 2026-10-17: clip counts code points, so it never splits a surrogate pair. It runs on decoded text, so it cannot cut through an escape. Grapheme clusters (a base and its combining marks) can still be split; that is harmless here.
- 2026-10-17: Runner: tools/run-external! replaces dispatch/run-brief-tool!. It honours :timeout-seconds; with none it uses tools/default-timeout-seconds, 600, the envelope path's existing default (envelope tools now resolve their limit the same way). The brief passes its own default of 60 seconds for a tool with none, since the tick waits on it; a tool that sets :timeout-seconds gets that in the brief too.
- 2026-10-17: On timeout the runner ends the process tree (p/destroy-tree). A plain tool's role is told "The tool X timed out after N seconds.", with the tool's static labels as for any failure; the brief says "the tool X timed out after N seconds" in place of "failed". relay.edn's :timeout-seconds on a tool is now checked to be a positive whole number.
- 2026-10-17: Note for the orchestrator: agentloop's own tool timeout defaults to 300 s, below the 600 s default, so for a role's call agentloop is still the outer bound unless a harness raises it; relay's limit matters most for the brief and for harnesses with a longer limit.
- 2026-10-17: Tests: relay.brief-test (what-cal-and-rem-escaped-is-decoded-once, a-padded-title-stays-on-one-short-line, a-title-is-cut-between-characters, the-brief-honours-a-tool-s-time-limit, with properties), relay.external-tool-test (time limit, process tree, default, role message, config check). README and spec updated.
- 2026-10-17: The Codex review round could not run (usage limit until 2026-10-22). Self-review instead: a decoded line break or tab is squeezed to a space before render, every other decoded control or invisible character is escaped by relay.text/visible at the push, and the log now keeps the decoded text as it keeps any other raw text, escaped at each edge that shows it.
