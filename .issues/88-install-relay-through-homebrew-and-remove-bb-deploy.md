# Install relay through Homebrew and remove bb deploy

## Context

Decision 26 in `docs/plans/house.md`: relay, agentloop, relay-tui and
webtool install from Kira's tap (`kirahowe/tap`, github.com/kirahowe/homebrew-tap,
which already holds the editmd and slk casks). relay runs as one
`brew services` service. `bb deploy`, `bb rollback`, `bb service`, the
pause file and the relay.tick and relay.serve plists go. Development
happens in single checkouts on `main`; the installed copy is what runs.

## Acceptance criteria

- [ ] `relay run` runs serve and a tick every minute, for `brew services`
- [ ] wakes outlive a service restart, as they outlived a tick
- [ ] bb deploy, bb rollback, bb service and the pause file are gone, with their tests
- [ ] the relay.tick and relay.serve plists are gone
- [ ] relay records its invoked path, not a Cellar path, wherever it persists one
- [ ] formulas for relay, agentloop, relay-tui and webtool in the tap, installed and tested from a local head
- [ ] README, AGENTS.md, DEPLOY.md, docs/architecture.md and spec describe the brew install
- [ ] bb verify green

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
