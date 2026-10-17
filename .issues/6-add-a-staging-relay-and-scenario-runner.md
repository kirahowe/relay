# Add a staging relay and scenario runner

## Context

Development agents need to exercise relay without touching Kira's live data. Staging is a data dir outside Syncthing holding artificial data. Scenario scripts seed it, run `relay tick` by hand against real agentloop (cheap model) or the mock, and assert on the log.

## Acceptance criteria

- [ ] A staging data dir outside Syncthing, built from artificial data only.
- [ ] Scenario scripts seed it, run `relay tick` by hand, and assert on the log.
- [ ] A scenario can run against real agentloop with a cheap model, or against the mock.
- [ ] One bb task runs all scenarios and exits non-zero on failure.
- [ ] The runner never reads or writes ~/Sync/relay or ~/.config/relay.

## Plan

## Log

- 2026-10-17: landed on main (yowqpxsu through xlvnmvsp): the :file notifier, `bb staging init`, and the scenario runner (`bb scenario`, `bb scenarios`, also run by bb test). Review converged at round 9.
