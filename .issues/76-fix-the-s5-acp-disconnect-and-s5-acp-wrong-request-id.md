# Fix the s5-acp-disconnect and s5-acp-wrong-request-id pentest scenarios

## Context

Found 2026-10-23 while verifying relay#75. `bb verify`'s pentest step
fails these two scenarios on every run. `s5-acp-disconnect` fails the
same way at `wmpwutkq`, before any of the dashboard work (relay#73-75),
so the failure predates it. Not investigated yet.

## Acceptance criteria

- Both scenarios pass, or the scenario is shown to be wrong and fixed.
- If relay was at fault, a regression test in `bb test`.

## Log
