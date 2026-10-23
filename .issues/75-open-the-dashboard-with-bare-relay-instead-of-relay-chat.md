# Open the dashboard with bare relay instead of relay chat

## Context

Kira, 2026-10-23: "`relay chat` is probably not the right way to open this
now; really it's opening a whole dashboard/command centre type view."

Kira's answer: bare `relay` opens it. `relay help` prints the usage.
`relay chat` stays as an alias for a while so habits and scripts keep
working.

## Acceptance criteria

- `relay` with no subcommand (and `relay --data <dir>`) opens the
  dashboard, as `relay chat` did.
- `relay <role>` is not added: a role name would collide with
  subcommands.
- `relay help` and `relay --help` print the usage.
- README, `resources/AGENTS.md` and `spec/relay.allium` say `relay`.

## Log
