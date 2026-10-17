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
- time: `clock`, for the current time and for date arithmetic
- knowledge: `facts`, `fact_history`, `fact_add`, `fact_supersede`,
  `fact_correct`, `notes`, `note_write`, `note_remove`, `journal`,
  `journal_write`, `docs`, `doc_get`, `doc_history`, `doc_put`,
  `doc_remove`
- the registry, for roles granted `:may-add`: `role_add`, `role_update`,
  `role_remove`
- `dead_letters_retry`, for the dead letter handler and roles granted
  `:may-add`
- research: `report_submit` and `reports`, for roles granted `:reports`,
  and `ledger` and `ledger_status` for the others once any role is (see
  Research roles and reports)
- any external command the operator defines in `relay.edn` and grants

Every memo stays open until its recipient closes it, saying what it did,
or blocks it, saying why it can't. A memo left open is retried after a
growing wait, and after enough tries it becomes a dead letter and goes to
the hub. A memo to `operator` is pushed to the operator's phone.

### Time

A wake's prompt starts with the time. A conversation's system prompt says
when the conversation started, in the `:timezone` from `relay.edn`, and
points the role at `clock` for the rest. `clock` takes an `op`: `now`,
`add` (a date plus a span such as `3w`, `2d`, `90m`, `1mo` or `-1d`),
`between` (the days, hours and minutes from one date to another) or `next`
(the next `mon` to `sun` on or after a date, or the nth one). Every answer
names the weekday. A date without an offset is in `:timezone`. The tool is
class read and its answers carry no labels, because they show nothing
relay keeps. The arithmetic is in `relay.clock`, which is pure; only the
tool's edge reads the system clock. Every role, a public one included,
is told times in the operator's zone.

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
notifications go, limits, the dead letter handler, external tools, the
verifier for research reports, an optional weekly budget and what each
role is granted.
`setup/relay.example.edn` shows each of them. No role can read this
file. Every external tool has a class (`read`, `write`,
`egress` or `act`) and the labels its output carries (see below), and
relay refuses a tool that lacks either. A role on a `:script` harness gets
no tools at all, relay's own included: a script is any program, nothing
polices its calls, and it could claim any labels. relay refuses to put a
role granted tools on a script harness, and `relay tool` refuses calls
from one.

An external tool normally gets the call's arguments on its command line,
appended after the configured command with no `--` between them. So a
tool on this plain protocol must refuse flags in the model's part of its
arguments, or the model could override a flag the operator pinned; `rem`
reads its pinned flags first and treat anything after
as the role's command. Prefer the envelope protocol for new tools.
With `:protocol :envelope` it speaks the same envelope protocol as `relay
tool`: the call arrives on stdin as `{input, labels, call_id}`, the labels
being the calling session's, and the tool prints exactly one `{content,
is_error, labels}`. The labels it reports are added to its static
`:labels`. Output that is not exactly one such object (a repeated key, a
key in another case, a label relay does not know, anything else printed),
a non-zero exit or a timeout (`:timeout-seconds`, ten minutes by default)
tells the model only that the tool failed, and what it printed goes to
relay's stderr. An envelope tool may give its input schema as
`:parameters`, which relay checks before running it. cleanroom's `relay-tool`
is one such tool; the example settings show how to add it, with `:labels
["private"]` since every answer comes from the operator's mail.

A typed answer from cleanroom (a date, a number, a record) holds no text
an outsider chose, which is why it can come back clean, but it is still
a value an outsider can influence: the mail decides what date or number
it is. Do not trust such a value for integrity. When it will feed an
`act` tool, ask with `choice` among options you wrote, so the most an
outsider can do is pick the wrong one of your options.

An optional `<data>/AGENTS.md` adds the operator's own instructions to
every role's system prompt, except a public role's (see below).

A role's grants are `:tools`, `:may-add`, `:public` and `:reports`, and
relay refuses any other key, so a typo cannot change what a role may
hold.

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
tools. Writing facts and docs is class `write`, so a contaminated session may
retract, supersede, remove or overwrite them without asking. That never
hides clean content: a read that does not accept contamination shows the
latest version no contaminated session wrote, with a note that a newer
change is withheld. `relay fact revert <fact> <version>` and `relay doc
revert <path> <version>` put an earlier version back, and `relay fact
add` and `relay fact correct` let the operator write facts directly.

A log written before relay kept labels is marked the first time this
relay opens it. Everything roles and the dispatcher wrote before the
mark counts as private and contaminated, since nothing says what it had
seen; the operator's own writing does not. Trust what you have read to
clear it. No wake or conversation session from before the mark is
carried on: the next wake starts afresh.

`relay trust <kind> <ref>` clears `contaminated`
from something the operator has read and vouches for. It never clears
`private`. `--untrusted` on `relay memo`, `put`, `add`, `update` and
`routine` marks what the operator writes as contaminated, for text pasted
from somewhere untrusted.

When agentloop holds a call, the wake ends and relay records a hold for
it and notifies the operator (with the role, the tool and its class,
never the input). That session keeps the memos it carried, without
counting the wake against them, until every hold in it is decided. The
role's other memos go on meanwhile, in fresh sessions, one wake at a
time as ever. `relay holds` lists the held calls, and `relay approve` and `relay deny`
show the same before asking for the code: why the call was held, what
the tool is, where its data would go, what the session has seen and
where each label came from, any characters in the input that could pass
for others (a Cyrillic `а` in `pаypal`), and the whole input. `relay approve
<hold>` or `relay deny <hold> [why]` decides one. Once every call in a
held session is decided, the next tick that finds the role free carries
that session on, with its memos, and agentloop carries out the
decisions.
`relay deny <hold> --end` also ends that session, so the role's next
wake starts afresh with its open memos; the third denied call in one
session does the same, and tells the operator. In
a conversation agentloop asks the ACP client instead, and `relay acp`
passes the request through.

`relay approve`, `deny`, `release`, `declassify` and `trust` print what
they are about on the terminal (`/dev/tty`, never stdin) and ask for a
four-character code to be typed back there, so no harness or pipe can
answer for the operator.

#### Public roles

A role that searches or browses the web on a schedule, for jobs or
grants say, would stall under these rules: the first memo it read would
make it private, the first page contaminated, and every search after
that would wait for the operator. Granting it `:public true` in
relay.edn makes it a public role instead. Its session never holds
`private`, so its egress calls run without asking. It still becomes
contaminated, and its `act` calls are still held.

A public role is shown only public items. An item is public when the
operator wrote it with `--public`, a public role wrote it, or the
operator declassified it. Everything else is invisible to it: not
shown, not counted, with no placeholder, and a fact, doc or memo it
cannot see does not exist for it. It is never given the operator's
`<data>/AGENTS.md`, which may hold private details, and since any other
role's session may have read that file, text a role wrote is never
public: a public role sees, and can send memos to, only the roles
whose id, title and instructions the operator wrote with `--public`,
and its own must be written that way too. `relay add --public` does it
for a new role; `relay update <id> --public --title <t> --instructions`
does it for an existing one, id included. A public role whose own text
is not public is not woken, and the operator is told once, with what to
run; `relay status` says so too. What it writes is public only when it comes
from a session that began while the role was public: relay names the
wake in the tool command it gives the harness. A session that began
public is read for as public even if relay.edn changes while it runs.
It sees a doc only when its path was chosen in public. It may not write
where a doc it cannot see sits, so it can tell that such a path is
taken. The operator cannot put a public doc at a
path first chosen without `--public` either, since no public role could
see it there. Memos, facts, docs and other items are numbered by the
log's one sequence, so the gaps in the numbers a public role sees tell
it roughly how much else happens that it cannot see. And an item it
could see vanishes for it once a session that is not public changes it
(a fact corrected, a doc rewritten), and a fact it can see may say it
was superseded by one it cannot: both say that something changed, never
what. A public role is told times in the operator's time zone, in its
prompts, its tools and `clock`, like any role, and its routines follow
that zone: the zone is not kept from it, so it may go out in a search. Its
own contaminated notes and reads are not withheld from it, since it
has nothing private to leak, and memos to it are never quarantined.

`--public` on `relay memo`, `routine`, `fact`, `put`, `add`, `update` and
on `relay wake --note` marks what the operator writes as public: it may
leave this machine in a web search. A memo, routine, note, journal entry
or wake note for a public role is refused without it. A memo to a public
role from anyone else waits, and the operator is told who sent it to
whom. `relay declassify <memo>` shows it in full and, with the code,
makes it public, minus `private`; `contaminated` stays. The role gets
exactly what you were shown, less the `[contaminated]` marker: the memo
with its URLs broken if it is contaminated, its spacing made plain (runs
of spaces, tabs and anything else that draws as an empty space become one
space, blank lines are trimmed, and the subject is one line), and in
Unicode NFC. So nothing can ride in whitespace, in accents written two
ways, or in what breaking a URL folds together. Declassifying vouches for the visible text
only: a secret spelled out in the choice of words is visible text, and
only reading for it finds it, so declassified memos are best kept
short. A reason given
with `relay deny` to a public role's held call is public too, and the
prompt says so.

relay refuses any tool result for a public role that carries `private`,
and tells agentloop to refuse one too (`forbid_labels`), so a public
role is not granted a tool whose `:labels` include `private`, nor
`:may-add`, nor the dead letter handler's job, nor a `:script` harness.
Either refusal is reported to the operator. A session from before a
role was made public may hold private text that no label records, so
none is carried on once it is: an interrupted wake starts afresh, a held
session is ended with its open calls denied, and conversations are kept
in a directory of their own. A reason the operator gave before the role
was public stays private. A conversation with a
public role is under the same rules, but what the operator types in it
goes to the role as it is, and the role may send it out to the web.

### Research roles and reports

A research role is a public role that searches the web for something
and reports back: a weekly job search, say, or a grant search. Granting
it `:reports {:kinds ["job" "grant"]}` lets it write reports with
`report_submit`. Only a public role may have that grant, and relay.edn
then needs a `:verifier`: webtool's `check`, run on the envelope
protocol (see `setup/relay.example.edn` and webtool's README). relay
runs the verifier itself; no role is granted it.

The rule behind all of it: free text an outsider could have written is
never trusted until the operator has read it. Only typed values can be
cleared without that, and only once the operator has said so.

A report is a summary and 1 to 30 findings. Each finding has a title,
the page it is on, the organization, a kind from the grant, an optional
deadline (YYYY-MM-DD), amount (a number and an ISO 4217 currency code)
and location, a short summary, and 1 to 5 pieces of evidence, each a URL
and a quote from that page. relay checks the schema itself and refuses
anything outside it. The role is told only "Report N submitted; it will
be checked." It never learns how its report fared, so it cannot tune
itself against the checks.

The tick starts `relay verify <report>` for each new report, in its own
process, as it starts a wake. That process runs the verifier on every
URL the report cites, twenty at a time, and records what came back.
relay then works out flags for each finding from the results, the
ledger and the date, all in pure code: a quote not found on its page, a
page that cannot be checked or fetched (and whether the verifier's guard
refused its address, or the page redirected where the verifier will not
go), a redirect to another domain, a punycode or mixed-script host, a
finding on a domain none of its evidence is on, characters that hide or
pass for others in any field, a deadline that is not a date or has
passed, an organization whose name is not in its domain (a weak sign), a
domain relay has not seen before, and phrasing aimed at the reader
("ignore previous", "do the following first", a "SYSTEM:" header,
"click", "email us"). A finding is matched by the
canonical form of the URL it gives, never by where that page redirects,
so a redirect cannot hide it. The canonical form drops the fragment
unless it starts `#/` or `#!`, which single-page sites route by. A
finding that matches a ledger entry, a finding waiting for review, or
one the operator judged not relevant is seen before: it is not shown,
and the review says how many were hidden and what each matched. One
that matches a finding rejected as fake is shown again, flagged. That
holds for one already hidden, too: once the finding it matched is
rejected as fake, it waits for review with the same flag, and `relay
review` says which findings came back. The
verification is contaminated, since it all comes from pages, and keeps
any labels the verifier reports. relay reads at most 8 MB of what the
verifier prints and cuts every string in it to a fixed length. The operator gets one notification with counts:
"Report N from scout: 5 findings, 2 flagged, 1 seen before. relay
review N". A verifier that fails is tried again after a growing wait,
like a memo, and the operator is told the first time and when relay
gives up; `relay verify <report>` tries again by hand.

`relay review` lists the reports waiting. `relay review <report>` goes
through the findings on the operator's terminal, never stdin. Each one
shows every field, marked as an outsider's text; each URL in full with
its registrable domain in brackets (`https://jobs.[example.org]/x`) and
how a punycode host displays; and the flags in plain words. A field of
one line shows a line break as `\u{000a}`, and a summary or quote is
printed line by line behind a `│` gutter, so nothing in a report can
start a line of the screen or pass for its structure. For each,
type a to accept it, r to reject it as fake, n for not relevant, or s to
skip it, then type the code once for the lot. Each decision is bound to
a digest of exactly the fields and flags shown, as `relay trust` is.

An accepted finding becomes a ledger entry: its role, kind, title,
organization, canonical URL, deadline, amount, location, and a status
that starts as new. Its text is the operator's now, so it is not
contaminated, and like everything relay keeps it is private on read.
`relay ledger` lists the entries, and `relay ledger set <entry>
<new|seen|applied|passed>` sets a status. Once any role writes reports,
every role that is not public gets the `ledger` tool, and `ledger_status`
to record a status the operator gives it. A public role gets neither.
It gets `reports` instead, which lists the titles and URLs it reported
before, its own public writing, so it can leave out repeats without
seeing what the operator decided.

Each research role has a trust level. Every role starts at 0, where the
operator reviews every finding not seen before. `relay trust-level
<role> 1`, at the terminal with the code, lets relay accept a finding
unread when nothing flags it or its report and it was not drawn for the
review sample, one finding in five, drawn when the report is checked.
Phrasing aimed at the reader, lookalike or hidden characters, a punycode
or mixed-script host, or a redirect to another domain on any finding
keeps the whole report for review. The code is bound to the record shown,
so a report checked or reviewed in the meantime changes nothing.
Such an entry shows in `ledger` as a line of typed values only, "entry
7: job, deadline 2026-11-30, no amount, status new", and its text stays
contaminated, withheld unless the reader accepts contamination. Typed
values are still open to outside influence: the page chose the date.
Only the operator raises a level. relay lowers it to 0 by itself, says
why and tells the operator, when a report quotes what its page does not
say, cites a page whose address the verifier's guard refused (a private
address or a local name, say, but not a page that redirects somewhere
refused, which is only flagged), or has a finding the operator rejects
as fake. A finding rejected as fake also takes its report's entries that
relay accepted unread out of the ledger, back to review. `relay status` shows each research role's
level and record: reports, findings, flags by kind, and how many were
accepted, rejected as fake or not relevant.

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

   Anyone who can reach the server can read or post to an open topic, so
   lock it down. `setup/ntfy/lockdown <server.yml> <ntfy>` does it for a
   running server and prints the settings to use. The end of
   `setup/ntfy/server.yml` shows the steps: deny all
   access by default, give the phone a user that can only read the topic
   and relay a token that can only write to it, and pick a topic nobody
   could guess. relay sends the token as `Authorization: Bearer`, reading
   it at each push from the output of `:token-command` (any command, like
   `:api-key-command`) or from `:token-file`. The lockdown script keeps it
   in the login keychain, so the setting is
   `:token-command ["security" "find-generic-password" "-s" "relay-ntfy" "-w"]`.
   If you use `:token-file` instead, keep the file readable only by you.
7. To give roles Apple Reminders, build `tools/rem` (see its README) and
   define its two tools (`rem-read`, `rem`) and grant them in `relay.edn`.
8. To let roles read your Apple Calendar, build `tools/cal` (see its README),
   run `tools/cal/cal calendars` once in a terminal to accept the Calendar
   prompt, define the `cal` tool in `relay.edn` with class `:read` and labels
   `["private" "contaminated"]`, and grant it. Invites carry other people's
   text, which is why it is contaminated.

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
harnesses, this week's spend and recent wakes, and `relay help` lists
every command.

### Spend

Each wake records the tokens its model calls used and what they cost in
US dollars, as agentloop reports them on its outcome line. A
conversation records the cost agentloop last reported for each of its
sessions. `relay status` shows what each role has spent this week and
the total, then the total for each of the four weeks before. A week
starts on Monday at midnight in relay's time zone.

A wake on a model agentloop has no prices for has no cost, which is not
the same as costing nothing. relay never counts it as 0. Status shows
how many wakes ran without a cost beside the spend.

An optional weekly budget in relay.edn, `:budget {:weekly-usd 15}`,
sets a warning level for all roles together. relay sends you one
notification when the week's spend reaches 80% of it and one when it
reaches 100%. A wake that passes both at once sends only the 100% one.
The budget never stops or slows anything.

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

Give `:chat-client` an absolute path, and likewise `:bin` in the harness
settings for agentloop. A bare name is looked up on PATH, so whatever
comes first on PATH would run as your chat client, see your
conversations and answer permission requests for you.

### ACP clients

`relay acp <role>` speaks the [Agent Client Protocol](https://agentclientprotocol.com)
on stdin and stdout, so an editor or any other ACP client can run it as
its agent command. relay starts the role's agentloop and passes bytes
between the client and agentloop unchanged. A connection holds the role
like a wake: no other wake of that role runs until the client
disconnects, and the wake records the ACP sessions the client used. Their
logs are kept in `<data>/runs/<role>/conversations/`, apart from the
role's wake sessions.

Every conversation starts empty. A client can open new sessions and
close them, but cannot load, resume or list earlier ones, so no
conversation takes on another's history or labels. What a role should
carry from one conversation to the next goes in its notes, facts and
journal, as it does between wakes.

To set up a client, give it the command `relay` with the arguments
`acp <role> --data <dir>`, or leave out `--data` when
`~/.config/relay/config.edn` names the data directory. The client needs
no environment variables and no API key, since the key comes from
`:api-key-command` as usual. relay ignores the client's working directory
and MCP servers, because a role has only relay's tools. If the role is
busy, relay writes the reason to stderr and exits 1 before sending
anything.

`--trace <file>` appends every line in both directions to `<file>`, one
JSON object per line, with `from` set to `client` or `agent`. The file
holds everything said, so relay makes it readable by you only and refuses
a directory others can write to, such as `/tmp`.

When agentloop holds a tool call in a conversation, it asks the client
with `session/request_permission`. relay passes that request on only when
`relay acp` was started with `--approvals`, which `relay chat` does,
since its client is the operator's terminal. The client's answer goes
back only if it picks an option the agent offered, or cancels; anything
else, and a request still open when the client goes away, is a refusal.
Without `--approvals` (an editor, say), relay refuses the call itself and
notifies the operator. Each request and its answer are recorded in the
log with the role, session, tool and a digest of the input. A message from
the agent too long for relay to read whole (over 8 MB) never reaches the
client: relay refuses it if it is a permission request whose id it can
find, drops it either way, records that, and tells the operator.

## Development

```sh
bb test          # every test; pass namespaces to run only those
bb soak 5000     # the model-based property, many more times
bb tck           # the ACP conformance kit against relay acp (needs Go, uv and ../agentloop)
```

The tests never call the real API. The agentloop tests run the real binary against a
mock Anthropic server in `test/relay/mock_anthropic.clj`.

### Staging data and scenarios

To check a feature end to end without touching your own data, make a staging
directory and run scenarios against it:

```sh
bb staging init [<dir>]            # artificial roles, facts and settings; default ~/code/projects/.relay-staging
bb scenario <name> [--live]        # one scenario from test/scenarios/
bb scenarios [--agentloop <path>]  # every scenario, against the mock model
```

`staging init` refuses a directory in or around `~/Sync` or the data
directory named in `~/.config/relay/config.edn`, and a directory that is not
empty. `--force` empties a directory only if an earlier `staging init` made
it. The staging `relay.edn` uses a `:file` notifier, which appends each
notification to `notifications.log` in the directory and reaches nobody. Its
roles are the hub, the clerk and the scout, a public role. Its `:verifier` is
a stand-in that checks quotes against the pages in the directory's `web/` and
reaches nothing else. No role writes reports unless a scenario's `:settings`
grants it `:reports`.

A scenario is a data file: relay commands to run as the operator, dispatcher
ticks, clock moves, and assertions over the log and state. The header of
`test/relay/scenario.clj` describes the format. Each run makes its own staging
directory and passes `--data` to every command. The ticks run in the runner,
not under launchd. Without `--live`, the model is the mock server answering
from a script in the scenario. With `--live` it is the real model, using the
keychain key, so it costs a little. `bb test` runs the mock scenarios too.

### Behaviour evals

Tests show that the code works. An eval measures whether a role does its job
well: records a closure with both ends, supersedes a fact instead of
correcting it, keeps quiet when nothing needs the operator. An eval case is a
scenario with `:graders` added, and `bb scenarios` still plays it.

```sh
bb eval [<case> ...] [--out <dir>] [--live] [--trials n] [--model m] [--max-cost usd] [--agentloop <path>]
```

Without `--live` the model is the mock server playing the case's oracle
script, which costs nothing and checks the harness. With `--live` it is the
real model, `claude-haiku-5-5` unless `--model` names another, five trials a
case unless `--trials` says otherwise. `--max-cost` stops starting trials once
the spend recorded so far plus the dearest trial so far would pass it.

Every trial gets a fresh staging directory, kept under
`<out>/<run-id>/trials/<case>/<n>/`. `--out` defaults to
`~/code/projects/.relay-evals`, and the same places `staging init` refuses
are refused here. A failed precondition, a step that threw, a wake that ended
needs-attention, limited or unreachable, a model other than the one asked for,
or a spent budget makes the trial an error. It is run once more, then left
out of the pass rate and counted beside it.

A run directory holds `run.json` (the command, the model, relay's and
agentloop's change ids, a digest of the prompt surface and of each case),
`results.jsonl` (one row per scored trial in Inspect AI's sample shape, with
scores `C` and `I`), `errors.jsonl`, `summary.json` (pass rate with a Wilson
interval, pass^k for k of 1, 3 and the trial count, per-grader pass counts,
mean wakes, requests, tokens and cost) and the terminal report. Usage and cost
come from agentloop's transcripts, at the served model's rates. Judge graders
may appear in a case but are not run yet; they are recorded as skipped.

In `bb test`, every case's oracle must pass all its checks and each foil (a
known-bad script) must fail exactly the checks it names.

### Deploy and rollback

These are for you to run, not an agent. Run `bb deploy` from a relay working
copy that has no changes:

```sh
bb deploy [--relay <dir>] [--agentloop <dir>] [--acp-tui <dir>] [--bookmark main] [--record <file>] [--data <dir>] [--paused-externally]
bb rollback                        # same flags
```

The paths default to the live checkouts under `~/code/projects` and must be
three different directories. `--data` defaults to the data directory in
`~/.config/relay/config.edn` and must be a relay data directory (it needs
`relay.edn` and a `log` directory), since it is how the deploy sees running
wakes. A deploy names the
commit `main` points to in each repository and uses only those commits from
then on. It builds them in fresh jj workspaces in a temporary directory and
runs `bb test` and `bb scenarios` there, lists what it will do, and asks you
to type a code on the terminal.

After you confirm, it pauses relay: while
`~/Library/Application Support/relay/paused` exists, `relay tick` does nothing,
and `relay status` shows the file's path, why it is there and how to lift it
(`rm '<path>'`, and `bb rollback` when a deploy made it). It waits until no wake or tick is running (read
from the live log, which `--data` must point at, and from markers the ticks
leave), writes a record, and for each checkout runs `jj new <commit>`, builds
agentloop and acp-tui with `make build`, and finally runs `relay status`
against that data directory. Just before it moves a checkout it looks at it
again, and it stops if anything changed since the plan or jj cannot read it.
It refuses to start if any checkout has changes in its working copy or sits on
a merge. When it all works it removes the pause file. If a step fails after a
checkout was touched (a move that errors or a build that starts counts), relay
stays paused and the message says so. If every checkout got to its target and
only `relay status` failed, the message says that and gives the `rm` command
that lifts the pause. Ctrl-C before any checkout is touched lifts the pause it
made and forgets the check workspaces. Only one deploy or rollback runs at a
time, through a lock on `~/Library/Application Support/relay/deploy.lock`.

A relay from before the pause file does not know it. If the relay now running,
or the one a deploy or rollback would leave in place, has no
`src/relay/pause.clj`, the command refuses until you stop the tick job yourself
(`launchctl bootout gui/$(id -u)/relay.tick`), run it with `--paused-externally`,
and start the job again afterwards.

Records go to `~/Library/Application Support/relay/deploys.edn`, one line each
time something changes: before the first move, after each checkout, and at the
end. `bb rollback` reads the latest deploy record that moved a checkout, or
that failed after touching one, and that no finished rollback has undone. It
moves each checkout back with `jj new <previous>`, but only if the checkout is
still where that deploy left it; a deploy that failed in a rebuild without
moving anything is undone by rebuilding in place. It rebuilds, runs `relay
status`, and lifts the pause. Run it again and it undoes the deploy before. Every run writes its first record before it
pauses relay, so if one is killed before it touched anything (while waiting for
wakes, say), `bb rollback` sees that and only lifts the pause; nothing is moved.
After a partial failure, run it again to finish; it does not lift a pause left
by an earlier failure until it has worked.

## Pentests

`bb pentest` attacks the whole system as built: relay, agentloop,
acp-tui and cleanroom's `relay-tool`, driven from outside through the
command line, ACP and a terminal, the way the operator and an attacker
would reach them. `bb test` checks relay's parts; this checks that they
still keep the lethal trifecta apart when put together. Each scenario
gives the model an outsider's text and assumes it obeys perfectly, then
checks that:

- an outsider's text reaches a role that can post or act only after the
  operator releases it, by any route (a memo, a block explanation, a dead
  letter, a routine, a doc, fact, note or journal entry, a pasted ACP
  resource, mail through cleanroom)
- a session that has seen it has its posts and acts held, and a held call
  runs once, only after a person approves it on a terminal, whatever
  ticks, wakes, crashes and ACP clients do
- approvals cannot come from stdin, a pipe, a forged or misdirected ACP
  answer, or a client that hangs up
- no view the operator reads passes a raw control or format character
  through, and URLs from contaminated text are defanged
- relay refuses malformed tool calls and settings that could lose a label
- clean, everyday use is not held
- a research role sent to an attack page from the red-team corpus ends
  flagged, refused or harmless, as the corpus says it must

Run it from this directory:

```sh
bb pentest                   # every scenario, about two minutes
bb pentest s1 s5-acp-cancel  # by name, or by prefix
bb pentest --list            # the scenarios and what each needs
bb pentest --jobs 4          # several at once, about a minute
```

It takes agentloop, acp-tui and cleanroom from the sibling checkouts
(`../agentloop/bin/agentloop` and so on), or from `--agentloop`,
`--acp-tui` and `--cleanroom`, never from PATH. It takes webtool's
fixture build from `../webtool/bin/webtool-fixture` or `--webtool`; the
release build does not count. `--build` builds a
sibling that is missing. A scenario that needs a missing piece, or a
cleanroom without `relay-tool`, is reported as SKIP with the reason. It
needs a python3 (`/usr/bin/python3`, or `--python`) for `pty/drive.py`,
which starts its processes and drives terminals. `--keep`
keeps each scenario's directory to look at afterwards, and `--verbose`
prints every check. Each scenario prints PASS, FAIL or SKIP, with the
checks that failed, and the command exits 1 if any failed.

Everything it uses is artificial. Each scenario gets a fresh temporary
data directory, a scripted stand-in for the Anthropic API on localhost
(`test/relay/mock_anthropic.clj`) with a dummy key from `/bin/echo`, a
stand-in ntfy server, and stand-in tools (`pentest/standins/`) for
Reminders, the web and the mail source, which only write to files in that
directory. It never calls a model, touches Reminders, the keychain or
launchctl, or reads `~/.config/relay`, and it refuses to start if any
path it would use lies under `~/Sync` or `~/.config/relay`. Every
process it starts leads a process group of its own, and what relay forks
from it (a tick's wakes, agentloop) stays in that group. When a scenario
ends, the harness signals only the groups it recorded (TERM, then KILL),
fails the scenario if `ps` still shows a process in one of them, and
removes the directory. It never chooses a process to kill by its command
line. The one other kill is the crash scenario's: agentloop, by PID,
found as the child of a relay process the harness knows.

The code is in `pentest/`. `relay/pentest/env.clj` builds a scenario's
world and drives relay, `relay/pentest/acp.clj` is an ACP client that can
misbehave, `pty/drive.py` starts processes in new groups and runs commands
on a pseudo-terminal (babashka can do neither), and
`relay/pentest/scenarios/` holds the attacks. To add a scenario, add a
map to the `scenarios` vector of one of those namespaces, or of a new one
listed in `relay/pentest/main.clj`:

```clojure
{:id "s11-my-attack"            ; the name bb pentest takes
 :title "what it shows"
 :needs #{:agentloop :python}   ; also :acp-tui, :cleanroom, :webtool
 :before (fn [env] env)         ; optional: start what relay.edn names
 :config (fn [cfg env] cfg)     ; optional: change relay.edn
 :run (fn [env]
        (e/memo! env "hub" "Subject" "Body")
        (e/script! env "hub" [{:tool-uses [{:name "web_read" :input {:args ["x"]}}]}
                              {:text "done"}])
        (e/wake! env "hub")
        (e/check! env "nothing went out" (empty? (e/egress env))))}
```

The red-team corpus for research roles is in `pentest/corpus/`: attack
pages in `pages/` and `manifest.edn`, which gives each page its
technique, MITRE ATLAS technique ids, source and licence, and expected
outcome. Each entry is a scenario, `s12-<id>`, run by
`relay/pentest/scenarios/corpus.clj`. A local server serves the pages on
loopback, in a process group of its own. A public role, scout, fetches
and searches with webtool's fixture build, which may reach only that
server, and obeys the page: it follows its links, tries the tools it
names, and submits a report quoting it. relay checks the report with
webtool's `check` as its verifier. Flagged means the verification raises
the flags the entry names, so no finding goes into the ledger unread.
Refused means webtool or relay refuses the calls the page asked for.
Harmless means the page's text never reached the model, or what it got
the role to do carries nothing private and stays marked as an outsider's.
Every finding there is on 127.0.0.1, a domain relay has not seen and
whose name holds no organization's, so each always carries those two
flags; `control-clean` shows they are all a plain page gets. To add an
attack, add a page and an entry to the manifest.

Many attacks are tables: the channels in `injection.clj`, the writes in
`contamination.clj`, the payloads and views in `display.clj`, the client
behaviours in `acp.clj`, the envelopes and settings in `strict.clj`.
Adding a case to one of those is one entry.

## License

MIT
