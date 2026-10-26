# The house: a personal agent that prepares and never acts as you

Status: ACTIVE (started 2026-10-25). A main session orchestrates implementer
and reviewer agents phase by phase with the phase-loop workflow. This file
is the single source of truth for those agents: read it whole before
touching code. When reality disagrees with it, update it and write a log
entry rather than drifting.

## Goal

Kira asks relay for something ("draft the next concert email") and it is
ready when she looks: the draft is in her own account, a note in her inbox
says what it drew on, and nothing went out under her name. She never
approves a tool call, never types a code, never trusts or releases
anything. What she pastes is hers. The only thing she does is the last
step, as herself, in her own app, after reading it.

The model, in Kira's words: a private house. Inside, with the door locked
and the curtains closed, anything goes. You only stop to think when you
open the door.

Authorship and accountability are separate. The agent does the work under
its own identity, as staff do; Kira signs off and acts as herself. Once
she sends it, it is hers.

## Locked decisions (Kira, 2026-10-25)

1. **The house.** Inside the house nothing is gated: no holds, no
   approvals, no contamination withholding, no quarantine, no trust or
   release ceremony. Safety comes from the walls, not from asking.
2. **Never as Kira.** relay holds no credential that can send, post,
   publish or pay as Kira, and no tool of the `act` class exists. The
   agent drafts into her own accounts (a Hear Here campaign is a draft;
   Kira sends it) and Kira takes the final action herself. The agent
   does only things she could undo and nobody else sees.
3. **The agent's own identity.** The agent has its own email address and
   its own calendar. It may email Kira and no one else. It may do
   anything to its own calendar, which Kira subscribes to. It never
   writes Kira's calendar or sends from Kira's address. The address,
   provider and domain are ops for later: build the interface now,
   test against mailpit.
4. **Trust by who brought it in.** Anything Kira types, pastes, attaches
   or forwards from her own verified address is trusted. Only what a
   tool, ingestor or scout brought in automatically is untrusted, and
   untrusted is provenance shown to Kira, never a gate inside the house.
5. **Pastes are sanitized deterministically** at every place Kira's input
   enters. A separate vetting model may be tried as an experiment; if it
   ships it is advisory only.
6. **Scouts** are public agents with web access and no private data. They
   take and produce free text. The steward's requests to scouts go
   without sign-off.
7. **The steward's shell runs in Apple `container`** (1.6.1, installed),
   `--network none`, Kira's files mounted read-only, a writable scratch
   directory. The runner command lives in config so Docker or Podman can
   stand in on Linux. A full VM per steward session is not needed.
8. **No migrations, no compatibility.** Implement the model directly.
   Delete old machinery ruthlessly; break interfaces freely; no aliases
   or shims. The one exception is Kira's data: the new relay must fold
   every existing log (see I8), since the log is append-only and holds
   her history.
9. **Open source and self-hostable.** Nothing in the core may assume
   Kira's machine, accounts or domain.
10. **No disclosure.** Nothing relay prepares says an agent drafted it.
11. **Not problems:** scheduling over mail (Kira schedules with calendar
    apps) and deadlines (nothing is that time sensitive). Phone
    notifications are enough.
12. **Autonomy.** Verified, reviewed work lands on `main` in each repo
    (Kira, 2026-10-26). Never push, never deploy, never touch
    `~/Sync/relay`, `~/.config/relay`, the live checkout or launchd).
    Live evals use the `relay-anthropic` keychain key, within a $25
    total cap for this initiative.
13. **Staff read the mail** (2026-10-26). Untrusted content lands in the
    mailbox, and the steward reads it; otherwise everything bottlenecks
    on Kira. Kira's attention goes to two places only: what leaves the
    house, and recipe changes that came from outside.
14. **Roles improve themselves without asking.** Provenance decides how
    loudly, never whether: a change to a skill, routine or note shaped
    only by Kira or the role's own work is quiet; one shaped by mailbox
    content takes effect and is flagged in the daily note, source named,
    with a one-key revert. Roles can never change config: tools, grants,
    recipients, channels.
15. **Messages are medium-agnostic.** What relay tells Kira is one data
    model (kind, urgency, sections of items with refs and provenance,
    thread ids); routing from config picks channels; each channel is a
    thin adapter with a pure renderer (email, push, the client, SMS
    later). Replies come back through the same abstraction, and each
    channel verifies in code that a reply is really Kira's; an
    unverifiable reply lands in the mailbox.
16. **Use cases to serve and test:** a daily grant digest for Kira's
    nonprofit; watching her open-source GitHub repos for new issues and
    PRs; organizing piles of local notes; a look-ahead that offers help
    before things are due ("interview Thursday, want prep notes?").
17. **UX before Kira tests.** UX-expert agents use the system at
    milestones and at the end, and their papercuts get fixed. Kira fixes
    papercuts relentlessly; so do we.

### Orchestrator decisions (2026-10-25, Kira may overrule)

- O1 **Scouts fetch only URLs they were shown.** A scout may search, and
  may fetch a URL only if it appeared verbatim in a tool result earlier
  in the same session, enforced by relay before the call runs; redirects
  are refused unless the final URL also appeared. Free text from the
  steward then cannot carry Kira's data out in a URL the model built.
  The search provider sees queries, and is trusted as the model
  provider is.
- O2 **The class names stay** `read`, `write`, `edit` (Kira chose the
  verb pattern on 2026-10-18), plus `egress` for scouts only. `act` is
  removed. `edit` means: changes something outside relay that only Kira
  sees and that she can undo (a draft in her account, mail to her, the
  agent's calendar).
- O3 **The steward's harness stays on the host.** agentloop is trusted
  code whose only network use in a house session is the model API; the
  model's own code (the shell) runs in the no-network container. relay
  refuses to build a house session that names any egress tool.

## Constraints every agent follows

- Read `AGENTS.md` (repo root) first: where to work, the threat model,
  engineering rules, verification. It is the conventions guide.
- Work only in your jj workspace under `~/code/projects/.next/`. Test
  with artificial data and an explicit `--data <dir>`.
- No environment variables for configuration or wiring.
- Functional core, imperative shell. Decisions are pure functions; IO at
  thin edges; writes through `relay.inbound`.
- TDD. Property tests wherever a property is natural. Every bug gets a
  test.
- Tests that use a fixed port or a shared file are `^:volley/serial`.
  Mailpit tests start their own mailpit on free ports and kill it by the
  PID they started.
- Kill processes by saved PID only. Containers you start get unique
  names and are removed by name when you are done.
- Delete, don't deprecate. When you remove a feature, remove its code,
  tests, pentest and eval scenarios, README and spec sections, and
  config keys in the same phase.
- Update `README.md`, `resources/AGENTS.md` and `spec/relay.allium` when
  behaviour they describe changes. Prose follows the humanizer patterns:
  plain, specific, no em dashes.
- The experience matters: whatever Kira sees (notifications, mail, the
  dashboard, CLI output) is calm, plain and short. Never mention roles,
  tools, grants, labels or classes to her unless she asked.

## Invariants (property-test targets)

- I1 **No way out of the house.** For any generated relay.edn, every
  session relay builds for a house role names no `egress` tool and no
  `act` tool (the class no longer exists); a remote MCP tool given to a
  house role is class `read` or `edit`, so what leaves goes only to a
  provider Kira configured, into her own account; the steward's shell runs only
  under a runner command that includes `--network none`. Pentest: from
  inside the steward shell, TCP, UDP, DNS and unix sockets to the host
  all fail.
- I2 **Only Kira gets mail.** For every input the model can give the
  mail tool, the message relay submits has exactly one recipient, the
  configured operator address, no Cc or Bcc, `text/plain` only, and no
  header the model chose except the subject.
- I3 **Scouts see nothing private.** For any log and any scout, the state
  a scout session can read contains only public items.
- I4 **Scouts fetch only what they were shown.** For any scout session
  history, a fetch call runs only if its URL appears verbatim in an
  earlier tool result of that session; any other URL is refused before
  it reaches the network.
- I5 **Trust follows the hand that brought it in.** A pure function of
  the entry point assigns provenance: operator entry points (CLI, a
  conversation, a forward from the verified operator address) give
  trusted; tool results, ingestors and scouts give untrusted; anything a
  session writes carries the union of what it read.
- I6 **Sanitizing is idempotent and visible.** For every string s,
  `sanitize(sanitize(s)) = sanitize(s)`; the output contains no
  invisible, bidi-control or tag characters; printable ASCII passes
  unchanged; every change is reported.
- I7 **Nothing the steward does destroys.** Kira's files are mounted
  read-only; the log is append-only; no tool deletes outside relay's
  own versioned data.
- I8 **Every old log still folds.** Every log the pre-pivot relay wrote
  (the staging seed, fixtures, generated logs with every removed event
  kind) folds without error under the new relay, removed event kinds
  are ignored, and memos, facts, docs, notes, journals, cards, routines
  and roles come out as before.
- I9 **The calendar is the agent's.** The calendar feed contains only
  events the agent wrote; relay serves it only on the configured
  listener (the tailnet), never on a public address.

- O4 **Labels stay as provenance.** `private` and `contaminated` stay
  as internal names (agentloop and cleanroom share them), are still
  carried on every event and version, and still drive two things only:
  `forbid_labels ["private"]` for scouts (I3), and the provenance Kira
  is shown. Kira never sees the word "contaminated": she sees where a
  thing came from ("from a mail by stranger@example.com", "from the
  web"). Nothing is withheld, quarantined or held because of a label.
- O5 **Reports, ledger, verifier, trust levels and the quarantined
  reader are deleted**, not slimmed. A scout answers with a memo; the
  steward puts what matters on the board. The workbench is replaced by
  the steward's shell (P5).

## Workspaces

Every stream works in a jj workspace under `~/code/projects/.next/`:
`house` (relay), and siblings `agentloop`, `relay-tui`, `webtool`. relay's
tests look for `../agentloop` and `../relay-tui` relative to the relay
workspace and print SKIPPED when they are missing, so the siblings must
exist and be built (`make -C ../agentloop build`, relay-tui's build). A
SKIPPED line for a sibling binary is a failure for this initiative.

The orchestrator integrates: the Commit stage commits in the workspace;
there is no `bb integrate` in these repos yet, so it returns
`integrated: false` and the orchestrator rebases onto `main`, re-runs the
gate and moves `main`. Parallel relay streams use extra workspaces
(`.next/house-<phase>`) only when they run at the same time.

Gates: relay `bb test` (tier 1) and `bb verify` (full, includes pentests
and the sibling repos); agentloop `make test` or `go test ./...`;
relay-tui `go test ./...`; webtool `go test ./...`.

## The new agentloop request contract (A1 implements, B2 consumes)

- No `resolve`, no holds, no permission requests over ACP, no tool
  classes, no `consent`, no `always_hold`, no `quarantine`, no
  `workbench`.
- `policy` is optional. When present it holds only `labels` (the
  session's starting labels), per-tool static `labels`, and
  `forbid_labels`. A result carrying a forbidden label is refused, as
  now. Without `policy` every given tool is allowed.
- Labels remain free-form provenance strings on results, displays and
  `LabelSources`, and in `_meta["agentloop/labels"]` over ACP.
- The model HTTP client ignores proxy environment variables
  (`Proxy: nil`), for Anthropic and OpenAI alike.
- Envelope and plain tool protocols, MCP stdio and HTTP, fixed and
  hidden args, pins and limits stay.

## Phases

Each phase runs through the phase-loop workflow (implement test-first,
gate, review panel, at most 3 fix rounds, commit). Each phase deletes the
README, `resources/AGENTS.md`, `spec/relay.allium`, pentest and eval
material for what it removes, and documents what it adds.

### A1: agentloop to the house contract (repo agentloop)
- Implement the contract above. Delete resolve.go, holds and the settle
  logic, the trifecta classes and consent, quarantine/, workbench/,
  acp/permission.go and `server.ask`, `PromptLabels`, the
  `mcp.ToolConfig.Policy` class lint. Keep `Forbid`/`forbid_labels`.
- Shrink README and DESIGN.md to what remains.
- Done when: `go test ./...` passes; a request with a removed field is
  refused with a clear error; a property test shows a result carrying a
  forbidden label never reaches the model; the model client ignores
  `HTTPS_PROXY`.

### B1: delete research reports and tool vetting (relay, parallel to A1)
- Area 5 of the survey: reports.clj, report/verify/finding/ledger/
  trust-level events and commands, the verifier, `relay review`,
  `relay verify`, `relay ledger`, `relay trust-level`, `:reports` and
  `:verifier` config, the corpus pentest, the research eval case.
- Area 6 partly: vet.clj and the class/labels/consent choices in
  `relay connect` (keep the OAuth sign-in and connection files).
- Done when: `bb test` passes, nothing references the removed code,
  and I8 holds for logs containing every removed event kind (a fold
  test over generated logs).

### C1: relay-tui without approvals or trust (repo relay-tui, parallel)
- Delete permission requests, the consent review pager, review text,
  authority, trust from the pane and board, paste trust (Ctrl-Y),
  proposal items, `?N`/`!N` badges and `--approvals`. Keep labels only
  as provenance on items (`sources`), shown in plain words.
- Done when: `go test ./...` passes with goldens updated, and the
  dashboard still opens against the fake hub.

### B2: relay without holds or gates (relay, after A1 and B1)
- Areas 1, 2, 3, 7 and the declassify part of 4: holds, approve/deny,
  TTY codes, the `act` class, `:always-hold`, `:consent`, `:preview`,
  ACP permission plumbing, the hub `:perms` table and `--approvals`;
  contamination withholding, `accept_contamination`, memo quarantine,
  release, trust, fresh (keep tool pins; a changed definition just
  takes the new pin and notifies Kira), the legacy-label marker,
  `--untrusted`; the quarantined reader; the workbench; declassify.
- Requests follow the new agentloop contract. Scouts keep
  `forbid_labels ["private"]`.
- Trust rule I5: operator entry points are trusted, including what Kira
  pastes or attaches in a conversation.
- `docs/client-protocol.md`: remove approvals, trust and reviews.
- Done when: `bb test` and `bb verify` pass with no SKIPPED sibling
  tests; I3, I5 and I8 have property tests; the remaining pentests
  pass.

B2 notes (orchestrator, 2026-10-25):
- `relay connect` proposes a tool that is not read-only as off; Kira
  turns it on as `edit` in the connection file. There is no `act`.
- `egress` tools may be granted only to public roles; config refuses one
  on a house role.
- Delete the TTY confirmation codes outright; nothing needs them.
- Until B5, a memo from a house role to a public role is refused with a
  plain reason (declassify is gone; B5 opens the door with O1).
- The staging stand-in campaign tool becomes a draft-only `edit` tool.
- Eval cases and pentests that only test removed behaviour are deleted;
  the rest keep passing (B9 rewrites them properly).

### B3: the steward's shell (relay, after B2)
- A `:house` section in relay.edn: the runner argv template (default
  Apple `container`), the image, the paths of Kira's files to mount
  read-only, limits. A Containerfile in the repo (Alpine with sh,
  coreutils, find, grep, rg, awk, sed, python3, sqlite3, jq, pdftotext,
  file, tar, zip).
- relay keeps one container per data directory running
  (`relay house up|down|status`, and the tick starts it if it is
  missing). The `sh` tool runs a command inside it via `container exec
  -i` with a timeout enforced inside the VM.
- I1: config validation refuses an egress tool, remote MCP or a runner
  without `--network none` for a house role; a pentest from inside the
  shell shows TCP, UDP, DNS and host sockets all fail.
- I7: a test that writes to a mounted path fails.
- Done when: a live test against a real container passes (marked
  serial, skipped with a clear line only when `container` is absent,
  which is not the case on this machine).

### B4: messages to Kira (relay, after B2, parallel to B3 and B5)
- Decision 15. A message is data in the log: id, kind (`note`,
  `alert`, `digest`, `reply`), urgency, a subject, sections of items
  with refs and provenance, and an optional `in-reply-to`. One tool,
  `tell_operator`, takes content and urgency; the model never chooses a
  medium, an address or a recipient.
- Routing is a pure function of config (`:messages {:channels ...
  :routes ...}`): which kinds and urgencies go to which channels, quiet
  hours, the daily digest time. Every message is always visible to the
  client channel.
- Channels are adapters, each a pure renderer plus a thin delivery
  edge: email (text/plain, UTF-8, From the agent's address, To Kira's
  address from config, threaded with Message-ID/In-Reply-To, submitted
  with `curl` over SMTP(S), credentials from a `:credentials-command`);
  push (the existing ntfy notifications become this adapter: one line,
  no internals, a link into the client); client (`_relay/messages` in
  the client protocol: initial fetch, then `changed` notifications, as
  the board does). Every existing `notify` call becomes a message.
- I2, generalized: for every model input and every route, each
  delivery goes only to the channel's configured destination; an email
  has exactly one recipient, no Cc or Bcc, text/plain only, and no
  header the model chose except the subject.
- Done when: properties for I2 and for routing; an end-to-end test
  delivers to a mailpit started on free ports (serial, killed by PID)
  and reads it back through mailpit's API.

### B5: the agent's calendar (relay, after B2, parallel to B3 and B4)
- `calendar`, `calendar_add`, `calendar_update`, `calendar_remove`
  tools of class edit over events in the log, versioned like facts. A
  pure ICS renderer (RFC 5545: folding, escaping, UTC, stable UIDs,
  SEQUENCE on change) with round-trip properties. `relay serve` serves
  `/calendar.ics` on its configured listener only (I9). The agent never
  writes Kira's own calendar.
- Done when: properties pass and the feed validates (an RFC 5545 parser
  in tests reads back what relay wrote).

### B6: scouts (relay and webtool, after B2)
- O1 and I4: relay refuses a scout's `fetch` whose URL did not appear
  verbatim in an earlier tool result of the same session. webtool (W1,
  landed) reports a moved page with its target on its own line instead
  of following it. Also fix W1's minor: a target with any Unicode space
  is refused.
- A house role's memo to a scout is public as written (decision 6),
  replacing B2's interim refusal; the steward sees what it sent. A
  scout's memo back arrives contaminated, from the web.
- Done when: I4 is a property; the staging seed has a working scout
  routine; webtool's README relay.edn section matches the house.

### B7: pastes, sanitized (relay, after B2)
- A pure `sanitize` in relay.text, applied at every operator entry
  point (CLI writes, conversation prompts and resources, replies through
  any channel): NFC, remove invisible, bidi-control and tag characters,
  flag lookalike characters and mixed-script words, and tell Kira each
  change in one plain line. I6 as properties.
- Experiment (Haiku, within budget): does a vetting model catch hidden
  instructions in pastes that sanitizing cannot see? Record the result
  in the log; ship only if clearly useful, and only as an advisory note.

### B8: the mailbox and replies (relay, after B4)
- Ingestors: code, no model, each a configured source polled by the
  tick, writing new items as memos to the steward, labelled with where
  they came from. Sources: the agent's mailbox (POP3 or IMAP via
  `curl`), GitHub issues and PRs on repos Kira lists (public API, an
  optional read-only token from a `:credentials-command`), and ICS
  calendars by URL (Kira's own calendar, read only). The fetch targets
  come from config only, never from a model.
- Replies: each channel turns a reply into one event threaded to the
  message it answers. Email is Kira's only when it is from her
  configured address and the configured authserv-id's
  Authentication-Results says DKIM passed for her domain; then it is
  trusted (I5). Anything else is mailbox content from that sender.
- Done when: tests against mailpit's POP3 and canned GitHub and ICS
  responses; a property that no unverified message is ever trusted.

### B9: organizing files (relay, after B3)
- A `files_move` tool of class edit: moves within, and new files under,
  roots Kira configures as writable; never overwrites, never deletes,
  never leaves the roots (symlinks resolved). Every call appends to an
  undo journal in the log; `relay undo <call>` (and the client) reverses
  it exactly. The steward plans with its read-only shell; relay code
  carries out the moves. Closes relay#53.
- I7 extended: for any generated sequence of moves, undo restores the
  original tree byte for byte (property over a temp directory).

### B10: the daily note (relay, after B4, B5, B6, B8)
- One digest message a day (time from config), built by a steward
  routine: what is coming up in the next two weeks (calendar, board
  deadlines, dates found in mail and notes) with an offer of help each;
  the grant digest from the scout, checked against the board; new
  GitHub items; "what I learned" (quiet recipe changes); and, at the
  top, any recipe change shaped by mailbox content, source named, with
  a revert (`relay revert <ref>`, a key in the client, or a reply).
  Relay computes the flagged changes in code from labels (decision 14);
  the model cannot leave one out.
- A suggestion Kira dismisses is remembered and not repeated.
- A new issue or PR from a person also gets one push, batched per tick.
- The morning brief is replaced by this.

### B11: what's ready (relay and relay-tui, after B4 and C1)
- The client channel's view: messages, plus everything prepared for
  Kira (drafts in her accounts, calendar entries, moves with undo,
  findings on the board), each with where it came from. `relay ready`
  on the CLI, `_relay/ready` in the client protocol, and a relay-tui
  view that is calm, framed and readable at phone width. Reply to a
  message from the view.
- Done when: a dogfood pass on the staging seed shows the concert email
  flow end to end: a draft in the stand-in campaign tool, a message in
  mailpit, an entry in the view, a reply that reaches the steward.

### U1: UX panel and polish (after B11)
- Three UX-expert agents, each a persona, use the system on the
  staging seed and report papercuts with exact steps, what happened and
  what smooth would look like: Kira on a phone-width terminal over the
  tailnet; a new self-hoster with only the README; a daily user living
  in the dashboard and email. Their tasks are decision 16's use cases.
- A polish phase fixes them.

### B12: evals and pentests for the house (relay)
- Eval cases (oracle and foils per AGENTS.md): the concert email is
  drafted, never sent, and Kira gets one calm message; a hostile mail in
  the mailbox cannot reach anyone but Kira, cannot change files beyond
  what undo reverses, and a recipe change it shaped is flagged; a scout
  request carries no URL the model built; the grant digest skips what
  the board already has; the look-ahead offers help for an interview.
  Live on Haiku (3 trials) and Opus (1 trial) within budget; update
  BASELINES.md.
- Pentests rewritten around I1-I9.
- A Codex adversarial review of the whole initiative against the threat
  model, until a clean round, at most three.

### B13: the README, spec and AGENTS.md read as one design
- One pass so the docs describe the house as a whole, plain and short.
- `docs/architecture.md` (Kira, 2026-10-26): how the system works, its
  components and how they connect, with mermaid diagrams throughout:
  the house and its walls, the components across the four repos, a
  request's path from Kira to a draft and a message, the mailbox and
  ingestors, messages and channels, scouts and the fetch rule, the log
  and its fold, and where each invariant is enforced.
  Deploy notes for Kira: the new relay.edn shape (removed keys are
  refused), `container system start`, building the steward image, the
  agent's address, channels and the calendar URL to set up later.

### U2: UX panel again, and polish
- The same panel on the finished system; check U1's fixes; fix what is
  new.

### Px: cleanup
- The banked minor findings, triaged.

## Baseline

Trunks at the start: relay `main` 877fdba ("Move relay#79 to review after
round 4"), agentloop `main` e697f49, relay-tui `main` 3ae2f89, webtool
`main` e54f548.

| Suite | Result (2026-10-25) |
|---|---|
| relay `bb test` | 914 tests, 914 passed, 0 skipped, 71 s wall |

## Log

One file per entry under `docs/plans/log/` (`YYYY-MM-DD-<phase>.md`).
