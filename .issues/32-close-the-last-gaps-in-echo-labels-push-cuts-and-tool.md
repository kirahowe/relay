# Close the last gaps in echo labels, push cuts and tool timeouts

## Context

From the review of relay#25 and relay#29 (otttluqu, vlmmrnwx, 2026-10-17). None breaks a guarantee.

- Some refusals still quote a role's own text with labels []: memo_send's "There is no role called <to>.", `active-role!` from role_update and role_remove, and routine_remove's "<owner> has no routine called <name>.". Each echoes the session's own text back to it, so no label is lost, but the marker is opt-in and these were missed. Mark them with `reject-quoting-call!`, or make the default that any :invalid or :not-found refusal inside a tool call carries the session's labels unless it is marked as fixed text.
- `clip` in notify.clj counts UTF-16 units after escaping, so an ntfy body can end in a split surrogate pair or half an escape such as `\u{20`. Cut it at a code point and never inside an escape.
- A tool that leaves a background child holding stdout defeats `:timeout-seconds`. The timed deref waits for the exit and then waits with no limit on the :out future (verified with babashka.process 1.13.226: `sh -c "sleep 4 & echo hi"` with a 1 s limit returned after 4 s). Bound the :out and :err reads by the same deadline, then destroy-tree and report a timeout. This predates relay#29. Also consider SIGKILL after SIGTERM, since `destroy-tree` only sends SIGTERM.

## Acceptance criteria

- [ ] Every refusal that quotes a role's input carries the session's labels, with a test that would catch a new one missing them.
- [ ] The ntfy body never ends in a split pair or half an escape.
- [ ] A tool with a background child holding stdout is stopped at its limit.

## Plan

Wait for relay-23's quarantined reader to land; it touches tools.clj and config.

## Log
