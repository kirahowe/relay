# How relay works

This page describes the whole system as the code on `main` has it: the
parts, how they connect, and where each rule is enforced. The README says
how to use relay. `docs/messages.md`, `docs/house-shell.md` and
`docs/client-protocol.md` go deeper into three of the parts below.

## The house and its walls

relay is a private house. Inside, the roles read everything relay keeps,
your notes and files and the mail that comes in, and get on with the work
without asking you. Nothing inside waits for an approval. What keeps you
safe is the walls, and relay builds them in code:

- No tool can send, post, publish or pay as you. The tool classes are
  `read`, `write`, `edit` and `egress`, and no class covers an action
  others would see. `edit` changes only your own things, where nobody
  else sees the change and you can undo it: a draft in your account, a
  reminder, the agent's own calendar.
- A house role (any role that is not public) is never given an `egress`
  tool. The steward's shell runs in a container with no network.
- Mail goes only to you, from the agent's own address.
- Scouts, the public roles that search the web, are never shown anything
  private, and they may fetch only an address the web showed them.

This is everything that can leave the house, and how:

```mermaid
flowchart LR
    kira(["Kira"])

    subgraph house["The house: your Mac"]
        direction TB
        steward["The steward and other house roles"]
        log[("The log")]
        shell["The steward's shell<br/>container, --network none"]
        steward <--> log
        steward --> shell
    end

    subgraph scoutside["Public side"]
        scout["Scouts<br/>see only public text"]
    end

    model["Model provider<br/>prompts and replies"]
    search["Search provider<br/>queries only"]
    web["Web pages<br/>only addresses already shown"]
    yours["Your own accounts<br/>drafts, edit tools only"]
    mailout["Mail to you<br/>one recipient, plain text"]
    push["Push to your phone<br/>one line"]
    feed["The agent's calendar feed<br/>127.0.0.1, behind your tailnet"]

    kira -->|"asks, pastes, replies"| steward
    steward -->|"memo, public as written"| scout
    scout -->|"memo back, marked from the web"| steward
    steward --> model
    scout --> model
    scout --> search
    scout --> web
    steward --> yours
    steward --> mailout
    steward --> push
    steward --> feed
    mailout --> kira
    push --> kira
    feed -->|"you subscribe"| kira
    yours -->|"you read it and send it yourself"| kira
```

The shell has no arrow out: it runs with `--network none` and sees your
folders read-only. A house role's only ways out are the edges on the
right, and each goes to you or into your own accounts. The scout's
`egress` tools reach the search provider and web pages, under the fetch
rule below.

Labels are provenance, not gates. Every event carries labels that say
where its text came from (`private`, `contaminated`, and source labels
such as `mail:dana@example.org`). They decide two things: a scout's
session may never hold `private`, and they tell you where a thing came
from. Nothing is held back or hidden because of a label.

## The four repos and their processes

```mermaid
flowchart TB
    subgraph relayrepo["relay, Clojure on babashka"]
        tick["relay tick<br/>launchd, every minute"]
        serve["relay serve<br/>launchd, always on"]
        acp["relay acp<br/>bridge to relay serve"]
        tool["relay tool NAME<br/>one call, as one role"]
        cli["relay CLI<br/>status, memo, ready, reply, undo"]
    end

    subgraph agentloopRepo["agentloop, Go"]
        al["agentloop run request.json<br/>model loop with tools"]
    end

    subgraph tuiRepo["relay-tui, Go"]
        tui["relay-tui<br/>dashboard"]
    end

    subgraph webtoolRepo["webtool, Go"]
        wt["webtool<br/>search and fetch for scouts"]
    end

    container["Apple container VM<br/>the steward's shell"]
    ntfy["ntfy server<br/>pushes"]
    smtp["Mail provider, or mailpit in tests<br/>SMTP out, POP3 in"]
    provider["Model provider API"]
    brave["Brave Search"]
    phone["Phone ACP client<br/>over the tailnet"]
    calapp["Calendar app"]

    tick -->|"spawns a wake"| al
    serve -->|"one conversation per role"| al
    al -->|"HTTPS"| provider
    al -->|"each tool call"| tool
    tool -->|"envelope on stdin"| wt
    wt --> brave
    tool -->|"container exec"| container
    tick -->|"container run"| container
    tui -->|"stdio"| acp
    acp -->|"Unix socket"| serve
    phone -->|"WebSocket via tailscale serve"| serve
    calapp -->|"GET /calendar.ics"| serve
    tick -->|"curl"| smtp
    tick -->|"push"| ntfy
    cli --> tool
```

| Process | Started by | What it does |
|---|---|---|
| `relay tick` | launchd (`setup/launchd/relay.tick.plist`), every minute | Ends lost wakes, fires routines, delivers messages that are due, starts the shell, polls the ingest sources, wakes every role with open memos, and sends the daily note (`relay.dispatch/tick!`). |
| a wake | the tick, as `relay wake <role> --dispatched` | Writes `request.json` and runs `agentloop run` for one role (`relay.dispatch/wake!`). |
| `relay serve` | launchd (`setup/launchd/relay.serve.plist`) | Holds live conversations and the clients attached to them, on a Unix socket under `~/.relay/run/` and, when configured, a WebSocket listener on 127.0.0.1 that also serves `/calendar.ics` (`relay.serve`). |
| `relay acp` | relay-tui or any ACP client | Copies bytes between the client and `relay serve`, or runs the same hub in its own process when no service runs. |
| `relay tool` | agentloop, once per call | Runs one tool call as the role named on its command line, with the session's labels from the envelope (`relay.tools/call!`). |
| agentloop | a wake or a conversation | Runs the model with exactly the tools in the request. It holds no call and asks nobody. A public role's request carries `forbid_labels ["private"]`, which agentloop enforces. |
| relay-tui | Kira | The dashboard: roles, conversations, the board and the ready view. |
| webtool | `relay tool`, for a scout's `egress` call | Searches with Brave and fetches pages. It reports a redirect instead of following it. |
| container VM | `relay house up`, the tick, or the first `sh` call | The steward's shell. One per data directory. |
| ntfy, mail provider | Kira's setup | Carry pushes and mail. Tests use a stand-in ntfy and a mailpit they start on free ports. |

relay is a functional core with a thin shell. `relay.core` decides every
command and folds every event, and it is pure. `relay.inbound`,
`relay.log`, `relay.dispatch`, `relay.serve` and the tool edges do the IO.

## A request, from Kira to a draft and a message

Kira types "draft the next concert email" in relay-tui. The steward has a
draft tool of class `edit` for her mailing service (a remote MCP tool from
`relay connect`, or the staging stand-in `campaign-draft-demo`).

```mermaid
sequenceDiagram
    autonumber
    actor K as Kira
    participant T as relay-tui
    participant S as relay serve and its hub
    participant A as agentloop
    participant M as Model provider
    participant R as relay tool
    participant D as Draft tool, edit class
    participant L as inbound and the log
    participant C as Channels

    K->>T: draft the next concert email
    T->>S: session/prompt over relay acp
    S->>S: sanitize the prompt, tell Kira what changed
    S->>A: prompt, in the steward's conversation
    A->>M: conversation so far
    M-->>A: call the draft tool
    A->>D: save a draft in Kira's account
    D-->>A: where the draft is
    M-->>A: call tell_operator
    A->>R: tell_operator, envelope with the session's labels
    R->>L: message/send as the steward
    L->>L: core decides and routes, appends message/created
    L->>C: deliver what is due now
    C-->>K: mail from the agent's address, or a push
    A-->>S: the turn ends
    S->>L: record what the steward prepared, prepared/recorded
    S-->>T: _relay/ready/changed with the draft and the message
    K->>K: reads the draft and sends it herself
```

The same request can come as a memo (`relay memo steward ...`). Then the
tick wakes the steward, and a wake runs in its own process instead of the
conversation. Everything after that is the same.

relay learns what a role prepared by reading the session after the wake
or turn (`relay.dispatch/store-prepared!`): every successful call to a
tool of class `edit` that is not relay's own becomes a `prepared/recorded`
event, which the ready view lists. It is a record of what happened; no
step waits on it.

## The log and its fold

Everything relay knows is an event in an append-only log of JSONL files
under `<data>/log/`. The state is the log folded in order. Two pure
functions sit at the center: `relay.core/decide` takes the state and a
command and returns events or a refusal, and `relay.core/evolve` folds
one event into the state.

```mermaid
flowchart LR
    who["Kira at the CLI<br/>a role's tool call<br/>the dispatcher<br/>a client"] -->|"command"| submit["relay.inbound/submit!"]
    submit --> tx["relay.log/transact!<br/>under the file lock"]
    tx --> replay["relay.core/replay<br/>fold every event"]
    replay --> decide{"relay.core/decide<br/>pure"}
    decide -->|"refused"| why["a rejection with a reason"]
    decide -->|"events"| append["append with seq and time"]
    append --> logfiles[("log/*.jsonl")]
    logfiles --> replay
    append --> after["after the append:<br/>deliver messages that are due"]
```

Every write, from anyone, goes through `submit!`. It is decided and
appended in one step under the lock, so two writers never act on a state
the other has already changed. Nothing is deleted. A fact, doc, note,
routine or calendar event changes by a new version, and removing one is
a version too.

Old logs always fold (I8). Events that older relays wrote for machinery
relay no longer has are listed in `relay.core/removed-event-kinds` and
fold to nothing. A kind relay never wrote is still refused, so a log from
a newer relay is not misread.

### Provenance: labels and sources

relay keeps two records of where text came from.

- Labels bound everything a session may have seen. A session starts with
  the labels of its prompt and gains the labels of every result, and they
  only grow. Everything it writes carries them. Reading relay's own
  content adds `private`.
- Sources are narrower: the outside text a session read directly in this
  wake, which is the memos it was woken for and what its tool calls
  returned (`relay.provenance/direct`). What sat in its prompt (notes,
  skills, journal tail, fact topics, the board) does not count.

Labels keep private text from a scout. Sources decide what Kira is told
("Drew on: a mail from dana@example.org") and which recipe changes the
daily note flags.

```mermaid
flowchart LR
    subgraph entry["Entry points, relay.core/entry-points, I5"]
        direction TB
        op["Kira: command line, a conversation,<br/>her verified mail"] -->|"no labels: trusted"| sess
        ing["An ingestor"] -->|"contaminated and a source label<br/>mail:, github:, ics:"| sess
        sc["A scout's memo"] -->|"contaminated, from the web"| sess
        tl["A tool's result"] -->|"the labels relay.edn gives the tool"| sess
    end
    sess["A session's labels<br/>only grow"] -->|"every write carries them"| ev["Events and versions"]
    ev -->|"reading relay content adds private"| sess
    sess -->|"what it read directly"| src["Sources"]
    src --> shown["Shown to Kira in plain words"]
    src --> flag["Flagged recipe changes"]
    sess -->|"public role: forbid_labels private"| al["agentloop refuses<br/>a private result"]
```

## The mailbox and the ingestors

Ingestors bring outside things in as memos to a role, usually the
steward. They are code with no model. The tick polls each source in
`:ingest` when it is due (`relay.ingest-run/poll-due!`), and relay.edn
alone names what to fetch.

```mermaid
flowchart LR
    subgraph sources["Sources in :ingest"]
        pop["pop3: the agent's mailbox<br/>curl, new UIDLs only"]
        gh["github: your repositories<br/>REST API, If-None-Match"]
        ics["ics: a calendar by URL<br/>read only"]
    end
    pop --> parse["relay.mailbox<br/>parse, decode, verdict"]
    parse --> verdict{"Kira's, verified?"}
    verdict -->|"yes"| opmail["memo from operator<br/>trusted, sanitized"]
    verdict -->|"no"| outmail["memo from dispatcher<br/>labelled mail:sender"]
    gh --> ghmemo["one memo per repository<br/>labelled github:owner/repo"]
    ics --> icsmemo["memo of new, changed, removed events<br/>labelled ics:source"]
    opmail --> steward["The steward"]
    outmail --> steward
    ghmemo --> steward
    icsmemo --> steward
```

A mail is Kira's only when `relay.mailbox/verdict` says so: one From
header naming her address (the `:to` of an email channel), exactly one
`Authentication-Results` header with the source's `:authserv-id`, and
that header says `dkim=pass` with `header.d` her domain. Anything short
of that is from outside, and a mail that names her address without
passing is labelled `mail:unverified-operator`. Staff read the mail: the
steward learns from it and does nothing it asks.

## Messages, routing and channels

Everything relay tells Kira is one message in the log: a kind (`note`,
`alert`, `digest`, `reply`), an urgency, a subject, and sections of items
with refs and provenance. A role makes one with `tell_operator`, which
takes content and urgency only. relay's own alerts, memos to `operator`
and the daily note are messages too.

```mermaid
flowchart LR
    tell["tell_operator"] --> core
    memo["memo to operator"] --> core
    alert["relay's alerts"] --> core
    note["the daily note"] --> core
    core["relay.core decides<br/>relay.messages/route, pure"] --> created[("message/created<br/>with its deliveries")]
    created --> now["relay.inbound<br/>delivers what is due now"]
    created --> later["relay.dispatch/tick!<br/>quiet hours, digest time, retries"]
    now --> email["email<br/>channels/email, mail/send!"]
    now --> pushc["push<br/>channels/push, push/push!"]
    later --> email
    later --> pushc
    created --> client["client channel<br/>_relay/messages, _relay/ready"]
```

Routing is a pure function of `:messages` in relay.edn, the message and
the time: which channels a message goes to and when each delivery is
due. Every message is always in the client channel. An email goes from
the channel's `:from` to its `:to` with one recipient, plain text, and no
header the model wrote except the subject (I2). `docs/messages.md` has
the details.

## Replies: one path

Kira can answer a message from relay-tui's ready view, with `relay
reply`, or by replying to the mail. Each channel checks in code that the
reply is hers, then all of them take one path, `record-reply` in
`relay.core`.

```mermaid
flowchart TB
    tui["relay-tui ready view<br/>_relay/reply"] --> clean1["sanitize"]
    cli["relay reply N -m text"] --> clean1
    mail["a mail reply"] --> v{"verified as Kira's<br/>and threaded to a message relay sent?"}
    v -->|"yes"| clean2["sanitize, cut the quoted tail"]
    v -->|"no"| box["mailbox content<br/>a memo from outside, no thread"]
    clean1 --> rr["relay.core record-reply"]
    clean2 --> rr
    rr --> replied["message/replied on the thread"]
    rr --> m["memo from operator to the role<br/>that wrote the message"]
    rr --> close["a memo to Kira it answers is closed"]
    rr --> acts["daily note: revert, not, drop<br/>done by relay itself"]
```

The role answers with `tell_operator` and `in_reply_to`, so the thread
shows both.

## Scouts and the fetch rule

A scout is a public role. It is shown only public items, it may hold
`egress` tools, and its session never holds `private`. A house role asks
it for help with a memo, which is public as written: the scout sees only
what the house role chose to write. The scout answers with a memo marked
as from the web.

What keeps that text from leaving in a URL is the fetch rule (I4). relay
runs every `egress` call itself, so it sees each one first:

```mermaid
sequenceDiagram
    autonumber
    participant St as Steward
    participant Sc as Scout's agentloop
    participant R as relay tool
    participant W as webtool
    St->>Sc: memo: find grants for the choir
    Sc->>R: web, a query only
    R->>R: relay.shown/refusal: was every address in it shown before?
    R->>W: search
    W-->>R: results with page addresses
    R->>R: relay.shown/record! keeps the result for this session
    R-->>Sc: results
    Sc->>R: fetch a page address copied from a result
    R->>R: shown before, verbatim, so allowed
    R->>W: fetch
    W-->>R: the page, or a redirect reported with its target
    Sc->>R: fetch an address it built
    R-->>Sc: refused before it reaches the network
    Sc->>St: memo back, labelled contaminated and from the web
```

Only results of `egress` tools count as shown. A memo from the steward
comes through relay's own tools and never makes an address fetchable.
relay.edn refuses an `egress` tool that relay cannot check: it must be an
envelope tool with known parameters, and it either fetches a `url` or
searches with free text named in `:queries`, never both.

## The steward's shell

A house role granted `:shell` gets `sh`, which runs a command in a Linux
container that relay keeps running for the data directory. relay builds
every runner argument (`relay.shell/run-argv`): `--network none`, a
read-only root, `/scratch` and `/tmp` in memory, and each `:house
:mounts` path read-only. The model's code runs only there; agentloop
stays on the host. What a command prints is `private` and carries the
labels of every session that used scratch since the container started.
`docs/house-shell.md` has the full picture.

## The daily note and recipe changes

A role improves its own skills, routines and notes without asking. The
daily note tells Kira which of those changes text from outside shaped.

```mermaid
sequenceDiagram
    autonumber
    participant T as relay tick
    participant St as Steward
    participant L as Log
    participant N as relay.digest
    actor K as Kira
    T->>St: 07:10, the daily-note routine fires a memo
    St->>L: daily_note_write: ahead, grants, github, learned
    T->>N: 07:30, compose the note
    N->>L: recipe changes since the last note
    N->>N: flagged: a role's change whose sources are outside
    N->>L: digest/composed and message/created, kind digest
    L-->>K: the note, flagged changes first, each numbered
    K->>L: revert 1, by reply, client key or relay revert 1
    L->>L: change/reverted, the old version written back
```

relay folds a recipe change from every event that writes a skill doc, a
routine, a role's note or a role (`relay.core/recipe-changes`). A change
is flagged when its sources name outside text (`relay.digest/flagged`),
so the model cannot leave one out. A change shaped only by Kira or the
role's own work is quiet and goes under "What I learned". Undoing puts
back what the change replaced, and several undos of one thing end at the
version before all of them. "not 2" turns down an offer and "drop 2"
removes an item for good.

## files_move and undo

A house role granted `:files` gets `files_move`. The steward plans with
its read-only shell, and relay's own code makes the moves on the host.

```mermaid
flowchart TB
    call["files_move call"] --> plan{"relay.files/plan<br/>every move checked first"}
    plan -->|"a move would overwrite, delete, leave the folders,<br/>or move a link or a granted folder"| refuse["whole call refused, nothing moves"]
    plan -->|"all allowed"| moving["files/moving recorded"]
    moving --> move["relay.files/move!<br/>atomic renames, folders made"]
    move --> moved["files/moved: what moved,<br/>fingerprints, folders made and emptied"]
    moved --> ready["relay ready and the client:<br/>the move, with its undo"]
    ready --> undo["relay undo N, or the client key"]
    undo --> check{"relay.moves/undo!<br/>each file as the move left it?"}
    check -->|"yes"| back["renamed back, empty folders it made removed"]
    check -->|"changed since"| stay["stays, named with why"]
    back --> undone["files/undone, and a memo to the role"]
    stay --> undone
```

A move never overwrites, deletes or copies. Undoing every call in
reverse order puts the tree back byte for byte, which a property test
checks (I7).

## The agent's calendar

Roles that are not public write the agent's own calendar with
`calendar_add`, `calendar_update` and `calendar_remove`. Only a role can
write it (`calendar-writer!` in `relay.core`), so the feed holds only
what a role wrote. `relay serve` renders it with `relay.ics/render` and
serves `/calendar.ics` on the WebSocket listener, which binds 127.0.0.1
and is published on the tailnet by `tailscale serve`. The feed has its
own token (`relay.websocket/feed-token`), which opens the calendar and
nothing else. Kira's own calendar comes in only through an `:ics` ingest
source and is never written.

## Where each invariant is enforced

The invariants come from `docs/plans/house.md`. Tests are in
`test/relay/`, pentests in `pentest/relay/pentest/scenarios/`.

| | Invariant | Enforced in | Tests | Pentests |
|---|---|---|---|---|
| I1 | No way out of the house | `check-grants` in `relay.config` refuses an `egress` tool on a house role, and a remote MCP tool that is not `read` or `edit`; `relay.tools/manifest` refuses again when building any session; `relay.shell/run-argv` always gives `--network none`, and `relay.shell/settings-problem` lets relay.edn name only the runner program | `shell_test`: `no-session-for-a-house-role-has-a-way-out`, `the-manifest-alone-keeps-a-house-role-in`, `the-shell-always-runs-with-no-network-and-read-only-mounts`; `house_test`: `only-a-public-role-may-have-an-egress-tool`; `shell_live_test` | `s18-shell-walls`, `s18-house-settings`, `s9-settings`, `s1-*` |
| I2 | Only Kira gets mail | `relay.messages/settings-problem` takes one plain address as `:to`; `relay.channels/email` writes From and To from the channel and fixed headers; `relay.channels/curl-argv` gives one `--mail-rcpt`; `relay.mail/send!` | `channels_test`: `every-delivery-goes-only-where-its-channel-says`, `hostile-messages-that-go-together-are-one-safe-email`; `mail_test` reads mail back from mailpit | `s19-mail-only-kira` |
| I3 | Scouts see nothing private | the public filters in `relay.core` (`shown-to-public?`, `fact-shown-to-public?`, `doc-shown-to-public?`); `check-grants` in `relay.config` refuses a private tool for a public role; `relay.tools/policy` sends `forbid_labels`; `refuse-private` in `relay.tools`; agentloop's forbid check | `house_test`: `scouts-see-only-public-items`, `forbid-private-reaches-agentloop-for-every-public-role`; `public_roles_test` | `s21-scout-sees-nothing-private` |
| I4 | Scouts fetch only what they were shown | `relay.shown/refusal`, called by `egress-call!` in `relay.tools` before any `egress` call runs; `relay.config/egress-problem` refuses an `egress` tool relay cannot check; webtool reports redirects | `shown_test`: `i4-a-call-runs-only-with-addresses-it-was-shown`; `scout_test`; `house_test`: `an-egress-tool-takes-only-what-relay-can-check` | `s22-scout-fetch-rule` |
| I5 | Trust follows the hand that brought it in | `relay.core/entry-points` and `entry-labels`; `relay.mailbox/verdict` and `relay.ingest/mail-verdict` for mail | `house_test`: `the-operator-s-entry-points-are-trusted`, `provenance-is-a-function-of-the-entry-point`, `what-a-session-writes-carries-what-it-read`; `mailbox_test`: `no-unverified-mail-is-ever-trusted-whatever-its-bytes`; `ingest_test`: `no-unverified-mail-is-ever-recorded-as-the-operators` | `s20-forged-operator-mail`, `s0-pasted-memo`, `s5-acp-pasted-resource` |
| I6 | Sanitizing is idempotent and visible | `relay.text/sanitize` and `sanitize-input`, called by the CLI's writes, `relay.hub` (prompts, card ops, replies) and `relay.ingest` (Kira's verified mail) | `sanitize_test`: `sanitize-is-idempotent`, `the-output-has-nothing-hidden`, `printable-ascii-passes-unchanged`, `every-change-is-reported`; `sanitize_cli_test`; `sanitize_hub_test` | `s23-paste-command-line`, `s23-paste-conversation` |
| I7 | Nothing the steward does destroys | `relay.shell/run-argv` mounts read-only; `relay.files/plan` and `move!` never overwrite or delete; `relay.moves/undo!`; `relay.log` only appends | `files_test`: `undoing-every-call-restores-the-tree-byte-for-byte`, `a-move-never-overwrites-deletes-or-leaves-the-folders`; `shell_live_test`; `log_test` | `s24-files-never-destroyed`, `s18-shell-walls` |
| I8 | Every old log still folds | `relay.core/removed-event-kinds` and `evolve` | `removed_events_test`; `messages_test`: `every-old-notification-folds-into-a-message`; `calendar_test`: `an-old-log-has-no-calendar` | `s25-old-log-folds` |
| I9 | The calendar is the agent's | `calendar-writer!` in `relay.core` refuses the operator, the dispatcher and public roles; `relay.core/calendar-feed` and `relay.ics/render`; `relay.websocket/decide-calendar` and `feed-token`; `relay.serve` binds the listener to 127.0.0.1 | `calendar_test`: `the-feed-has-only-what-roles-wrote`; `calendar_serve_test`; `ics_test` | `s26-calendar-feed` |

## Reading list

- `README.md`: setup, settings and daily use.
- `resources/AGENTS.md`: what relay tells its own roles.
- `spec/relay.allium`: the rules as a specification.
- `docs/messages.md`: messages, routing and channels in detail.
- `docs/house-shell.md`: the steward's shell in detail.
- `docs/client-protocol.md`: the ACP extensions clients use.
