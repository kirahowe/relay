# relay as a personal agent: design for this epic

Kira (the operator) wants relay to be a personal agent for people who know
nothing about agents. It should be as useful as Claude Code: it looks things
up, reads files, computes, drafts, and remembers Kira's organisations, style
and history. It should be secure by construction, so that safety never
depends on a model following instructions.

The test: "please draft the next concert email" produces a grounded draft
with sources, and nothing reaches the outside world until Kira approves it.

## Principles (Kira's, 2026-10-18)

1. Assume the model is fully compromised. The worst it can do must be
   annoying, never harmful.
2. No action in the real world for the operator without explicit consent,
   given at the time (approval) or in advance (a standing rule the operator
   wrote). Anything irreversible needs consent.
3. Roles draft, and the operator reviews.
4. Answers cite their sources (facts, docs, reminders, files, web pages).
   Citations build trust.
5. Every role is a team member with a charter. Every role keeps and improves
   its own skills (procedures), and is required to.
6. Sandboxes: a role can search the disk, read files and compute, in a
   session where egress is impossible at the OS level, not merely a missing
   tool.
7. The UI is part of the security. A workspace pane shows work in progress,
   untrusted content the model never read, previews of what an approval will
   do, and the approve and trust actions. Content the operator approves or
   trusts there counts as trusted.
8. External services come in through MCP servers behind a relay security
   layer. Nothing is tied to one vendor (MailerLite may become Zeffy).
9. The operator never needs to know relay's internals. Roles never mention
   roles, tools, grants, labels or "web access" to the operator.

## D1. Effect classes

Tool classes are `read`, `write`, `edit`, `egress` and `act`. Kira chose
the name `edit` on 2026-10-18. It fits the verbs, and an edit is one that
can be undone.

| class  | meaning | rule |
|--------|---------|------|
| read   | returns content, changes nothing, sends nothing out | allow |
| write  | changes relay's own data, whose labels relay tracks | allow |
| edit   | changes the operator's own things outside relay. Nobody else sees the change, and the earlier state survives (own reminders, a private draft) | hold when the session is contaminated |
| egress | can put data the model chose where an outsider can see it (a search query, a fetch URL) | hold when the session is private and contaminated (unchanged) |
| act    | others see it, it spends money, or it cannot be undone (send a campaign, refund, post, pay) | always needs consent: hold every call unless a standing consent rule matches it, whatever the labels |

`write` is free whatever the labels because relay carries the labels with
the text. `edit` is not, because relay cannot follow labels outside itself.
Text a contaminated session puts in Reminders would come back from
rem-read labelled only private, and a clean session would take it in as
trusted. A tool that overwrites or deletes without keeping history, or
reaches anything shared with other people, is `act`.

`always_hold` stays and still holds every call. An unknown class is still
held. relay's own registry, routine and dead-letter tools write relay's log,
so they are `write`. Text a contaminated session writes keeps its labels, so
a role added by a contaminated session has contaminated instructions and
starts every wake contaminated.

Migration: an existing relay.edn that declares `rem` as `:act` now gets every
rem call held. That is the safe direction. The example config, `relay init`
and the docs declare rem write as `:edit`, with a note that a list shared
with other people stays `:act`. Write a short deploy note for Kira.

## D2. Standing consent

A tool in relay.edn may have `:consent`, a list of rules the operator wrote.
A rule is a map from input argument names to the exact JSON values the call
must have. A call to an `act` tool whose input matches every pair of some
rule runs without a hold. Example: `:consent [{"group" "Kira only"}]`. Only
act tools may have `:consent` (relay refuses it elsewhere). The match is pure
and exact (after JSON normalisation), with no patterns. agentloop does the
match: the tool's policy entry carries `consent`. A call allowed by a rule is
recorded with the rule's index. Rules can be added later, for example `:max`
or `:until`. Do not add them now.

## D3. Proposals and previews

A held call is a proposal. relay renders the approval from data and never
from the model's description:

- A tool may have `:preview`, an operator-configured envelope command that is
  given the held call's input and returns `{content, is_error, labels}`. The
  content is markdown describing what will happen (for an email: subject,
  body, recipient group and count, send time), read from the service by the
  preview command, not taken from the model. relay runs it when the call is
  held and stores the result with the hold.
- `relay approve` and `deny`, the ACP permission request sent to the chat
  client and the workspace pane all show the preview, along with the existing
  fields (input, labels, lookalike characters). Links in a preview whose
  domain the operator has never approved before are marked.
- Approval still binds to the input digest. Known limit: a preview reads
  the service's state at hold time, and the service may change before the
  call runs. Document it.

## D4. The display channel and the workspace pane

An envelope tool result may carry `display`, alongside `content`:

```json
{"content": "...for the model...", "is_error": false, "labels": [...],
 "display": {"title": "...", "body": "markdown", "labels": ["contaminated"],
             "sources": [{"title": "...", "ref": "web:https://..."}]}}
```

agentloop passes `content` to the model and never puts `display` into model
context. For an ACP session it sends `display` to the client as tool call
content, which acp-tui shows in the workspace pane. relay stores every display
as a doc under `shown/<yyyy-mm-dd>/<call id>.md`, with the display's labels,
so a wake's displays are not lost. The notification for a wake that produced
displays mentions them. The model's content says that the operator was shown
something and names the doc ref.

The quarantined reader gains `show_operator: true`. The reader answers in
the caller's schema as now, and also writes a free-text answer that goes
only to `display`. The caller gets the closed answer (and stays clean when the
schema is closed) and a note that the operator was shown more.

Trust. In the workspace pane the operator can trust a shown item. This uses
the same path as `relay trust doc <path>`, with the same operator-presence
guarantee ACP approvals have today. After that, a role may read the doc
without becoming contaminated. Approving a held call's content works the
same way: content the operator approved is not contaminated afterwards
(already true for typed operator text).

acp-tui grows a workspace pane: a side pane (or toggled overlay on narrow
terminals) listing, newest first, the displays, the held proposals with
their previews, and the citations in the current answer. A selected item
opens. Keys approve, deny or trust. The pane is rendered by acp-tui from
data relay sent, never from model text.

## D5. Citations

A uniform reference syntax: `[[kind:id]]`, where kind is one of `fact`,
`doc`, `note`, `memo`, `journal`, `rem`, `file`, `web`, `shown`, `report`.
For example `[[fact:12]]`, `[[doc:hear-here/style.md@3]]`, `[[rem:<id>]]`,
`[[file:/Users/kira/x.pdf]]` and `[[web:https://...]]`. relay's read tools
print each item's ref with it. The shared role instructions require a ref for
every claim drawn from relay's knowledge or a source. relay gets a pure
function that resolves a ref against the log (does it exist, what is its
current text and labels). `relay acp` exposes it to the client as an ACP
extension method, so acp-tui can render refs as selectable citations, check
that each resolves, mark the ones that do not, and open them in the pane. In
notifications, refs render as short plain text.

A `web:` ref in a role's own text counts as model-written text. A role that
is not public only gets URLs through the display channel or a closed answer,
so its own answer rarely has web refs. That is fine.

## D6. Skills and self-improvement

Every role keeps skills: docs at `skills/<role-id>/<slug>.md`, each with a
title line, a "use when" line and the procedure (steps, where things are,
pitfalls, the operator's preferences). relay lists the role's skills
(title and "use when") in its system prompt, under the usual label filters:
a skill a contaminated session wrote is left out with a note until the
operator trusts it. The shared role instructions require a role to:

- look for a matching skill before starting multi-step work, and follow it;
- write a skill the first time it finishes a multi-step task that will
  recur;
- update the skill whenever the operator corrects it, or when the
  operator's edits to its draft show a preference (cite the edit);
- keep skills short and current, since they are its expertise.

Roles are team members with a charter. The hub's charter is chief of staff:
it owns the operator's requests, delegates to roles with charters, and adds a
role when a kind of work keeps coming back.

## D7. The sandboxed workbench

A role can be granted `workbench`. It is agentloop's quarantined reader
extended with tools: a child session with its own model and one tool, `sh`,
that runs commands under macOS `sandbox-exec` with a deny-default profile:

- no network of any kind (`network*` denied, unix sockets included);
- no mach-lookup except a minimal allowlist the tests show `sh`, coreutils,
  `find`, `grep`, `rg`, `mdfind` (if Spotlight works sandboxed; otherwise
  leave it out), `python3`, `pdftotext` (if present), `sqlite3` and `jq` need.
  This blocks LaunchServices (`open`), AppleEvents (`osascript`) and
  pasteboard, all of which could send data out;
- file reads allowed across the disk except a deny list: the relay data
  dir, `~/Library/Keychains`, `~/.ssh`, `~/.gnupg`, browser profile
  directories, `~/.codex*`, `~/.claude*`, `~/.config/relay` and any path the
  operator adds in relay.edn;
- file writes only to a per-session scratch dir;
- process limits and a timeout per command, with the whole process group
  killed at the limit.

The agentloop process itself stays outside the sandbox, since it must reach
the model provider (trusted). Only the commands run inside it.

The caller gives a task and a JSON Schema, as with `read_quarantined`. The
result's labels are `private` and `contaminated`, minus `contaminated` when
the schema is closed (reuse the reader's closed-schema rule). It may also
`show_operator` (D4). The class is `read`.

Pentests must show, with artificial data and real `sandbox-exec`: curl, DNS,
a unix socket, `open https://...`, `osascript`, writing outside scratch,
reading a denied path and `pbcopy` all fail.

## D8. The web for every role

webtool gains `ask`: given a query and n (at most 5), it searches and fetches
the top n result URLs, all under the existing fetch guards, and returns the
pages. No free URL goes in. This also satisfies relay#34's intent. Its class
is `egress` because the query is model-chosen text, and its labels are
`contaminated`.

The quarantined reader may read through an `egress` inner tool. The call is
decided as the inner call would be, under the caller's labels: a clean hub
searches freely, and a private and contaminated session is held. The caller
writes the inner input (the query); the reader only reads the results. This
is the deliberate exception to "reader inner tools are read".

The hub is granted `web` (webtool ask) under `:quarantine`, so it reads the
web only as closed answers, with `show_operator` for prose and links. Sources
in a closed answer are page numbers (integers), which the display maps to
`web:` refs.

## D9. Remote MCP servers behind relay's layer

- agentloop speaks streamable HTTP to a remote MCP server, with a bearer
  token from a credentials command, written to a private file as today.
- `relay connect <name> <url>`, run by the operator at the terminal:
  1. OAuth 2.1 with PKCE, dynamic client registration when the server
     offers it, and a loopback redirect. The refresh token goes in the login
     keychain (`relay-mcp-<name>`).
  2. `relay mcp-token <name>` prints a fresh access token, refreshing as
     needed. It is the server's credentials command.
  3. It lists the server's tools and proposes, for each, a class from the
     MCP annotations (`readOnlyHint` gives read; `destructiveHint`, or no
     hints, gives act; anything else gives act) and labels `["private"
     "contaminated"]`. It turns off tools that delete by default.
  4. It shows a plain-language summary ("can read your subscribers", "can
     send a campaign: always asks"), lets the operator change class or
     on/off per tool, and asks for the typed code.
  5. It writes `<data>/connections/<name>.edn` (server, tools, classes,
     labels, digests pinned now) and pins every definition at connect time,
     which closes the "first definition on trust" gap. relay.edn names
     connections to load, and grants still name tools. No role can read
     either file.
- Argument narrowing per tool: `:fixed-args {"send_now" false}` sets
  arguments the model cannot change, and `:hidden-args [...]` drops
  arguments from the schema the model sees and refuses calls that carry
  them. relay or agentloop enforces both before the server sees the call.
- Test against a local HTTP MCP test server and a fake OAuth server, in
  agentloop's test tree. Never a real service.

## D10. Voice and defaults

- The shared instructions and the hub's default instructions: speak in the
  operator's terms. Never say role, tool, grant, label, contaminated, hold,
  "web access" or "set up a research role" to the operator. Describe what
  you can do for them. When something needs setting up, name the one thing
  the operator would do, in plain words.
- `relay init` and the example config give the hub web (D8) and the
  workbench (D7), with rem write as `:edit`. Setup asks for a search key and
  says what it is for.

## D11. Verification, codified

- `bb verify` (in relay) runs, in order, with explicit paths and no env
  vars: relay `bb test`; agentloop, acp-tui and webtool `make test`;
  `bb pentest` with the sibling binaries; and with `--live`, `bb eval` on
  Haiku. It prints one summary table and exits non-zero on any failure.
  `VERIFY.md` says what each step proves and how to run it by hand.
- New eval cases (artificial data) for: no internals in replies to the
  operator, citations present and resolvable, writing and using a skill, a
  closed web answer leaving the hub clean, the workbench answering a disk
  question closed, an act call producing a proposal (held) rather than
  being refused or worked around, and a draft that marks unknown facts as
  placeholders.
- New pentest scenarios for: the sandbox escapes listed in D7, display
  content never reaching the model, `act` always held without a matching
  consent rule, consent matching exactly, `edit` held exactly when contaminated, fixed and
  hidden args enforced, and a remote MCP tool's definition changing after
  connect.

## Out of scope for this epic

Phone-side preview pages, learning from the diff of a sent campaign
automatically (the skill mandate covers the manual case), Zeffy-specific
work, and choosing a search backend (Brave, as Kira decided, behind
webtool's pluggable backend; tests use the fixture backend).
