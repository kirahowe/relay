# relay

relay runs a small set of agent roles for one person, the operator. A hub
role takes the operator's requests, hands them to other roles, and adds
roles as jobs need owners. Roles talk to each other in memos, wake
themselves on routines, and reach the operator with messages.

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
prompt. The prompt ends with the role's last 3 journal entries, each cut
at 600 characters, and the fact topics with how many facts hold now in
each, so a role does not spend its first model calls looking them up.
They go through the same filters as the `journal` and `facts` tools: a
public role sees only public items, and a house role sees them all. The
wake's labels are those of everything its prompt holds.

A role has no files and no shell of its own. It works only through
tools, each one a `relay tool <name>` call made as that role:

- memos: `inbox`, `roles`, `memo_send`, `memo_close`, `memo_block`
- routines: `routines`, `routine_write`, `routine_remove`
- time: `clock`, for the current time and for date arithmetic
- knowledge: `facts`, `fact_history`, `fact_add`, `fact_supersede`,
  `fact_correct`, `notes`, `note_write`, `note_remove`, `journal`,
  `journal_write`, `docs`, `doc_get`, `doc_history`, `doc_put`,
  `doc_remove`
- the board, for roles that are not public: `cards`, `card_add`,
  `card_op` (see Cards)
- the agent's own calendar, for roles that are not public: `calendar`,
  `calendar_add`, `calendar_update`, `calendar_remove` (see The
  calendar)
- the registry, for roles granted `:may-add`: `role_add`, `role_update`,
  `role_remove`
- `dead_letters_retry`, for the dead letter handler and roles granted
  `:may-add`
- `sh`, for a house role granted `:shell`: a command run in the steward's
  shell, a Linux container with no network (see The steward's shell)
- `files_move`, for a house role granted `:files`: moves and renames
  inside the folders you chose, which you can undo (see Organizing files)
- any external command the operator defines in `relay.edn` and grants

Every memo stays open until its recipient closes it, saying what it did,
or blocks it, saying why it can't. A memo left open is retried after a
growing wait, and after enough tries it becomes a dead letter and goes to
the hub. A role tells the operator something with `tell_operator`, and a
memo to `operator` reaches them the same way (see Messages to you).

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

A `facts` search ranks the facts by how well their text matches, best
first, with Okapi BM25 over Porter2 stems (`relay.search`). Equal scores
go by fact id. The topic, time and contamination filters apply before the
ranking, so a fact left out never moves the others.

Notes are a role's own memory and appear in its prompt. A journal is a
role's running log, and its tail appears in each wake's prompt. When a
wake is cut short at its deadline before the role wrote an entry, relay
writes one for it, saying when and which memos it had. It quotes their
subjects, so it carries their labels. Docs are versioned shared content
for things that really are files. A role's skills are docs, and every item
a read tool shows has a ref (below).

#### Refs

A ref names one thing relay knows, as `[[kind:id]]`. The kinds are `fact`
(`[[fact:12]]`), `doc` (`[[doc:hear-here/style.md]]`, or a version with
`@3`), `note` (`[[note:hub/venues]]`, role and name), `memo`, `journal`,
`card` (`[[card:1840]]`, a card on the board), `shown`
(something the operator was shown, `[[shown:2026-10-18/call-9]]`), `rem`
(a reminder), `file` and `web`.
The shared instructions require a role to put a ref after every claim it
draws from relay's knowledge or a source. The tools `facts`,
`fact_history`, `docs`, `doc_get`, `doc_history`, `notes`, `journal`,
`inbox` and `cards` print each item's ref beside it. A path or
role id that someone else chose is printed in a ref only when the item's
own labels already cover it.

`relay.refs` is pure. `parse` finds every ref in a text and never throws.
`ref` and `doc-ref` write one, escaping brackets, percent signs and line
breaks in the id so that `parse` reads back what was written.
`resolve` takes the state and a ref and returns `{:ref :kind :status
:title :text :labels}`. The status is `:ok` when the item exists,
`:missing` when it does not, was removed or is not a ref, and `:external`
for `rem`, `file` and `web`, which relay cannot look up. An external
result carries the id as `:target` for the client to open. A client uses
`resolve` to mark the refs in an answer that do not resolve. In
messages, `relay.refs/plain` writes a ref as short text.

#### Skills

A skill is a doc at `skills/<role id>/<slug>.md`. Its first line is `#
Title`, a later line starts `Use when:`, and the procedure follows. A
role's system prompt lists its skills by title, path and use-when line,
under the same filters as its journal and facts, and the wake carries
their labels. A public role is listed its public skills only. The shared instructions tell a role to look
for a matching skill before multi-step work, to write one after it first
finishes work that will recur, and to update it when the operator corrects
it or edits its draft.

A role writes or removes docs under `skills/` only for its own id. `relay
put` and `doc_put` by any other role refuse the path. The operator may
edit any skill. Skills shared between roles are not built.

`relay export <dir>` writes all of it out as read-only markdown for
browsing. Nothing reads an export back.

#### Cards

A card is a small structured thing on your board that you work through
over days, or a status worth a glance. Roles make them, mostly the hub,
and you change them from the command line or a client's pane. There are
three kinds, and the set is closed:

- a checklist: items, each done or not
- a tracker: rows and columns, each cell open, done or skip, such as
  accounts by month
- a note: one text of up to 8000 characters

There is one board, and every active card is on it, at most 30 at once.
A card's id is the seq of the event that added it. Its items, rows and
columns get small numbers of their own, counted per card from 1, which
relay assigns and writes into the event. A title and every item, row and
column is one line of 1 to 200 characters. relay counts characters as
Unicode code points, so an emoji is one, in a note too. A checklist has at most 100
items, a tracker 40 rows and 24 columns.

Cards are private only. A public role is not offered the card tools, and
relay refuses a card command from a public role's session. A change to a
card never wakes a role and never notifies you; a role sees the new state
the next time it reads.

A card lives in the log as `card/added` and `card/changed` events, and
everyone changes it the same way: with an op, `{op, item, row, column,
state, text, seen}`. relay decides the op against the card and appends
the change, and every client redraws from what relay sends back. The ops
are `retitle`, `archive` and `restore` for any card; `add`, `tick`,
`untick`, `edit` and `remove` for a checklist; `set`, `add_row`,
`add_column`, `rename_row`, `rename_column`, `remove_row` and
`remove_column` for a tracker; and `write` for a note. An op on an
archived card is refused, except `restore`. An op that changes nothing
appends nothing and says so. `seen` is the card's rev when the client
last read it, which the header of its text form shows: an edit of text that changed after `seen` is refused, so a
stale edit cannot overwrite a newer one, and an archive of a card that
changed in any way after `seen` is refused, so the card archived is the
one the client showed. A `seen` past the card's rev
is refused too, since no client can have read it. Ticks and cell states go to
whoever wrote last.

Each piece of a card's text (the title, an item, a row or column, the
note) keeps the labels of whoever last wrote it. Reading a card adds
private and the labels of the text it shows now, so removing a
contaminated item, or writing it again yourself, cleans the card. Ticks,
cell states, archive and restore carry no text and add no labels.
Whatever you write on a card carries no labels, whichever way you write
it.

A wake's prompt, and a conversation's, lists the active cards after the
fact topics, one line each with the card's progress, and the wake
carries their titles' labels.

```
relay cards [--archived]
relay card 1840
relay card 1840 set --row 1 --column 3 --state done
relay card 1851 edit --item 2 --text "Download the statements" --seen 1852
relay card add checklist --title "Before the trip" --item "Passport" --item "Charger"
```

A card prints as its text form, which is also what roles read:

```
Card 1840: Catch up monthly financials (tracker, 6 of 24 done, rev 1842) [[card:1840]]
Columns: 1 Jun, 2 Jul, 3 Aug, 4 Sep
Row 1 BMO: Jun done, Jul done, Aug open, Sep open
Row 2 RBC: all open
```

### Settings

`<data>/relay.edn` holds the operator's settings: harnesses, where
messages go, limits, the dead letter handler, external tools, an optional weekly budget, an optional
morning brief and what each role is granted.
`setup/relay.example.edn` shows each of them. No role can read this
file. Every external tool has a class (`read`, `write`, `edit` or
`egress`) and the labels its output carries (see below), and
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
Every external tool may run for its `:timeout-seconds`, ten minutes by
default. The limit covers its output too, so a child left holding stdout
open does not keep it running. relay starts each tool in a process group
of its own and, at the limit, sends the group SIGTERM, then SIGKILL two
seconds later if anything is left. It tells the model the tool timed out.
With `:protocol :envelope` it speaks the same envelope protocol as `relay
tool`: the call arrives on stdin as `{input, labels, call_id}`, the labels
being the calling session's, and the tool prints exactly one `{content,
is_error, labels}`. The labels it reports are added to its static
`:labels`. Output that is not exactly one such object (JSON that breaks
the strict rule described under ACP clients, such as a repeated key or
bytes that are not UTF-8; a key in another case; a label relay does not
know; anything else printed),
a non-zero exit or a timeout tells the model only that the tool failed,
and what it printed goes to relay's stderr. An envelope tool may give its input schema as
`:parameters`, which relay checks before running it. cleanroom's `relay-tool`
is one such tool; the example settings show how to add it, with `:labels
["private"]` since every answer comes from the operator's mail.

A tool can also come from an [MCP](https://modelcontextprotocol.io)
server that agentloop runs over stdio. `:mcp-servers` in relay.edn names
each server, and a tool with `:protocol :mcp-stdio` names its `:server`
and the server's own name for it, `:tool`:

```clojure
:mcp-servers {"files" {:command ["/usr/local/bin/files-mcp" "--token-file" "{credentials_file}"]
                       :credentials-command ["security" "find-generic-password" "-s" "files" "-w"]}}
:tools {"file_read" {:protocol :mcp-stdio :server "files" :tool "read"
                     :class :read :labels ["private"]}}
```

The tool takes its class and labels from relay.edn, like any other, and
is granted by its name in relay.edn. Its server gives its description
and schema, so it has no `:description`, `:parameters` or `:command`.
agentloop calls the server itself, and `relay tool` refuses a call to
it. A `:digest`, the SHA-256 of the definition, makes agentloop refuse a
server that offers any other; `:allow-content` lets results hold
`"image"`, `"audio"`, `"resource_link"` or `"resource"` content, which
otherwise fails the call; `:send-labels true` on a server shows it the
session's labels. A server gets only PATH and HOME. A secret comes from
its `:credentials-command`, which agentloop runs and writes to a private
file that the server's `:command` names as `{credentials_file}`, so relay
never sees it. relay checks all of this as agentloop does, and refuses a
server that no tool uses. A role's request starts only the servers its
own tools come from. The harness's `:tool-timeout-seconds` (five minutes
by default) bounds each call, MCP or not; relay always sends it, so
agentloop's own default for MCP servers never applies.

relay holds each MCP tool to one definition, as SSH's known_hosts holds
a host to one key. The first wake that uses the tool records the SHA-256
of its definition (from agentloop's `pins` entry), and from then on every
request, for any role and any conversation, names that digest, or the
tool's `:digest` when relay.edn gives one. agentloop refuses a server
that offers another definition, and also refuses one that differs from
what a session itself pinned. So a changed description or schema never
reaches a model until the operator accepts it. What this does not cover:
the first definition is taken on trust, and a conversation opened before
any wake has used the tool gets whatever the server offers then.

A run whose server will not start, or whose tool's definition changed,
runs nothing and ends needing the operator. That is the role's trouble,
so its harness is not paused and other roles go on. A server that would
not start is tried again after the usual doubling wait, in the same
session, or now with `relay wake <role>`; relay tells the operator once,
with what agentloop said, and `relay status` shows it on the role. A
changed definition is taken: relay records the new digest, tells the
operator once that the tool changed, and the role's next wake starts a
new session with it and the role's open memos, since a session that
pinned the old definition could not go on. Every other role then gets
the new definition too. If it changes again before the role has run
since, as it does for a server whose description carries a timestamp,
relay still takes it but waits the usual doubling time before trying
the role again, and says nothing more. When relay.edn's `:digest` is what refuses it,
relay never moves that pin: the role is tried again after the usual
wait, and the message says to set the new digest there.

A server can also be remote: a service on the internet that speaks MCP
over HTTP, such as MailerLite's at `https://mcp.mailerlite.com/mcp`. You
do not write one into relay.edn by hand. `relay connect <name> <url>`
signs relay in, shows you the server's tools and writes
`<data>/connections/<name>.edn`, and `:connections ["<name>"]` in
relay.edn loads it. Each tool that is on becomes a tool called
`<name>_<tool>` with `:protocol :mcp-http`, granted by that name like any
other. No role can read the connection file. Setup step 9 walks through
it.

The sign-in is OAuth 2.1 with PKCE. relay finds the server's
authorization server from the server's metadata, registers itself there
when the server allows that (otherwise it asks you for a client id), and
opens your browser. The browser comes back to relay on 127.0.0.1, at a
port chosen for this sign-in, and relay checks that the answer is to its
own request. Each sign-in has a redirect of its own, and relay talks to
one authorization server only: one that says it names itself in its
answers (RFC 9207) must do so every time, and one that does not must
keep its sign-in and token addresses on its own origin, or relay
refuses it before opening the browser. Every token is good only for
that server. The refresh
token and the current access token go in your login keychain as
`relay-mcp-<name>`, never in a file. The connection's
`:credentials-command` is `relay mcp-token <name>`, which prints an
access token and renews it first when it has less than ten minutes left.
agentloop runs it at the start of each wake, and again if the server
refuses the token. Two runs never renew at once.

relay proposes each tool from what the server says about it. A tool the
server says only reads is `:read` and on. Any other tool is `:edit` and
off: turn it on in the file once you have checked that it changes only
your own things and that you can undo what it does, such as a draft. A
tool whose name says it deletes something starts off too. Every tool's
labels are `["contaminated" "private"]`: your data, which other people
may have written. connect asks nothing about any of this: it shows you
the tools and what each may do, then writes the file. To change a tool's
class, labels or whether it is on, edit the file afterwards. relay pins
each tool's definition as you saw it when you connect, so nothing is
taken on trust. A changed definition is taken as for any MCP tool, and
connecting again pins what you see then and keeps the choices in the
file.

Any MCP tool, in relay.edn or a connection file, can narrow what the
model may send. `:fixed-args {"send_now" false}` sets arguments to the
given values on every call, and `:hidden-args ["reply_to"]` names
arguments the model may not give. The model does not see either in the
tool's schema, and agentloop refuses a call that names one before the
call is sent. A fixed value is yours, so no role can change it.

A typed answer from cleanroom (a date, a number, a record) holds no text
an outsider chose, which is why it can come back clean, but it is still
a value an outsider can influence: the mail decides what date or number
it is. Do not rely on such a value for integrity.

An optional `<data>/AGENTS.md` adds the operator's own instructions to
every role's system prompt, except a public role's (see below).

A role's grants are `:tools`, `:may-add` and `:public`, and relay
refuses any other key, so a typo cannot change what a role may hold.

### What you paste

Whatever you type, paste or attach is yours, and relay trusts it. It
still cleans the text where it comes in, because a paste can carry
characters you cannot see: zero-width runs, text-direction overrides,
tag characters that spell out a message, terminal escapes. relay removes
those, turns unusual line separators into line breaks, puts the text in
Unicode's standard form (NFC), and tells you each change in one line,
such as `Removed 3 invisible characters from what you pasted.` A word that
mixes alphabets (a Cyrillic "а" inside a Latin word) is kept and
flagged. Printable ASCII is never touched, and cleaning a text twice
changes nothing more.

This happens in the command line's writes (`relay memo`, `put`, `add`,
`fact`, `card`, `wake --note` and the rest, with the lines on stderr), in
a conversation's prompt blocks and embedded resources (the lines arrive as
a short message at the start of the turn, marked `_meta` `relay/notice`),
in card edits from a client (the answer's `notes`), and in mail from your
own verified address: a reply to a message, or anything you forward to
the agent's mailbox. Nobody is at a terminal when mail arrives, so the
lines go at the end of the memo the role gets, such as `Removed 1
invisible character from the operator's mail.` Mail from anyone else is
left as it came, since it is already marked as from outside. Code that
adds another way in calls the same function, `relay.text/sanitize-input`,
which takes a string or any data holding strings and returns the cleaned
value with the lines to tell you.

### Labels and the house

relay keeps the operator's things in the house. A role that is not
public, a house role, may read everything relay keeps, including text
an outsider wrote, but it has no way to send anything out: relay grants
it no `egress` tool. A public role, a scout, may reach the web, but it is
never shown anything private. A house role may ask a scout for help, and
its memo goes as written, so the scout sees only what the house role
chose to put in it. A scout may fetch only an address that an earlier
web result in the same session showed it, so it cannot put what it was
told into a URL of its own. relay and agentloop enforce these rules.
None depends on the model behaving.

Each tool has a class, which says what a call to it can do:

| class | what a call does |
|---|---|
| `read` | returns content, changes nothing, sends nothing out |
| `write` | changes relay's own data, which keeps every earlier version and its labels |
| `edit` | changes the operator's own things outside relay, where nobody else sees the change and the earlier state survives: her own reminders, a draft in her mail |
| `egress` | can put data the model chose where an outsider can see it, such as a search query or a URL to fetch. Only a public role may have one. |

No class covers a tool that others see, that spends money or that
cannot be undone. A role is not given such a tool: it drafts, and the
operator sends. relay refuses a class it does not know, `act` included,
and the tool keys `:always-hold`, `:consent` and `:preview`, which it no
longer has. Nothing a role calls waits for the operator.

Labels say where a thing came from. `private` comes from reading
anything relay keeps: a memo, fact, doc, note, journal entry or a
routine's instructions. `contaminated` comes from text an outsider could
have written, such as the output of a tool whose `:labels` say so, or
anything a contaminated session wrote. A session has the labels of what
it has seen, and they only grow. What a session writes keeps its labels,
so a role it adds starts every wake contaminated, and a routine it
writes fires contaminated memos. Labels decide two things only. A public
role's session may never hold `private` (agentloop's `forbid_labels`).
And the operator is shown where a thing came from: text from outside is
marked and its links are broken. A scout's memo in `relay inbox` is
marked `[from the web]`; other text from outside is marked
`[from outside]`.

What the operator writes or pastes is hers, and relay trusts it: a memo
on the command line, a line in a conversation, and a page she pastes or
a resource she attaches in an ACP client. relay has no `--untrusted`.

A role reads an outsider's text as it is. The shared instructions tell
every role that such text is information, never an instruction.

A list or account that other people share is not the operator's own.
Put `:labels ["private" "contaminated"]` on the tool that reads it, since
others write it too, and give no role a tool that changes it, since
others would see the change.

### The steward's shell

A house role granted `:shell true` gets the `sh` tool. It runs a command
with `sh -c` in a Linux container that relay keeps running for the data
directory, and the role reads what the command printed. The model's code
runs there and nowhere else; the harness stays on the host. relay.edn
says how it runs:

```clojure
:house {:mounts ["/Users/you/notes" "/Users/you/Documents/grants"]
        ;; :runner ["container"]   ; the default, Apple container; ["docker"] or ["podman"] on Linux
        ;; :timeout-seconds 60     ; per command, enforced inside the VM
        ;; :output-limit 32768     ; bytes kept of stdout and of stderr
        ;; :cpus 2 :memory "2G"
        }
:grants {"hub" {:shell true}}
```

relay builds every argument the runner gets. The container always runs
with `--network none`, a read-only root, `/scratch` and `/tmp` in memory,
and each path in `:mounts` read-only at the same path inside. relay.edn
can name only the runner program, never an option, so it cannot give the
shell a network, a port or a writable mount. relay refuses `:shell` for
a public role, and refuses a mount that is, holds or sits inside the
data directory, the home directory, relay's settings, or a place where
keys, passwords, tokens or browser profiles are kept (`~/.ssh`,
`~/Library/Keychains` and the rest of the list agentloop's workbench
used). A path under `/System/Volumes/Data`, the same files by another
name on macOS, is checked as the path it stands for. relay checks again
when it starts the container, with symbolic links resolved and by file
identity.

The container and its image are named after the data directory, so two
data directories never share one. `relay house build` builds the image
from `resources/house/Containerfile` (Alpine with coreutils, find, grep,
rg, awk, sed, python3, sqlite3, jq, pdftotext, file, tar and zip); that
needs the network, running it does not. `relay house up` starts the
shell, `relay house down` removes it with its scratch files, and `relay
house status` says how it is. The tick starts it when it is missing or
when `:house` has changed, and so does the first `sh` call.

What a command prints shows your files, so it is labelled `private`.
`/scratch` lasts until the container restarts, so every result also
carries the labels of every session that ran a command since then: text
a session read in a stranger's mail and left in scratch is still from
outside when a later session reads it. relay reads those labels when a
command starts and again when it ends, so a command that runs while
another session writes to scratch carries that session's labels too.

### Organizing files

A house role granted `:files` gets the `files_move` tool, which moves and
renames files and folders inside the folders you name:

```clojure
:grants {"steward" {:shell true
                    :files ["/Users/you/Downloads" "/Users/you/Documents/Taxes"]}}
```

These writable folders are separate from the shell's `:mounts`, which
stay read-only. A folder can be in both, and then the steward can look
through it with `sh` before it plans the moves. The model never touches
the disk: relay's own code makes each move on the Mac.

relay refuses `:files` for a public role, and refuses a folder that is,
holds or sits inside a secret, the home directory, the data directory or
the data volume, by the same checks as the shell's mounts, again with
symbolic links resolved when a call runs. Every path in a call is taken
with its links resolved and must lie inside one of your folders. relay
never moves a symbolic link, a folder that holds one, or one of your
folders itself. A move never overwrites: one whose target exists is
refused. It never deletes and never copies, so a move to another disk is
refused. The folders a target needs are made. relay checks every move in
a call before it makes any, and if one is refused, none is made.

Each call is a move in the log with a number, recorded before anything
moves and again after with what moved, a fingerprint of each thing
moved, and the folders it made. Both carry the session's labels, so you
can see where a call came from. `relay moves` lists them. `relay undo
<move>` puts one back exactly, and removes the folders it made once they
are empty. If anything has changed since (you edited a moved file, moved
it again, or put something new where it was), undo says what changed and
touches nothing. A folder it made that now holds something of yours is
kept.

#### What you are shown

A tool's result can carry a display: a title, a Markdown body and
sources, for you alone. agentloop keeps it out of the model's context,
and the role is told only its ref, such as
`[[shown:2026-10-18/toolu_01abc]]`. relay keeps each display as a doc at
`shown/<date>/<call>.md`, written as the role, with the display's labels
and its session's, so a display drawn from a web page is contaminated.
Nobody writes under `shown/` any other way. A wake that showed you
something tells you the paths in a message, never the text. Read
one with `relay doc <path>`. An envelope tool gives a display as a
`display` field beside `content`, with `title`, `body`, `labels` and
optional `sources`, each a `title` and a `ref`.

#### Public roles

A role that searches or browses the web on a schedule, for jobs or
grants say, needs a way out, and a house role has none. Granting it
`:public true` in relay.edn makes it a public role instead. Its session
never holds `private`, so it may be granted `egress` tools. It still
becomes contaminated when it reads the web.

A public role is shown only public items. An item is public when the
operator wrote it with `--public` or a public role wrote it. Everything
else is invisible to it: not shown, not counted, with no placeholder,
and a fact, doc or memo it cannot see does not exist for it. It is never
given the operator's `<data>/AGENTS.md`, which may hold private details,
and since any other role's session may have read that file, text a role
wrote is never public: a public role sees, and can send memos to, only
the roles whose id, title and instructions the operator wrote with
`--public`, and its own must be written that way too. `relay add
--public` does it for a new role; `relay update <id> --public --title
<t> --instructions` does it for an existing one, id included. A public
role whose own text is not public is not woken, and the operator is told
once, with what to run; `relay status` says so too. What it writes is
public only when it comes from a session that began while the role was
public: relay names the wake in the tool command it gives the harness. A
session that began public is read for as public even if relay.edn
changes while it runs. It sees a doc only when its path was chosen in
public. It may not write where a doc it cannot see sits, so it can tell
that such a path is taken. The operator cannot put a public doc at a
path first chosen without `--public` either, since no public role could
see it there. Memos, facts, docs and other items are numbered by the
log's one sequence, so the gaps in the numbers a public role sees tell
it roughly how much else happens that it cannot see. And an item it
could see vanishes for it once a session that is not public changes it
(a fact corrected, a doc rewritten), and a fact it can see may say it
was superseded by one it cannot: both say that something changed, never
what. A public role is told times in the operator's time zone, in its
prompts, its tools and `clock`, like any role, and its routines follow
that zone: the zone is not kept from it, so it may go out in a search.

`--public` on `relay memo`, `routine`, `fact`, `put`, `add`, `update` and
on `relay wake --note` marks what the operator writes as public: it may
leave this machine in a web search. A memo, routine, note, journal entry
or wake note for a public role is refused without it. A memo to a public
role from a house role is public as written: nobody signs it off, and
the house role is told that what it wrote went out. It keeps the rest of
its provenance, so a request shaped by a mail stays marked as from
outside. A role whose own id was written in private cannot send one,
since the scout would see its name. A private memo of that kind from an
older log stays unseen by the public role until the operator closes it.
A memo a public role sends is always marked as from outside, even from a
session that has not read the web yet. A public role may answer any role
that sent it a memo it was shown.

A public role's `egress` calls follow the fetch rule. relay checks each
one before it runs: a `url` argument, and in any other argument (a search
query included) everything from a `scheme://` to the end of that
argument, must appear character for character in an earlier result of
an `egress` tool in the same session. Nothing may follow an address in a
query, since a tool may echo the query back and the echo would make the
longer address fetchable. A wake that resumes an interrupted session keeps what that
session was shown. Results of relay's own tools and of other tools do
not count, so an address the steward wrote in a memo cannot be fetched
until the web shows it too. webtool never follows a redirect: it reports
the new address, and fetching it is a new call that relay checks the same
way. relay keeps each `egress` result for this in
`<data>/runs/<role>/<wake>/shown/`. Since relay must see and read each
call, an `egress` tool is an `:envelope` tool with its `:parameters`
listed: relay.edn refuses one of an MCP server, one run as a command
given the model's arguments, and one whose parameters it does not know.
An `egress` tool either fetches or searches. A fetch tool takes a string
`url` and otherwise only numbers and yes-or-no values. A search tool
takes no `url` and names its free text in `:queries`, such as
`:queries ["query"]`; that text goes only to the search provider the
tool is set up for, which you trust as you trust the model provider.
relay.edn refuses any other `egress` tool, one with both a `url` and
free text above all. A scout can hold what the steward wrote to it, and
a tool that sends text to an address (a web form, a post) could publish
it.

relay refuses any tool result for a public role that carries `private`,
and tells agentloop to refuse one too (`forbid_labels`), so a public
role is not granted a tool whose `:labels` include `private`, nor
`:may-add`, nor the dead letter handler's job, nor a `:script` harness.
Either refusal is reported to the operator. A session from before a
role was made public may hold private text that no label records, so
none is carried on once it is: an interrupted wake starts afresh, and
conversations are kept in a directory of their own. A conversation
with a public role is under the same rules, but what the operator types
in it goes to the role as it is, and the role may send it out to the
web.

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
   zone, the model and how to get its API key, and where pushes go.
   It also asks where webtool is, so a public role can look things up on
   the web, and for the command that prints the Brave Search API key,
   which is used only to send searches to Brave. With webtool it writes
   `webtool.json` beside `relay.edn` and gives the `web` tool to the
   scout, a public role that sees nothing of yours (add it with `relay
   add scout --public`); answer `none` to leave the web out.
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
6. For pushes to a phone, run an [ntfy](https://ntfy.sh) server.
   Its config names your machine, so keep it outside this repository:

   ```sh
   conf="$HOME/Library/Application Support/relay/ntfy"
   mkdir -p "$conf" && cp setup/ntfy/server.yml "$conf/"
   ```

   Edit its `base-url` and `cache-file`, install
   `setup/launchd/relay.ntfy.plist` the same way as the tick (it uses
   `@NTFY@`, `@NTFYCONFIG@` and `@LOGS@`), and subscribe to the topic from
   the ntfy app. Then give `:messages` in `relay.edn` a push channel,
   `{:kind :push :via :ntfy :url "http://127.0.0.1:2586" :topic "relay"}`,
   and a route to it, or choose ntfy when `relay init` asks.

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
9. To let roles use a service that offers an MCP server, such as
   MailerLite or Ticket Tailor, find the server's address in the
   service's help pages. MailerLite's is `https://mcp.mailerlite.com/mcp`.
   Pick a short name for the service, in lower case, and run this in a
   terminal:

   ```sh
   bin/relay connect mailerlite https://mcp.mailerlite.com/mcp
   ```

   Your browser opens on the service's own sign-in page. Sign in there
   and allow relay. Back in the terminal, relay lists the service's tools
   and says what each one could do, such as "Reads only" or "Acts for
   you, and asks you first every time", with what the service says the
   tool is for. Type the four-letter code it shows to finish.

   Then open `relay.edn`, add the name to `:connections`, and add the
   tools you want the hub to have to its grant, by the names relay
   printed. Keep the tools it has already:

   ```clojure
   :connections ["mailerlite"]
   :grants {"hub" {:tools ["mailerlite_list_subscribers" "mailerlite_send_campaign"]}}
   ```

   If the sign-in ever runs out, relay tells you. Run the same `relay
   connect` command again. It keeps the choices in the file.

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

Open the dashboard with `relay` in this terminal (`relay --open <role>`
puts a role in focus), or from an ACP
client with `relay acp <role>`. `relay status` shows roles, open work,
harnesses, this week's spend and recent wakes, and `relay help` lists
every command.

### Messages to you

Everything relay tells you is a message: a note (a draft is ready, a
question only you can answer), an alert (something is wrong), a digest
(the morning brief) or a reply in a thread. A message has a subject, an
urgency (low, normal or high), and sections of items, each with the refs
it drew on and where it came from. Text from outside is marked
`[from outside]` and its links broken, in every channel.

A role uses `tell_operator` to tell you something. It gives the content
and the urgency, and nothing else: relay.edn alone decides where the
message goes. A role that works on the web cannot use it; it reports
back by memo. A memo to `operator` and relay's own alerts become
messages too.

### What is ready for you

`relay ready` lists, newest first, everything relay has prepared or told
you: what roles told you, drafts they made in your own accounts with an
edit tool, entries on the agent's calendar that have not ended, and
findings they put on the board. Each says where it came from, in words
("from a mail by x@y.org", "from the web"), and never names a label. It
is short and fits 60 columns. The client shows the same list as its
ready view (`_relay/ready` in `docs/client-protocol.md`), and a change
reaches it without asking.

To answer a message, `relay reply <message> -m <text>` (the client does
the same from the view). The reply is yours, so it is trusted. It is
recorded against the message, and the role that wrote the message gets a
memo from you quoting it and naming the message, which wakes it. It
answers with `tell_operator`, giving the message's number as
`in_reply_to`, and the thread shows both. A message relay wrote itself
(an alert, the digest) has nobody to answer.

relay learns what a role prepared by reading the session after a wake or
a conversation turn. For every call that was allowed and ended without an
error to a tool relay.edn gives class `edit`, it records the tool, a
title (the input's `subject`, `title`, `name` or `summary`, else the
tool's name in words), where the tool said it put it (the first line of
its result), and a start of the text (the input's `body`, `text`,
`content` or `description`). A call is recorded once however often the
session is read. Nothing here is a decision: it is a record of what
already happened, and a draft does not wait for anything.

`:messages` in relay.edn has channels and routes:

```clojure
:messages
{:channels {"phone" {:kind :push :via :ntfy :url "http://127.0.0.1:2586" :topic "relay"}
            "mail"  {:kind :email :url "smtps://smtp.example.org:465"
                     :from "agent@example.org" :to "you@example.org"
                     :user "agent@example.org"
                     :credentials-command ["security" "find-generic-password" "-s" "relay-mail" "-w"]}}
 :routes [{:kinds ["alert"] :channels ["phone" "mail"]}
          {:urgencies ["high"] :channels ["phone"]}
          {:urgencies ["normal"] :channels ["mail"]}
          {:urgencies ["low"] :channels ["mail"] :at :digest}]
 :quiet-hours {:from "22:00" :to "07:00"}
 :digest-at "18:00"}
```

A message goes to every channel that a matching route names. A route
matches on `:kinds` and `:urgencies`, and leaving one out matches all.
A channel that only `:at :digest` routes name gets the message at
`:digest-at`. Quiet hours hold every message that is not high urgency
until they end. A high-urgency message goes at once. Messages that come
due on one channel at the same tick go as one push or one email. Every
message also shows in the client (`_relay/messages` in
`docs/client-protocol.md`). Without `:messages`, that is the only place
they show.

A push is one line: the subject as its title and the first line of the
message as its body. `:link`, if given, is opened when you tap it; `{id}`
in it is the message's number. `:via` is `:ntfy`, `:macos`, `:command`
(the title as its last argument, the body on stdin) or `:file`, which
appends a JSON line and reaches nobody.

An email goes from the agent's own address (`:from`) to yours (`:to`),
and to nobody else: relay submits it with `curl` over SMTP, with exactly
one recipient, no Cc or Bcc, as plain UTF-8 text. The subject is the
only part a role writes that becomes a header, and it is encoded on one
line. A reply threads to the message it answers with `In-Reply-To`.
`:url` is `smtps://` for a real server; plain `smtp://` is taken only to
this machine (`127.0.0.1` or `localhost`), such as a local relay or
mailpit in tests. The password is what `:credentials-command` prints. It
goes to curl on stdin at each send, so it is on no command line.

A delivery that fails is recorded and tried again by the tick after a
wait that doubles, up to eight times. The message itself is in the log
either way. When relay.edn cannot be loaded at all, the tick still sends
a high-urgency alert through `:messages` if that section is sound.

### The mailbox and other sources

Ingestors bring what arrives from outside into relay as memos to a
role, usually the steward. They are code, with no model, and the tick
polls each one. relay.edn's `:ingest` alone says what to fetch:

```clojure
:ingest
{"mailbox"  {:kind :pop3 :url "pop3s://pop.example.org:995" :user "agent@example.org"
             :credentials-command ["security" "find-generic-password" "-s" "relay-pop" "-w"]
             :authserv-id "mx.example.org" :to "steward"}
 "repos"    {:kind :github :repos ["owner/repo"] :to "steward"
             :credentials-command ["cat" "/path/to/read-only-token"]}
 "calendar" {:kind :ics :url "https://calendar.example.org/you.ics" :to "steward"}}
```

- `:pop3` reads the agent's own mailbox with `curl`. The password goes
  to curl on stdin. relay never deletes a mail: it remembers each one's
  UIDL and reads only new ones, at most 20 a poll. A mail over 10 MB is
  read as its headers only. Attachments are listed, not kept.
- `:github` asks the REST API for the issues and pull requests in each
  repository updated since the last poll, with `If-None-Match`, so a
  quiet repository costs little of the rate limit. The first poll only
  marks where to start. The token is optional and should be read-only.
  Every item a poll returns gets a memo, 50 to a memo, since the next
  poll starts after them.
- `:ics` reads a calendar feed, such as your own calendar's private
  address. relay never writes it. The memo lists events that are new,
  changed or removed, at most 50 a poll; events long over are left out.
  An answer that is not a whole calendar (an error page, an empty body,
  a feed cut off) is a failed poll, so it never reads as every event
  removed.

Each source is polled every `:every-minutes` (5 for mail, 15 for
GitHub, 60 for a calendar), at the minutes that are a multiple of it.
`relay poll [<source>]` checks now and says what came in. No redirect is
followed, and plain `pop3://` or `http://` is taken only to this
machine. A source that keeps failing for `:outage-patience-minutes`
sends you one alert. `:to` must not be a public role.

Everything an ingestor brings in is from outside, and carries a label
saying where: `mail:<sender>`, `github:<owner>/<repo>` or
`ics:<source>`. You see a mail as "from outside: a mail that says it is
from stranger@example.com", since anyone can write a From header. A mail
that names your own address but was not verified is labelled
`mail:unverified-operator` and shown as "from outside: a mail that says
it is from you, not verified", never with your address. The steward
reads it like any other memo.

A mail is yours, and trusted, only when all of these hold: it has one
From header naming one address, the `:to` of an email channel in
`:messages`; exactly one `Authentication-Results` header carries the
source's `:authserv-id` (your mail provider's, which strips forged
copies); and that header says `dkim=pass` with a single `header.d`,
your address's domain. Another method's pass (`bimi`, `spf`) does not
count. relay reads the headers strictly. A bare CR or LF, a
header it cannot parse, or a second header with that authserv-id makes
the mail an outsider's. Without `:authserv-id`, no mail is trusted.

A verified mail that answers one relay sent you (its `In-Reply-To` or
`References` holds that message's Message-ID) becomes a reply on the
message's thread, and a memo in your words to the role that sent the
message, without the quoted text at the end. Any other verified mail
reaches the source's role as yours. A reply that is not verified is
mailbox content: it joins no thread, and the memo says it could not be
confirmed.

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
message when the week's spend reaches 80% of it and one when it
reaches 100%. A wake that passes both at once sends only the 100% one.
The budget never stops or slows anything.

### Reviewing a week

`relay week` looks back over the last seven days, or from `--since` to
`--until` (dates in relay's time zone, the last day included). `--json`
prints the same report as JSON. It writes nothing and wakes nothing.

It starts with the interruptions: each time relay needed you. These are
a harness that needs you and work given up and sent to you. For each
kind it says how many arose, how many were dealt with, how many still
wait, and the median and longest wait. One decided after the window ends
counts as waiting. Then the messages relay sent you, per day and per
kind, and the memos to you per role. Then each role's wakes and conversations:
memos closed, blocked, left open and dead-lettered, wakes cut short,
crashed or without their tools, and what they cost. A wake belongs
to the window it started in.

Health checks list the wakes that look wrong: a wake that ran and wrote
no journal entry, left a memo open, had a tool call
refused or failed (read from its transcript), was cut short or crashed,
or sent you three memos or more.

`relay flag <wake> good|bad [note]` marks a wake or a conversation so
you can find it later. A conversation's id is a wake id; `relay status`
lists the latest and `relay week` lists the ones it mentions. Flags set in the window are at
the end of `relay week`.

`relay traces --out <dir>` writes the wakes and conversations in the
window as OpenTelemetry traces to `<dir>/traces.otlp.jsonl`, with
`--since` and `--until` as for `relay week`. Each line is one OTLP/JSON
request. Per wake or conversation there is one `invoke_workflow` span
with its role, id, outcome, labels, cost and memos, and its
health checks and flags as `gen_ai.evaluation.result` events. Each of
its run files then goes through `agentloop otel`, with the runs hung
under that span. It runs offline and makes no requests. agentloop comes
from the default harness's `:bin`, as for a wake, or from `--agentloop`.

A trace holds no content: no memo, fact or doc text, no tool input or
result, and only a flag's value, never its note. So it can be loaded
into any OTel viewer without your data going with it. It does name your
roles and tools and the labels each session held.

### Morning brief

With `:brief {:at "07:30"}` in relay.edn, relay sends you one digest
message each morning at that time in its time zone. It has two
sections: today's calendar and reminders due today or overdue. Each gives a count and up to eight
short lines. relay composes it itself and wakes no role, so it costs no
tokens and no calendar invite reaches a prompt.

The calendar comes from the tool named `cal` in `:tools`, run as `cal
events --from <today> --to <today>`. Reminders come from `rem-read`, run
as `rem ... ls --today`. `:brief {:at "07:30" :calendar "my-cal"
:reminders "my-rem"}` names other tools. The brief runs only `:read`
tools that take their arguments on the command line. It gives each tool its `:timeout-seconds`, or a
minute when the tool has none, since the tick waits on it.

Titles and calendar and list names show on one line each, cut at 80
characters, with every run of spaces or line breaks made one space. cal
and rem print invisible characters as `\u{XXXX}`; the brief reads those
back and the push escapes them once, so the phone shows `\u{200b}`, not
`\\u{200b}`.

A section whose tool fails, times out or prints something the brief
cannot read says it is unavailable. A section whose tool is not in
relay.edn says it is not configured. When no section has anything to
say, and none is unavailable, nothing is sent. The brief carries the
labels of what it shows, so one with calendar text is marked
contaminated and its links broken when it reaches your phone.

It goes out at most once a day. If the Mac was asleep at the time, it
goes out at the first tick after, the same day. A day the Mac sleeps
through has no brief: the next morning's does not repeat it. The log
records each day's brief, sent or empty, as a `brief/composed` event.

### The dashboard

`relay` opens the dashboard in this terminal, and `relay --open <role>` does
the same with `<role>` in focus. `relay help`
and `relay --help` print the usage. The dashboard needs [relay-tui](https://github.com/kirahowe/relay-tui),
a terminal client for ACP agents. Build it with `make -C ../relay-tui build`
and put `bin/relay-tui` on your PATH. relay runs it as
`relay-tui [--open <role>] -- relay acp`, adding `--data <dir>`
when you gave `relay` one. relay-tui shows every role, with `<role>`
in focus, and each role's conversation. When relay serve runs (see
[The relay service](#the-relay-service)), conversations live in it and
keep going after relay-tui closes; otherwise they end with it.

To use another client, or relay-tui somewhere other than PATH, set
`:client` in `relay.edn` to the command and any arguments that come
before `--`:

```clojure
:client ["/opt/relay-tui/bin/relay-tui" "--agent-log" "/tmp/relay-agent.log"]
```

The client gets `--open <role>` when you named one, then `--` and the
agent command as its last arguments. If relay cannot find the client, it
says so on stderr and exits 1.

relay-tui has a workspace pane. It lists, newest first, what the role
showed you and the sources the latest answer cites, each checked with
relay and marked when it does not exist. Everything in the pane comes
from relay, never from the model's text, and anything from outside is
marked so. relay-tui's README has the keys.

Give `:client` an absolute path, and likewise `:bin` in the harness
settings for agentloop. A bare name is looked up on PATH, so whatever
comes first on PATH would run as your dashboard and see your
conversations.

### ACP clients

`relay acp [<role>]` speaks the
[Agent Client Protocol](https://agentclientprotocol.com) on stdin and
stdout, so an editor or any other ACP client can run it as
its agent command. When relay serve runs for the data directory, `relay
acp` is a bridge to it: it copies bytes between the client and the
service's socket. Otherwise it runs the same code in its own process, and
the conversations it opens end when the client goes.

Each role has at most one live conversation, a chat wake running the
role's agentloop behind relay's proxy. `session/new` opens the role's
conversation, or attaches the client to it when it is already live: the
answer then carries `_meta {"relay/attached": true}`, and what the
conversation has said so far follows. The role is the one in the
request's `_meta` (`relay/role`), else the `<role>` relay acp was given.
`session/load` attaches to a live conversation and `session/list` lists
them; nothing that has ended is ever reopened. Several clients may be
attached at once, and each sees everything, including the prompts the
others send. `session/close` detaches a client (and cancels a turn it
started); the conversation goes on.

A conversation holds its role only while a turn runs: from a
`session/prompt` until the agent answers it. Between turns the dispatcher
may wake the role for its memos. A prompt sent while such a wake works
waits until the wake is done and then runs, even if the client that
sent it has gone; `session/cancel` from any client drops it. A session
takes one prompt at a time.
The wake records the ACP sessions the conversation used. Their logs are
kept in `<data>/runs/<role>/conversations-labelled/` (for a public role,
`conversations-public/`), apart from the role's wake sessions.

Every conversation starts empty, and when it ends it stays ended. What a
role should carry from one conversation to the next goes in its notes,
facts and journal, as it does between wakes.

To set up a client, give it the command `relay` with the arguments
`acp <role> --data <dir>`, or leave out `--data` when
`~/.config/relay/config.edn` names the data directory. The client needs
no environment variables and no API key, since the key comes from
`:api-key-command` as usual. relay ignores the client's MCP servers,
because a role has only relay's tools. Without a service, if the role
already has a conversation open, relay writes the reason to stderr and
exits 1 before sending anything.

relay adds methods of its own, which a client that knows them can use;
`initialize` says relay offers them with `_meta {"relay/hub": 1}`, and
with `"relay/keeps"` whether conversations outlive the client (true when
relay serve holds them). `_relay/roles` gives every role with what it is
doing, and `_relay/roles/changed` follows whenever that changes.
`_relay/seen` says the operator has read a conversation through a turn,
`_relay/conversations/end` ends one, and `_relay/replayed` marks where a
replay ends and live updates begin. The pane's methods (`_relay/items`
and `_relay/resolve`) are about the conversation the request's
`sessionId` names. The board (`_relay/cards` and `_relay/cards/op`)
belongs to the connection and needs no conversation: its methods refuse a `sessionId`,
and each client that asks for the board is sent, as
`_relay/cards/changed`, every card that changed since what that client
was last sent. [docs/client-protocol.md](docs/client-protocol.md) lists
every method, notification and `_meta` key, with its scope, params,
answer and errors.

`--trace <file>` appends every line in both directions to `<file>`, one
JSON object per line, with `from` set to `client` or `agent`. The file
holds everything said, so relay makes it readable by you only and refuses
a directory others can write to, such as `/tmp`.

relay never asks a client to approve anything, and a role's calls run
at once. agentloop sends no `session/request_permission`; if an agent
sends one anyway, relay answers it as cancelled and the client never
sees it.

A display (see What you are shown) reaches the client as one more text
block in the call's content, with the whole display under
`_meta["relay/display"]`. The block's own text is inert, for a client
that knows nothing of displays: characters that draw as nothing show as
`\u{XXXX}`, links are defanged, and the whole sits in a code fence
nothing in it can close. relay makes it so from the display, whatever
the agent sent, so no client fetches an image in it or follows a link
before you have read it. relay-tui shows the display from `_meta`.

relay acp also answers the pane's methods itself, and they never reach
agentloop. `_relay/items` lists a session's shown docs.
`_relay/resolve` looks up a `[[kind:id]]` ref. relay keeps each display
as the conversation goes, before the client sees it.

relay acp also gives the client your board. `_relay/cards` answers every
active card in a wire form that is ready to show, and from then on relay
sends the client every card that changes, including changes made by a
role's wake or the command line. `_relay/cards/op` changes a card as
you, with no labels. It refuses a parameter it does not know, naming it, so a
typo such as `iten` fails instead of passing as success, and an edit
carries the rev you saw, so relay refuses it when someone changed that
text in the meantime. Both are the connection's and work with no
conversation open. [docs/client-protocol.md](docs/client-protocol.md)
has their params, answers and errors, and the card's wire form.

Only relay speaks relay's methods to the client: relay acp drops any
message from the agent whose method starts with `_relay/`, so an agent
cannot fake a card update or a pane item. Nor can it answer for relay:
relay acp keeps count of the requests it passed from the client to the
agent, and drops any response from the agent that answers none of them,
or one already answered. A request the client sends relay under an id
the agent still owes an answer to cancels that debt. So a response with
the id of a pending `_relay/cards` request never reaches the client. Ids match as a client
matches them, numbers by value (`3` and `3.0` are one id) and strings by
their text. Escapes such as
`_relay\/cards` name the method they spell. Member names match exactly,
so `Method` is just another member, which no component reads as the
method.

relay, agentloop and relay-tui read every line by one strict rule, so a
line means the same to all of them or is not passed on. A line must be
one JSON object in valid UTF-8 with no byte order mark and nothing but
whitespace after it. No member name may appear twice in one object, at
any depth. Unpaired surrogates are refused, nesting may go 128 levels
deep, a number literal may have at most 1000 characters, and an
exponent must lie between -9999 and 9999 (by value, so leading zeros
are fine). Numbers
stay exact. relay passes a line that meets the rule on unchanged, apart
from the display text described above, and drops one that does not, in
either direction. A dropped client line gets an error answer when relay
can find its id.

A message from
the agent too long for relay to read whole (over 8 MB) never reaches the
client: relay drops it, records that, and tells the operator. A
message from the client that long never reaches the agent either, since
it could be one of relay's methods: relay drops it and answers with an
error when it can find the message's id.

### The relay service

`relay serve` keeps conversations going while no client is attached: a
turn finishes after you close relay-tui, and opening it again (or a client
on another device) picks the conversation up with what it said so far.
It listens on a Unix socket of the data directory's own, under
`~/.relay/run/`, which only you can open. `relay acp`, and so the dashboard,
uses it whenever it runs. Only one service runs for a data directory,
however you name the directory. The `--socket` and `--run-dir` options
exist only for relay's own tests; a real service uses neither.

A conversation ends when you end it
(relay-tui does it with `_relay/conversations/end`) or when the service
stops. Stopping it (SIGTERM, or launchd unloading it) ends each
conversation and records it; a turn in progress ends there. If the
service dies without that, the next one ends the conversations it left,
as lost, when it starts.

Install it as a launchd job, the same way as the tick:

```sh
sed -e "s|@BB@|$(command -v bb)|" -e "s|@RELAY@|$PWD|" \
    -e "s|@DATA@|$data|" -e "s|@HOME@|$HOME|" \
    -e "s|@LOGS@|$HOME/Library/Logs/relay|" \
    setup/launchd/relay.serve.plist > ~/Library/LaunchAgents/relay.serve.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/relay.serve.plist
```

`bb service stop`, `bb service start`, `bb service restart` and `bb
service status` do the same with less typing. Each does nothing where
there is nothing to do: stopping a stopped service, or starting a
running one.

A deploy waits for every running wake, and a conversation the service
holds is one, so stop the service before `bb deploy` (`bb service
stop`) and start it again after (`bb service start`). That ends every live conversation; the
roles remember what their notes, facts and journals hold, as after any
conversation. The service runs the code it started with until it is
restarted, so it must be restarted after a deploy in any case.

### Reaching relay from your phone

The service can also speak ACP over WebSocket, so a phone client such as
[Agmente](https://github.com/rebornix/Agmente) reaches the same
conversations as relay-tui: it can list them and pick one up. relay listens only on 127.0.0.1. Tailscale puts it
on your tailnet with TLS, the same way as ntfy, so only your own devices
reach it, and each client must also send a token that relay reads from
your keychain.

1. Put a long random token in the login keychain:

   ```sh
   security add-generic-password -a "$USER" -s relay-websocket -w "$(openssl rand -hex 32)"
   ```

2. Add the listener to relay.edn:

   ```clojure
   :serve {:websocket {:port 8765
                       :token-command ["security" "find-generic-password" "-s" "relay-websocket" "-w"]}}
   ```

   `:default-role` (`"hub"` unless you set it) is the role a client's
   new session goes to when the client does not name one. Agmente
   cannot name one, so its new sessions go there. `:allowed-origins` is
   empty unless you set it, and relay refuses any request that carries
   an `Origin` header, since that is what a web page in a browser on this
   Mac would send. A native app sends none. If yours turns out to send
   one, add that exact origin to the list.

3. Restart the service (`launchctl bootout gui/$(id -u)/relay.serve`,
   then `launchctl bootstrap` as above). It reads the token once, when it
   starts, after the Unix socket is up. If the command fails, prints
   nothing, prints more than 4 KB or takes more than 10 seconds, the
   service says so in its log and runs without the listener, and the Unix
   socket works as before.

4. Publish the port on your tailnet (`--bg` keeps it published after the
   terminal closes):

   ```sh
   tailscale serve --bg --https=8765 http://127.0.0.1:8765
   ```

5. In Agmente, add a server at `wss://<your Mac's tailnet name>:8765` with
   the token from step 1 (to see it again, run the `security` command from
   step 2).

relay answers a request with no token or a wrong one with a bare 401, a
request with an origin it does not allow with a bare 403, and anything
that is not a WebSocket upgrade with a 404. None of these responses has
a body. One text message carries one JSON-RPC message. relay closes the
connection on a binary message, on one over 8 MB, 1024 frames or 30
seconds, and on anything else RFC 6455 forbids. WebSocket clients
share the socket's limits: at most 64 connected clients, and a client
that stops reading is dropped once 64 MB wait for it.

### The calendar

The agent has a calendar of its own, and the operator subscribes to it in
their calendar app. A role that is not public puts events on it with
`calendar_add`, moves them with `calendar_update` and takes them off with
`calendar_remove`; `calendar` lists them. These tools are class `edit`:
they change something outside relay that only the operator sees and can
undo. Nothing on this calendar invites anyone, and relay never writes the
operator's own calendar. The operator and the dispatcher cannot write
this one either, so the feed holds only what a role wrote.

An event is versioned the way a fact is: every change is a new version
with the labels of what its writer had seen, and removing an event keeps
its history. `relay serve` publishes the calendar at `/calendar.ics` as
iCalendar (RFC 5545). A UID is made from the event's number and never
changes, and `SEQUENCE` counts the event's changes, so a calendar app
sees each change as an update to the same event. Times are UTC. A removed
event stays in the feed as `STATUS:CANCELLED` for 30 days, so a
subscriber that polls now and then still hears of it, and is then
dropped. An event that ended more than 30 days ago is dropped too; the
log keeps it. The feed carries at most 1000 events, counting both kinds,
so adding and removing in a loop cannot grow it. Text is escaped and stripped of control and invisible
characters, so what a role writes cannot add a property or an event.

The feed is on the WebSocket listener (see Reaching relay from your
phone), on that port and no other, so it is reachable only where that
listener is: 127.0.0.1, published on your tailnet by `tailscale serve`.
It takes the listener's token, as `Authorization: Bearer <token>` or,
since a calendar app cannot send a header, as `?token=<token>` in the
URL. A request without it gets a bare 401, and one with an `Origin` the
listener does not allow a bare 403. To subscribe, add a calendar by URL:
`https://<your Mac's tailnet name>:8765/calendar.ics?token=<token>`.

## Development

```sh
bb verify        # every check in every repo, one summary (VERIFY.md)
bb test          # every test, in parallel, with volley (tests.edn); pass namespaces to run only those
bb soak 5000     # the model-based property, many more times
bb tck           # the ACP conformance kit against relay acp (needs Go, uv and ../agentloop)
```

`bb test` runs [volley](https://clojars.org/com.kirahowe/volley), which reads
`tests.edn` and Kaocha's flags. A bare namespace name runs only that namespace
(`bb test relay.deploy-test`), `--skip` and `--workers N` work as in volley,
and `--agentloop <path>` runs the tests against that agentloop build. Each
volley worker is its own process, so `tests.edn` lists
`relay.test-support/isolate!` as worker setup; that keeps every worker away
from the operator's real relay config. `.volley/` holds volley's timings and
is not committed.

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
it. The staging `relay.edn` routes every message to a push channel with
`:via :file`, which appends each push to `notifications.log` in the
directory and reaches nobody. Its
roles are the hub, the clerk and the scout, a public role. The scout has
webtool's search and fetch, from the webtool built beside this checkout,
with a `webtool.json` in the directory that keeps page snapshots there
and reads the Brave key from the keychain as `relay-brave`. Its routine
`grant-search` runs daily at 07:00: it looks for grants for the Harbour
Sound Society, a made-up community choir, and sends what it found to the
hub, whose text is public so that the scout can reach it. Scenarios and
evals make their staging data without the scout's web and routine, so no
run of theirs reaches the real web. It also defines stand-in tools that no role holds until
a scenario grants them, among them a mailing service for a made-up concert
society: `campaign-draft-demo` (edit) keeps every version of a draft in
`campaigns.edn`. It only drafts; nothing in staging can send.

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
bb eval [<case> ...] [--out <dir>] [--live] [--trials n] [--model m] [--effort e] [--max-cost usd] [--agentloop <path>]
```

Without `--live` the model is the mock server playing the case's oracle
script, which costs nothing and checks the harness. With `--live` it is the
real model, `claude-haiku-5-5` unless `--model` names another, at effort
`low` unless `--effort` names another, five trials a case unless `--trials`
says otherwise. `--max-cost` stops starting trials once
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
mean wakes, requests, tokens and cost), `traces.otlp.jsonl` and the terminal
report. The traces file is the run as OpenTelemetry GenAI spans in OTLP/JSON,
one request per line: per trial, an `invoke_workflow` span named for the case
with a `gen_ai.evaluation.result` event per grader, then one line per wake from
`agentloop otel` (the same agentloop build the run used) with its runs under
that workflow span. Content stays out of the wake spans. The GenAI conventions
are at Development stability, so the names may change. A wake whose export
fails is reported and left out. Usage and cost
come from agentloop's transcripts, at the served model's rates. Judge graders
may appear in a case but are not run yet; they are recorded as skipped.

A check can also look at `:citation` items, one for each ref in what the hub
said to the operator (a memo to them, or the outcome it gave when closing
one of theirs), resolved against relay's state. A grader on forbidden words
is a `:none :memo` check with a `:regex` on the body.

In `bb test`, every case's oracle must pass all its checks and each foil (a
known-bad script) must fail exactly the checks it names.

`concert-email-drafted-for-approval` is the whole flow in one case. You ask
only "please draft the next concert email". The hub should follow its skill,
draft from the facts and the bio with a placeholder for what nobody said,
tell you what goes out when with refs, and stop at the draft, which you send
yourself. `test/scenarios/BASELINES.md` has its live results.

### Deploy and rollback

These are for you to run, not an agent. Run `bb deploy` from a relay working
copy that has no changes:

```sh
bb deploy [--relay <dir>] [--agentloop <dir>] [--relay-tui <dir>] [--bookmark main] [--record <file>] [--data <dir>] [--paused-externally]
bb rollback                        # same flags
```

The paths default to the live checkouts under `~/code/projects` and must be
three different directories. `--data` defaults to the data directory in
`~/.config/relay/config.edn` and must be a relay data directory (it needs
`relay.edn` and a `log` directory), since it is how the deploy sees running
wakes. A deploy names the
commit `main` points to in each repository and uses only those commits from
then on. It builds them in fresh jj workspaces in a temporary directory and
runs `bb test` there (the mock scenarios too), lists what it will do, and asks you
to type a code on the terminal.

After you confirm, it pauses relay: while
`~/Library/Application Support/relay/paused` exists, `relay tick` does nothing,
and `relay status` shows the file's path, why it is there and how to lift it
(`rm '<path>'`, and `bb rollback` when a deploy made it). It waits until no wake or tick is running (read
from the live log, which `--data` must point at, and from markers the ticks
leave), writes a record, and for each checkout runs `jj new <commit>`, builds
agentloop and relay-tui with `make build`, and finally runs `relay status`
against that data directory. Just before it moves a checkout it looks at it
again, and it stops if anything changed since the plan or jj cannot read it.
It refuses to start if any checkout has changes in its working copy or sits on
a merge. When it all works it removes the pause file. If a step fails after a
checkout was touched (a move that errors or a build that starts counts), relay
stays paused and the message says so. If every checkout got to its target and
only `relay status` failed, the message says that and gives the `rm` command
that lifts the pause. Ctrl-C before any checkout is touched lifts the pause it
made, forgets the check workspaces and says so. A check workspace jj could not
forget is listed with the command to forget it by hand. Only one deploy or rollback runs at a
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

`bb pentest` attacks the whole system as built: relay, agentloop and
cleanroom's `relay-tool`, driven from outside through the command line
and ACP, the way the operator and an attacker would reach them. `bb test`
checks relay's parts; this checks that they still keep the operator's
things in the house when put together. Each scenario gives the model an
outsider's text and assumes it obeys perfectly, then checks that:

- a house role that is shown an outsider's text, by any route (a memo, a
  block explanation, a dead letter, a doc), has no way to send the
  operator's things out: it has no egress tool, and when its memo to the
  public scout carries them, the scout cannot fetch an address that holds
  them
- the scout, which can post, is never shown anything private
- what the operator pastes, in a memo or as an ACP resource, is trusted,
  and relay never asks an ACP client for permission
- a crash does not lose a label or run a call twice, in a wake or a
  conversation
- no view the operator reads passes a raw control or format character
  through, and URLs from contaminated text are defanged
- relay refuses malformed tool calls and settings that could lose a
  label, give a house role a way out, or name a class or key relay no
  longer has
- an MCP tool gets its credentials without relay seeing them, and one
  whose definition changes is stopped before the model sees it, taken,
  and the operator told
- a remote MCP tool reached over HTTP never shows the model its fixed or
  hidden arguments, refuses a call that names one, and takes a changed
  definition the same way
- a command in the steward's shell cannot reach the network or the host
  by TCP, UDP, DNS, vsock or a unix socket, cannot change the operator's
  files, and cannot see relay's data directory; the scout has no shell
  (this one needs Apple `container`, builds the shell's image, and
  removes its own container and image by name)
- clean, everyday use works

Run it from this directory:

```sh
bb pentest                   # every scenario, about a minute
bb pentest s1 s5-acp-pasted-resource  # by name, or by prefix
bb pentest --list            # the scenarios and what each needs
bb pentest --jobs 4          # several at once, about twenty seconds
```

It takes agentloop, relay-tui and cleanroom from the sibling checkouts
(`../agentloop/bin/agentloop` and so on), or from `--agentloop`,
`--relay-tui` and `--cleanroom`, never from PATH. It takes webtool's
fixture build from `../webtool/bin/webtool-fixture` or `--webtool`; the
release build does not count. `--build` builds a
sibling that is missing. A scenario that needs a missing piece, or a
cleanroom without `relay-tool`, is reported as SKIP with the reason. It
needs a python3 (`/usr/bin/python3`, or `--python`) for `pty/drive.py`,
which starts each process in a group of its own. The MCP scenarios
builds agentloop's test server, `internal/cmd/mcpserver`, with Go. `--keep`
keeps each scenario's directory to look at afterwards, and `--verbose`
prints every check. Each scenario prints PASS, FAIL or SKIP, with the
checks that failed, and the command exits 1 if any failed.

Everything it uses is artificial. Each scenario gets a fresh temporary
data directory, a scripted stand-in for the Anthropic API on localhost
(`test/relay/mock_anthropic.clj`) with a dummy key from `/bin/echo`, a
stand-in ntfy server, and stand-in tools (`pentest/standins/`) for
Reminders, the web, a mail campaign service and the mail source, which only write to files in that
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
world and drives relay, `relay/pentest/acp.clj` is a scripted ACP
client, `pty/drive.py` starts processes in new groups (babashka cannot),
and
`relay/pentest/scenarios/` holds the attacks. To add a scenario, add a
map to the `scenarios` vector of one of those namespaces, or of a new one
listed in `relay/pentest/main.clj`:

```clojure
{:id "s11-my-attack"            ; the name bb pentest takes
 :title "what it shows"
 :needs #{:agentloop :python}   ; also :relay-tui, :cleanroom, :webtool
 :before (fn [env] env)         ; optional: start what relay.edn names
 :config (fn [cfg env] cfg)     ; optional: change relay.edn
 :run (fn [env]
        (e/memo! env "hub" "Subject" "Body")
        (e/script! env "hub" [{:tool-uses [{:name "web_read" :input {:args ["x"]}}]}
                              {:text "done"}])
        (e/wake! env "hub")
        (e/check! env "nothing went out" (empty? (e/egress env))))}
```

Many attacks are tables: the channels in `injection.clj`, the payloads
and views in `display.clj`, the client behaviours in `acp.clj`, and the
envelopes and settings in `strict.clj`. Adding a case to one of those is
one entry.

## License

MIT
