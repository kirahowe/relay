# Open the dashboard with bare relay instead of relay chat

## Context

Kira, 2026-10-23: "`relay chat` is probably not the right way to open this
now; really it's opening a whole dashboard/command centre type view."

Kira's answer: bare `relay` opens it. `relay help` prints the usage.
`relay chat` stays as an alias for a while so habits and scripts keep
working.

## Acceptance criteria

- [x] `relay` with no subcommand (and `relay --data <dir>`) opens the
  dashboard, as `relay chat` did.
  - Receipt: bare relay and relay --open <role> open the dashboard (pmlunxsq, ulnrnynl); dispatch-test bare-relay-opens-the-dashboard and help-prints-the-usage-and-opens-nothing; README, usage and spec updated.
- [x] `relay <role>` is not added: a role name would collide with
  subcommands.
  - Receipt: bare relay and relay --open <role> open the dashboard (pmlunxsq, ulnrnynl); dispatch-test bare-relay-opens-the-dashboard and help-prints-the-usage-and-opens-nothing; README, usage and spec updated.
- [x] `relay help` and `relay --help` print the usage.
  - Receipt: bare relay and relay --open <role> open the dashboard (pmlunxsq, ulnrnynl); dispatch-test bare-relay-opens-the-dashboard and help-prints-the-usage-and-opens-nothing; README, usage and spec updated.
- [x] README, `resources/AGENTS.md` and `spec/relay.allium` say `relay`.
  - Receipt: bare relay and relay --open <role> open the dashboard (pmlunxsq, ulnrnynl); dispatch-test bare-relay-opens-the-dashboard and help-prints-the-usage-and-opens-nothing; README, usage and spec updated.

## Log

- 2026-10-23, rename: acp-tui is relay-tui everywhere live in relay (src,
  test, pentest, scripts, bb.edn, docs, spec). Old `.issues/` records keep
  the old name. `src/relay/staging.clj` guards both `~/code/projects/relay-tui`
  and `~/code/projects/acp-tui` as live checkouts, because the old one stays
  on disk until the main session moves it.
- Config: `:client` (default `["relay-tui"]`). `:chat-client` is read as its
  old name only when `:client` is absent, in `config/resolve-config`, and is
  dropped from the resolved config. Reason: the tick reads the same live
  relay.edn. Validation messages name `:client`, also when the bad value came
  from `:chat-client`, so there is one name to fix. A present-but-bad
  `:client` is not rescued by a good `:chat-client`.
- Bare `relay` printed the usage (exit 0) before; nothing relied on it beyond
  `relay help`, `-h` and `--help`, which still print it. The no-command case
  now maps to `chat` in `cli/parse-line` unless a help flag is given. Judgment:
  doing it in the parser makes `command-of` say "chat" for a bare relay, so
  the scenario runner refuses it like `relay chat`. A bare `relay` with no
  data directory fails with the usual "Which data directory?" message rather
  than the usage.
- `relay <role>` is not added, as the issue says; `relay clerk` still prints
  the usage as an unknown command.
- `resources/AGENTS.md` never mentioned `relay chat`, so it has no change.
  The README, `spec/relay.allium` comments, the `relay` usage and the
  "no-approvals" hint now say the dashboard / `relay`. The notification text
  "Open relay chat <role>" is kept: the alias stays and it names the role.
- `:via "chat"` on trust events is log data, not a command name, so it stays.

- 2026-10-25: Kira accepted it while clearing the review backlog. The
  work is on main in every repo it touched, pushed and deployed.
