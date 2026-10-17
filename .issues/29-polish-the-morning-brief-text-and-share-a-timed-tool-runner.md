# Polish the morning brief text and share a timed tool runner

## Context

From the review of the morning brief (lsxtyzzw, 2026-10-17). None breaks a guarantee: the push is marked contaminated and its links are broken.

- Collapse runs of whitespace in calendar and reminder titles. A title of up to 80 characters padded with spaces can wrap on the phone so that its tail starts at the left margin and looks like a heading such as `Held calls: 3`.
- Text from cal and rem is escaped twice. The tools already print `\u{200b}`, and relay's `visible` doubles the backslash, so the phone shows `\\u{200b}`. `clip` can also cut through such an escape or a surrogate pair.
- Replace `dispatch/run-brief-tool!` with one public timed runner in relay.tools that both use, so plain external tools get a time limit too. The brief also ignores a tool's `:timeout-seconds`. Wait until relay-23's wake-context work in tools.clj has landed.

## Acceptance criteria

- [ ] Titles with whitespace runs render on one short line.
- [ ] An escape from cal or rem shows once on the phone.
- [ ] The brief and external tools share one timed runner that honours `:timeout-seconds`.

## Plan

## Log
