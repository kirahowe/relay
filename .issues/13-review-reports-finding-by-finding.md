# Review reports finding by finding

## Context

`relay review` runs at the terminal. Kira goes through each finding with its check results.

## Acceptance criteria

- [ ] `relay review` shows one finding at a time with its flags.
- [ ] Kira chooses accept, reject or not relevant per finding.
- [ ] Accept is a per-finding trust and needs the typed code.
- [ ] Decisions are recorded and survive a restart.

## Plan

## Log

- 2026-10-17: landed on main as xxvskrzm, with the docs in qwssvysx. At the tip: 494 tests and 9/9 scenarios green. Two Opus review rounds; round 2 had no in-scope defect. Its one non-blocking finding is relay#27.
