# Fix agentloop's flaky TestGroupLeaderIsASentinel

## Context

agentloop has no tracker of its own, so this lives here.

On 2026-10-24 a `bb verify` run failed in agentloop's `command` package:

    --- FAIL: TestGroupLeaderIsASentinel (2.01s)
        command_test.go:164: the group outlived the call: operation not permitted

The test passed five times in a row when run alone. It probes the
program's process group with `syscall.Kill(-pgid, 0)` and accepts only
ESRCH. EPERM means that some process group with that id exists and
belongs to another user. The test's own group would have answered nil,
so EPERM means its group is gone and the id was reused, which happens
under load. The failed agentloop step also left no agentloop build, so
the relay, scenarios and pentest steps all failed or skipped on
"agentloop not found".

## Acceptance criteria

- [ ] The test treats EPERM as the group being gone, or checks for the
      group in a way that pgid reuse cannot fool.
- [ ] `make test` in agentloop passes.

## Plan

## Log
