# relay

relay runs a small set of agent roles for one person, the operator. A hub
role takes the operator's requests, hands them to other roles, and adds
roles as jobs need owners. Roles talk to each other in memos, wake
themselves on routines, and reach the operator by notification.

Everything relay knows is an append-only log of events in a data
directory. The state (who exists, what is open, what is known) is the log
folded in order, so it can always be rebuilt, and nothing is ever deleted.
relay itself holds no personal data: the code is this repository, and the
data is wherever `--data` or the config file points.

## How it works

A role is an id, a title and its own standing instructions. Between
wakes nothing runs. `relay tick`, run by launchd once a minute, fires any
routine that has come due, then wakes every role with an open memo, each in
its own process. A wake runs the role's harness ([agentloop](https://github.com/kirahowe/agentloop) on the Anthropic API)
with the shared instructions in `resources/AGENTS.md`, the role's own
instructions, what it has been granted and its notes as the system prompt, and the memos it was woken for as the
prompt.

A role has no files and no shell. It works only through tools, each one a
`relay tool <name>` call made as that role:

- memos: `inbox`, `roles`, `memo_send`, `memo_close`, `memo_block`
- routines: `routines`, `routine_write`, `routine_remove`
- knowledge: `facts`, `fact_history`, `fact_add`, `fact_supersede`,
  `fact_correct`, `notes`, `note_write`, `note_remove`, `journal`,
  `journal_write`, `docs`, `doc_get`, `doc_history`, `doc_put`,
  `doc_remove`
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
`egress` or `act`) and the labels its output carries (see below), and
relay refuses a tool that lacks either. A role on a `:script` harness is
offered no external tools, since nothing polices what a script calls.

An optional `<data>/AGENTS.md` adds the operator's own instructions to
every role's system prompt.

### Labels and holds

relay keeps a role from holding all three parts of the lethal trifecta at
once: the operator's private data, text an outsider could have written,
and a way to send data out. It does this with labels, not by asking the
model to behave.

A session has the labels of what it has seen. `private` comes from
reading anything relay keeps: a memo, fact, doc, note, journal entry or a
routine's instructions. `contaminated` comes from text an outsider could
have written, such as the output of a tool whose `:labels` say so, or
anything a contaminated session wrote. Labels only grow. agentloop
enforces the policy: once a session holds both labels, a call to an
`:egress` tool waits for the operator, and once it is contaminated, so
does a call to an `:act` tool. relay's own tools that change the
registry, routines or dead letters are `act`.

Every event a role's tool call writes carries its session's labels, and
anything that copies text carries the copied text's labels too. Each
version of a fact, doc or note keeps the labels of whoever wrote it, and
every read tool returns the labels of what it shows, so the session that
reads it takes them on. A wake starts with the labels of everything in
its prompts.

Contaminated items are withheld by default. A read tool shows a
placeholder in their place and includes them only when called with
`accept_contamination`. A contaminated memo to a role granted an external
tool of class `act` or `egress` is quarantined: the role is not shown it
and is not woken for it until the operator runs `relay release <memo>`.
A role with no such tool reads a contaminated memo like any other and
becomes contaminated; agentloop then holds its calls to relay's own `act`
tools. `relay trust <kind> <ref>` clears `contaminated`
from something the operator has read and vouches for. It never clears
`private`. `--untrusted` on `relay memo`, `put`, `add`, `update` and
`routine` marks what the operator writes as contaminated, for text pasted
from somewhere untrusted.

When agentloop holds a call, the wake ends and relay records a hold for
it and notifies the operator. The role is not woken again, and its memos
wait without counting the wake against them, until every hold is
decided. `relay holds` lists them with their whole input. `relay approve
<hold>` or `relay deny <hold> [why]` decides one. At the next tick the
role is woken in the same session, which carries out the decisions. In
a conversation agentloop asks the ACP client instead, and `relay acp`
passes the request through.

`relay approve`, `deny`, `release` and `trust` print what they are about
on the terminal (`/dev/tty`, never stdin) and ask for a four-character
code to be typed back there, so no harness or pipe can answer for the
operator.

## Setup

relay needs [babashka](https://babashka.org) on macOS, and agentloop, which
is written in Go.

1. Clone [agentloop](https://github.com/kirahowe/agentloop) next to this
   repository and build it: `make -C ../agentloop build`. relay finds the
   binary at `../agentloop/bin/agentloop`. To keep it elsewhere, put
   `agentloop` on your PATH or set `:bin` in the harness settings.
2. Choose a data directory and initialize it:

   ```sh
   data=/path/to/data
   mkdir -p ~/.config/relay
   echo "{:data \"$data\"}" > ~/.config/relay/config.edn
   bin/relay init
   ```

   `relay init` asks for the settings relay cannot run without: the time
   zone, the model and how to get its API key, and where notifications go.
   Each question has a suggestion that Enter accepts, and nothing is
   written until you have seen the whole `relay.edn` and said yes. relay
   refuses to run with a `relay.edn` that lacks any of them. Run it in a
   terminal; elsewhere, write `relay.edn` yourself from
   `setup/relay.example.edn`.

   Every command takes the data directory from `--data <dir>`, or else
   from `:data` in `~/.config/relay/config.edn`. A leading `~` is
   expanded. With neither, relay says so and stops. There are no
   environment variables.

   To add tools and grants later, edit `relay.edn`. The data directory is plain files, so a file-sync tool
   can back it up, as long as this Mac is its only writer. Avoid iCloud
   Drive, which evicts files to placeholders.
3. Store the Anthropic API key in the login keychain, where the example
   config looks for it:

   ```sh
   security add-generic-password -a "$USER" -s relay-anthropic -w
   ```
4. Add the hub. Its instructions come from stdin. `setup/hub.md` is a
   starting point; change it to suit you:

   ```sh
   bin/relay add hub --title "Hub" < setup/hub.md
   ```
5. Install the dispatcher's launchd job. Fill in the template's paths and
   load it:

   ```sh
   mkdir -p ~/Library/Logs/relay
   sed -e "s|@BB@|$(command -v bb)|" -e "s|@RELAY@|$PWD|" \
       -e "s|@DATA@|$data|" -e "s|@HOME@|$HOME|" \
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
   the ntfy app. Then set `:notify` in `relay.edn` to
   `{:kind :ntfy :url "http://127.0.0.1:2586" :topic "relay"}`, or choose
   ntfy when `relay init` asks.
7. To give roles Apple Reminders, build `tools/rem` (see its README) and
   grant it in `relay.edn`.
8. To let a role read files from one branch of a public GitHub repository,
   such as reports another agent writes there, define `tools/github-file`
   in `relay.edn` with the repository and ref pinned (see its README) and
   grant it.

The harness settings in `relay.edn` are `:kind :agentloop`, `:model`,
`:effort`, `:provider` (`"anthropic"` by default, or `"openai"`),
`:base-url`, `:bin`, and `:api-key-command`, which is required. `:retry`
sets how agentloop retries a failed model call,
for example `{:retries 2 :backoff-seconds 2 :max-wait-seconds 30}`. A rate
limit that outlasts the longest wait pauses the harness until it lifts.
`:api-key-command` is a command and its arguments. agentloop runs it
itself and takes its output as the key, so relay never holds the key. A
missing or wrong key shows up as a needs-attention wake. Tools get only a
base environment (PATH, HOME and the like). There is no setting for the
model's context window: agentloop asks the provider for it, and an ACP
client shows how full the context is when the provider gives one.
Anthropic's API does; OpenAI's does not.

Talk to a role with `relay chat <role>` in this terminal, or from an ACP
client with `relay acp <role>`. `relay status` shows roles, open work,
harnesses and recent wakes, and `relay help` lists every command.

### relay chat

`relay chat <role>` needs [acp-tui](https://github.com/kirahowe/acp-tui),
a terminal client for ACP agents. Build it with `make -C ../acp-tui build`
and put `bin/acp-tui` on your PATH. relay runs it as
`acp-tui -- relay acp <role>`, adding `--data <dir>` when you gave
`relay chat` one, so a chat is an ordinary ACP conversation: `relay acp`
opens it, holds the role and records its sessions.

To use another client, or acp-tui somewhere other than PATH, set
`:chat-client` in `relay.edn` to the command and any arguments that come
before `--`:

```clojure
:chat-client ["/opt/acp-tui/bin/acp-tui" "--agent-log" "/tmp/relay-agent.log"]
```

The client gets `--` and then the agent command as its last arguments. If
relay cannot find the client, it says so on stderr and exits 1.

### ACP clients

`relay acp <role>` speaks the [Agent Client Protocol](https://agentclientprotocol.com)
on stdin and stdout, so an editor or any other ACP client can run it as
its agent command. relay starts the role's agentloop and passes bytes
between the client and agentloop unchanged. A connection holds the role
like a wake: no other wake of that role runs until the client
disconnects, and the wake records the ACP sessions the client used. Their
logs are kept in `<data>/runs/<role>/conversations/`, apart from the
role's wake sessions.

To set up a client, give it the command `relay` with the arguments
`acp <role> --data <dir>`, or leave out `--data` when
`~/.config/relay/config.edn` names the data directory. The client needs
no environment variables and no API key, since the key comes from
`:api-key-command` as usual. relay ignores the client's working directory
and MCP servers, because a role has only relay's tools. If the role is
busy, relay writes the reason to stderr and exits 1 before sending
anything.

`--trace <file>` appends every line in both directions to `<file>`, one
JSON object per line, with `from` set to `client` or `agent`.

## Development

```sh
bb test          # every test; pass namespaces to run only those
bb soak 5000     # the model-based property, many more times
bb tck           # the ACP conformance kit against relay acp (needs Go, uv and ../agentloop)
```

The tests never call the real API. The agentloop tests run the real binary against a
mock Anthropic server in `test/relay/mock_anthropic.clj`.

`relay.core` holds the rules as two pure functions. `evolve` folds an
event into the state. `decide` turns a command into events or refuses it.
Every write goes through `relay.inbound`, which decides and appends under
the log's lock. The model test in `test/relay/core_test.clj` drives random
sequences of every command and checks the rules after each step. The
property in `test/relay/labels_test.clj` writes random histories in which
every piece of text holds a marker tied to its writer's labels, and checks
that wherever a marker turns up later, in an event, a tool's output or a
prompt, its writer's labels came with it.

The tests never read `~/.config/relay/config.edn`, run the `relay` on
PATH, or run the `agentloop` on PATH: they take agentloop only from
`../agentloop/bin/agentloop`.

## License

MIT
