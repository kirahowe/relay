# Install relay through Homebrew and remove bb deploy

## Context

Decision 26 in `docs/plans/house.md`: relay, agentloop, relay-tui and
webtool install from Kira's tap (`kirahowe/tap`, github.com/kirahowe/homebrew-tap,
which already holds the editmd and slk casks). relay runs as one
`brew services` service. `bb deploy`, `bb rollback`, `bb service`, the
pause file and the relay.tick and relay.serve plists go. Development
happens in single checkouts on `main`; the installed copy is what runs.

## Acceptance criteria

- [x] `relay run` runs serve and a tick every minute, for `brew services`
  - Receipt: run_test (schedule properties, stand-in serve and tick); the installed keg ran `relay run` on staging data: serve listened, a tick woke clerk, SIGTERM stopped both in 2s
- [x] wakes outlive a service restart, as they outlived a tick
  - Receipt: run_test a-dispatched-wake-leads-a-process-group-of-its-own
- [x] bb deploy, bb rollback, bb service and the pause file are gone, with their tests
  - Receipt: commit lrkuyuvp; bb test green
- [x] the relay.tick and relay.serve plists are gone
  - Receipt: commit lrkuyuvp; ntfy's plist moved to setup/ntfy
- [x] relay records its invoked path, not a Cellar path, wherever it persists one
  - Receipt: bin/relay sets relay.bin from fs/absolutize *file*, and re-execs by that path
- [x] formulas for relay, agentloop, relay-tui and webtool in the tap, installed and tested from a local head
  - Receipt: keg-only scratch tap with file:// heads; brew install --HEAD built all four, brew test passed, then uninstalled and untapped; tap commit 48ca8bc
- [x] README, AGENTS.md, DEPLOY.md, docs/architecture.md describe the brew install
  - Receipt: commit vyyzvnys; spec and resources/AGENTS.md had nothing about deploying
- [x] bb verify green
  - Receipt: bb verify 2026-10-26, all passed: agentloop, relay-tui, webtool, relay (1089 tests), pentest (30)

## Decisions

- Head-only formulas for now (Kira, 2026-10-26). Upgrading is: push
  `main`, `brew upgrade --fetch-HEAD`, `brew services restart relay`.
  Tagged releases come later, for self-hosters.
- ntfy stays on its own plist. Kira agreed to move it to `brew services`,
  but homebrew-core's ntfy formula has no service block, and a custom
  formula around ntfy would be more of our own infra, not less.
- `relay run` supervises: it runs `relay serve` and, once a minute,
  `relay tick` as child processes. A broken relay.edn then stops serve
  but not the tick, which is what reports the broken settings.
- Homebrew's service DSL has no AbandonProcessGroup, so launchd kills
  the job's process group when the service stops. A dispatched wake
  starts in a process group of its own, so a restart does not count as a
  crash against its memos.
- The pause file goes with the deploy. It existed so a deploy could move
  checkouts under a running tick. `brew upgrade` swaps the keg while the
  service runs, and a restart starts the new code.
- The pentest harness found a tick's wakes through the tick's process
  group, to wait for them and to clean them up. Detached wakes broke
  s7, s14 and s17. It now adopts each wake's group from the pid and
  start time in wake/started, checked against the live process, so a
  reused pid is never adopted. Pentest time is unchanged (190s both).
- Review by a fresh Opus agent (Codex out of quota until 2026-10-30).
  Fixed: the stop budget now covers serve's worst case with margin
  (25s, stop_timeout 40) and waits for a running tick; a child that
  cannot start no longer kills relay run; the service retries once a
  minute (throttle_interval 60), so a missing config.edn does not
  restart it every ten seconds; PATH keeps /usr/local/bin as the old
  plists had; a run_test assertion that could flake. Accepted: between
  `brew upgrade` and `brew services restart` the old serve runs from the
  old keg, so DEPLOY.md restarts straight after; wakes outlive a stop,
  as they outlived the old tick job.
- Not security-relevant in AGENTS.md's sense (no labels, holds, tools,
  network or approvals), so one review round, not a Codex loop.

## Results

Commits on local `main` (not pushed): lrkuyuvp "Run relay as one
service and drop bb deploy", vyyzvnys "Describe the Homebrew install
and the switch to it". Tap (`~/code/projects/homebrew-tap`, git, not
pushed): 48ca8bc "Add relay, agentloop, relay-tui and webtool".

Kira's steps are DEPLOY.md's "The switch". relay#71 (bb deploy restarts
serve) and relay#36 (interrupted deploy cleanup) no longer apply.

## The switch (2026-10-26)

Kira switched the live relay over, walked through step by step: old
launchd jobs booted out, checkouts moved to main, brew install from the
pushed tap, the fixed relay.edn put in place, the service started, hub
given the steward text and the daily note. The leftovers (old plists,
~/.local/bin links, deploy records, the .next workspaces) went to the
Trash, and DEPLOY.md, a one-off guide, was removed.

One slip on the way: a re-run of the log export with `>` truncated the
backup before the new relay refused the old relay.edn. It was made
again with the old relay from a temporary workspace at 877fdba.
