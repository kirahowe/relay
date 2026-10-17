# Close the last gaps in echo labels, push cuts and tool timeouts

## Context

From the review of relay#25 and relay#29 (otttluqu, vlmmrnwx, 2026-10-17). None breaks a guarantee.

- Some refusals still quote a role's own text with labels []: memo_send's "There is no role called <to>.", `active-role!` from role_update and role_remove, and routine_remove's "<owner> has no routine called <name>.". Each echoes the session's own text back to it, so no label is lost, but the marker is opt-in and these were missed. Mark them with `reject-quoting-call!`, or make the default that any :invalid or :not-found refusal inside a tool call carries the session's labels unless it is marked as fixed text.
- `clip` in notify.clj counts UTF-16 units after escaping, so an ntfy body can end in a split surrogate pair or half an escape such as `\u{20`. Cut it at a code point and never inside an escape.
- A tool that leaves a background child holding stdout defeats `:timeout-seconds`. The timed deref waits for the exit and then waits with no limit on the :out future (verified with babashka.process 1.13.226: `sh -c "sleep 4 & echo hi"` with a 1 s limit returned after 4 s). Bound the :out and :err reads by the same deadline, then destroy-tree and report a timeout. This predates relay#29. Also consider SIGKILL after SIGTERM, since `destroy-tree` only sends SIGTERM.

## Acceptance criteria

- [x] Every refusal that quotes a role's input carries the session's labels, with a test that would catch a new one missing them.
- [x] The ntfy body never ends in a split pair or half an escape.
- [x] A tool with a background child holding stdout is stopped at its limit.

## Plan

Wait for relay-23's quarantined reader to land; it touches tools.clj and config.

## Log

- 2026-10-17: Echo labels. Inverted the default, wider than first proposed: every core/reject! refusal raised inside a tool call carries the calling session's labels, whatever its reason. :conflict, :forbidden and :busy refusals echo input too ("Memo <id> is not addressed to ...", "<path> would sit where ...", "Fact <id> was already superseded ..."), so limiting it to :invalid and :not-found would have left the same opt-in gap. The labels are the session's own, so a refusal that quotes nothing gains nothing it did not hold. reject-quoting-call! and the :relay/quotes-call marker are gone; the three ex-info throws in tools.clj that echoed a path or fact id (doc_get, doc_history, fact_history) are now reject! :not-found. fact_history's "There is no fact <id>." was a fourth miss, with labels [].
- 2026-10-17: No fixed-text marker was needed. The clock's errors (relay.clock/fail) and input-problem's are not reject! refusals, so they still carry no labels, and relay#25's clock tests pass unchanged.
- 2026-10-17: Test: labels_test `every-refusal-that-echoes-the-call-carries-the-session-s-labels` calls every tool offered to a role with a marker in each text argument, from a session holding private and contaminated, and fails on any error that shows the marker without the session's labels. It also checks that it reaches memo_send, role_update, role_remove, routine_remove, doc_get and fact_add, so it cannot pass by reaching nothing. Checked it fails when run-tool drops the labels for "There is no role called ...".
- 2026-10-17: clip. It now takes whole tokens of text/visible's output (a code point, a \u{...} escape, or an escaped backslash pair) while they fit, so it never leaves half a surrogate pair, half an escape or a lone backslash. The limit is still counted in UTF-16 units, not bytes, as before. Property test `clip-cuts-between-code-points-and-escapes` (500 cases) checks the kept text reads back as a prefix of the original; it fails at once against the old clip.
- 2026-10-17: Timeouts. run-external! and the envelope runner share run-limited: stdout and stderr are read in futures and the exit and both reads are bounded by one deadline. Java cannot find a child that the shell left behind once the shell has exited (it is reparented, so destroy-tree misses it). So each tool starts under `/usr/bin/perl -e 'setpgrp(0,0); exec {$ARGV[0]} @ARGV'` in a group of its own. At the deadline relay sends the group SIGTERM, waits up to 2 s, then SIGKILL. The program is resolved on the base PATH first, so a missing one still says "could not start". The verifier keeps its own bounded reader and destroy-tree; it was not in scope.
- 2026-10-17: Tests in external_tool_test: `sh -c "sleep 30 & echo $! > pidfile; echo hi"` with a 1 s limit returns in under 2.5 s on both protocols, and the sleep's recorded PID is gone. A second test has a child that ignores SIGTERM and checks SIGKILL ends it.
- 2026-10-17: The Codex review could not run (usage limit until Oct 22). I reviewed it myself against the threat model. A tool that calls setsid itself escapes the group, but that is a tool Kira configured, and the output bound still returns at the limit.
