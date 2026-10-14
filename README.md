# relay

relay runs a small set of agent roles for one person, the operator. A hub
role takes the operator's requests, hands them to other roles, and adds
roles as jobs need owners. Roles talk to each other in memos, wake
themselves on routines, and reach the operator by notification.

Everything relay knows is an append-only log of events in a data
directory. The state (who exists, what is open, what is known) is the log
folded in order, so it can always be rebuilt, and nothing is ever deleted.
relay itself holds no personal data: the code is this repository, and the
data is wherever `--data` or `RELAY_DATA` points.

## How it works

A role is an id, a title and a config: its standing instructions. Between
wakes nothing runs. `relay tick`, run by launchd once a minute, fires any
routine that has come due, then wakes every role with an open memo, each in
its own process. A wake runs the role's harness (pi on the Anthropic API)
with the shared instructions in `resources/AGENTS.md`, the role's config
and its notes as the system prompt, and the memos it was woken for as the
prompt.

A role has no files and no shell. It works only through tools, each one a
`relay tool <name>` call made as that role:

- memos: `inbox`, `roles`, `memo_send`, `memo_close`, `memo_block`
- routines: `routines`, `routine_write`, `routine_remove`
- knowledge: `facts`, `fact_history`, `fact_add`, `fact_supersede`,
  `fact_correct`, `note_write`, `note_remove`, `journal`, `journal_write`,
  `docs`, `doc_get`, `doc_history`, `doc_put`, `doc_remove`
- the registry, for roles granted `:may-add`: `role_add`, `role_update`,
  `role_remove`
- `dead_letters_retry`, for the dead letter handler and roles granted
  `:may-add`
- any external command the operator defines in `relay.edn` and grants

Every memo stays open until its recipient closes it, saying what it did,
or blocks it, saying why it can't. A memo left open is retried after a
growing wait, and after enough tries it becomes a dead letter and goes to
the hub. A memo to `operator` is pushed to the operator's phone.

### Knowledge

Facts are shared by every role, and each has two kinds of time: when it
holds in the world (`valid-from`, `valid-to`) and when relay recorded each
version of it. When the world changes, the fact is superseded: the old one
ends where the change begins and a new one takes over. When the record was
wrong, the fact is corrected, with a reason, and asking what relay knew
before the correction still gives the old answer. So relay can answer both
"what held in March" and "what did we think held in March, as of last
week".

Notes are a role's own memory and appear in its prompt. A journal is a
role's running log. Docs are versioned shared content for things that
really are files.

`relay export <dir>` writes all of it out as read-only markdown for
browsing. Nothing reads an export back.

### Settings

`<data>/relay.edn` holds the operator's settings: harnesses, where
notifications go, limits, the dead letter handler, external tools and what
each role is granted. `setup/relay.example.edn` shows each of them. No role
can read this file. Every external tool has a class (`read`, `write`,
`egress` or `act`), which nothing enforces yet; it is there for the
security policy to come.

An optional `<data>/AGENTS.md` adds the operator's own instructions to
every role's system prompt.

## Setup

relay needs [babashka](https://babashka.org) and Node.js on macOS.

1. Install the pinned pi: `npm ci`.
2. Choose a data directory and initialize it:

   ```sh
   export RELAY_DATA=/path/to/data
   bin/relay init
   cp setup/relay.example.edn "$RELAY_DATA/relay.edn"
   ```

   Edit `relay.edn`. The data directory is plain files, so a file-sync tool
   can back it up, as long as this Mac is its only writer. Avoid iCloud
   Drive, which evicts files to placeholders.
3. Store the Anthropic API key in the login keychain, where the example
   config looks for it:

   ```sh
   security add-generic-password -a "$USER" -s relay-anthropic -w
   ```
4. Add the hub. Its config comes from stdin:

   ```sh
   bin/relay add hub --title "Hub" < hub-config.md
   ```
5. Install the dispatcher's launchd job. Fill in the template's paths and
   load it:

   ```sh
   mkdir -p ~/Library/Logs/relay
   sed -e "s|@BB@|$(command -v bb)|" -e "s|@RELAY@|$PWD|" \
       -e "s|@DATA@|$RELAY_DATA|" -e "s|@HOME@|$HOME|" \
       -e "s|@LOGS@|$HOME/Library/Logs/relay|" \
       setup/launchd/relay.tick.plist > ~/Library/LaunchAgents/relay.tick.plist
   launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/relay.tick.plist
   ```
6. For notifications on a phone, run an [ntfy](https://ntfy.sh) server.
   Its config names your machine, so keep it outside this repository:

   ```sh
   conf="$HOME/Library/Application Support/relay/ntfy"
   mkdir -p "$conf" && cp setup/ntfy/server.yml "$conf/"
   ```

   Edit its `base-url` and `cache-file`, install
   `setup/launchd/relay.ntfy.plist` the same way as the tick (it uses
   `@NTFY@`, `@NTFYCONFIG@` and `@LOGS@`), and subscribe to the topic from
   the ntfy app. Without `:notify`, notifications appear on this Mac
   instead.
7. To give roles Apple Reminders, build `tools/rem` (see its README) and
   grant it in `relay.edn`.

Talk to a role with `relay chat <role>`. `relay status` shows roles, open
work, harnesses and recent wakes, and `relay help` lists every command.

## Development

```sh
bb test          # every test; pass namespaces to run only those
bb soak 5000     # the model-based property, many more times
```

The tests never call the real API. The pi tests run real pi against a
mock Anthropic server in `test/relay/mock_anthropic.clj`.

`relay.core` holds the rules as two pure functions. `evolve` folds an
event into the state. `decide` turns a command into events or refuses it.
Every write goes through `relay.inbound`, which decides and appends under
the log's lock. The model test in `test/relay/core_test.clj` drives random
sequences of every command and checks the rules after each step.
