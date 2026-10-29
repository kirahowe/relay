# Read the Mac calendar and manage reminders through ingestors and an actuator

## Context

`tools/cal` and `tools/rem` are tools from before the house model. A role
calls them and the operator grants them, and `rem`'s write setup uses
`:class :act`, which relay no longer accepts. Kira uses Apple Calendar,
and calendars stored "On My Mac" have no ICS feed, so the `:ics` ingestor
can't replace EventKit. Kira wants the steward to read the calendar
without approving each read, and to manage her reminders.

Decisions (Kira, 2026-10-28):

- Keep EventKit access, but as house machinery. Reading the calendar and
  the reminders becomes model-free ingestors, and writing reminders
  becomes a model-free actuator. Remove the `cal` and `rem` tools.
- Reminders are internal, trusted information. Text read from the lists
  Kira configures is private and not outside text.
- The agent may write to every reminder list Kira names in `relay.edn`,
  and to no other list. Kira names only private lists, since EventKit
  can't tell whether an iCloud list is shared.
- Writes that a session shaped from outside text (its sources) go ahead,
  and the daily note flags them with the source and a revert, the same
  way it flags skill and routine changes.

Judgment calls (Claude):

- Provenance of reminder text is a set of every text an outside session
  asked to write, recorded before the write and never shrunk; text
  matching it reads as outside wherever it appears. Kira changing the
  text is the only way back. Chosen after two review rounds found holes
  in per-reminder tracking.
- Reverting an added reminder marks it completed and does not delete it.
  The binary has no delete command at all, so no bug or injected
  instruction can delete one of Kira's reminders.
- Reminder undo is per-field compare-and-swap with no history, and an undo
  never writes outside text. For each field a change set, if the reminder
  still holds what the change left, the field goes back to its earlier
  value; otherwise it is left alone and said so, so relay never overwrites
  her own edit. An earlier title or notes that an outside session wrote is
  not written back. Chosen after rounds 4 and 5 found chain-undo defects.
- A reminder write or undo that fails partway (an error, a timeout, relay
  stopping) counts as cancelled and is retried later; relay records a
  change only from a call the binary reports as done. Kira, 2026-10-29,
  after rounds 7 to 9 found holes in reconciling uncertain outcomes.
- A single Swift binary, `tools/eventkit`, replaces `cal`, `rem` and
  `tools/shared`. It prints JSON and relay cleans the text, so the Swift
  escaping code goes. One binary also means one permission grant.
- The binary drops TCC responsibility on launch (re-execs itself with
  `responsibility_spawnattrs_setdisclaim`), so macOS asks for access in
  its own name, not in the name of Terminal or bb. Otherwise a grant made
  in a terminal would not carry over to the brew service.
- The role named in a source's `:to` gets the read and write tools for
  that source. There is no separate grant to keep in sync.
- `:ics` and the EventKit calendar share the event shape, the snapshot
  and the `calendar:<source>` provenance label (renamed from `ics:`).
  The live log has no `ics:` labels, since the live relay.edn never had
  an `:ics` source.

## Acceptance criteria

- [x] `tools/eventkit` reads calendars, events and reminders as JSON, and
      adds, edits, completes and reopens reminders only in the lists it
      is given. It has no delete command.
  - Receipt: tools/eventkit/test.sh (strict parsing, --allow refusals, no delete or alarm path); real binary ran calendars, lists and relay's read argv (no-access, no usage error); commit tompkrts
- [x] `:kind :apple-calendar` and `:kind :apple-reminders` ingest sources
      send the `:to` role memos of what changed, with no approval.
  - Receipt: reminders_test the-apple-calendar-is-read-into-memos-with-no-approval, reminders-are-read-into-memos-that-are-private-and-not-outside-text
- [x] Calendar memos are labelled contaminated and `calendar:<source>`.
      Reminder memos are labelled private only.
  - Receipt: reminders_test the-apple-calendar-is-read-into-memos-with-no-approval, reminders-are-read-into-memos-that-are-private-and-not-outside-text; Kira's later decision: reminder text an outside session wrote reads as outside (shown-text-is-outside-iff-an-outside-session-ever-asked-to-write-it)
- [x] The `:to` role can read the latest calendar and reminders snapshot
      on demand.
  - Receipt: reminders_test the-source-s-role-can-read-the-operator-s-calendar-and-reminders, the-tools-are-only-for-the-role-a-source-reports-to
- [x] The `:to` role can add, edit, complete and reopen reminders in the
      configured lists. A write to any other list is refused in relay and
      in the binary.
  - Receipt: reminders_test a-reminder-is-added-edited-completed-and-reopened-in-the-lists-the-source-names, a-write-to-any-other-list-is-refused-in-relay-before-the-binary-is-called; eventkit_test a-list-outside-the-source-is-never-asked-of-the-binary
- [x] Every reminder write is in the log with the fields before and after.
  - Receipt: reminders_test every-kind-of-failure-cancels-the-action-and-records-failed-only; eventkit_test a-successful-call-records-only-what-it-asked-for-from-the-binarys-images
- [x] Writes shaped by outside text are flagged in the daily note and can
      be reverted. Revert semantics as above.
  - Receipt: reminders_test a-write-shaped-by-outside-text-is-flagged-with-its-source-and-a-revert, an-undo-that-leaves-a-field-alone-says-so-whatever-asked-for-it; eventkit_test an-undo-is-field-by-field-compare-and-swap-and-never-writes-outside-text (property)
- [x] Lost Calendar or Reminders access shows up as one outage alert
      that says how to grant access again.
  - Receipt: reminders_test lost-access-is-one-outage-alert-that-says-how-to-grant-it-again, relay-eventkit-access-asks-where-macos-can-answer
- [x] `tools/cal`, `tools/rem` and `tools/shared` are gone, along with
      their README steps and example config.
  - Receipt: commit tvypyntq; README steps and example :tools entries removed in yxxuqtuw/sknkrswp
- [x] The Homebrew formula builds and installs the binary.
  - Receipt: homebrew-tap commit 3617700 (local, not pushed): builds tools/eventkit on macOS into libexec; ruby -c ok. Not installed from the tap yet
- [x] README, resources/AGENTS.md and spec/relay.allium describe the new
      behaviour.
  - Receipt: commits sknkrswp and noztwxtv; allium check shows only the 4 earlier warnings
- [x] `bb verify` passes, and the Codex review loop converges.
  - Receipt: bb verify all six steps PASS after every round; Codex converged at round 10 (its one finding rejected: an interrupted undo that did land is recorded as done, which is what Kira asked for)

## Plan

Two streams: the binary (workspace `eventkit`) and relay (default
workspace), joined by this contract.

### The binary: `tools/eventkit/eventkit`

Built by `tools/eventkit/build.sh`; git-ignored. Every command prints one
JSON object on stdout and exits 0, or prints `{"error": <text>, "code":
<code>}` and exits 1. Codes: `usage`, `no-access` (Calendar or Reminders
access not granted), `not-found`, `refused` (a list not allowed),
`failed`. Options are strict; an unknown one is `usage`.

Times: a timed value is a UTC instant `2026-10-28T14:00:00Z`; a date-only
value is `2026-10-28`. A reminder's timed due date is given and read as
local wall time `2026-10-28T09:00` (no zone), since that is how Reminders
stores it.

- `eventkit access` asks for Calendar and Reminders access (prompting in
  a terminal), and prints `{"calendar": "granted"|"denied"|...,
  "reminders": ...}`.
- `eventkit calendars` prints `{"calendars": [{"id", "title", "source",
  "type"}]}`; type is `local`, `calDAV`, `exchange`, `subscription`,
  `birthday`.
- `eventkit events --from <instant> --to <instant> [--calendar <title>]...`
  prints `{"events": [{"key", "id", "calendar", "title", "start", "end",
  "all_day", "location", "notes", "url", "status", "modified"}]}`. `key`
  is stable per occurrence: external identifier plus the occurrence start.
  For an all-day event, start and end are dates and end is exclusive, as
  in iCalendar. No `--calendar` means every calendar.
- `eventkit lists` prints `{"lists": [{"id", "title", "source",
  "markers"}]}`; markers is a list of `subscribed`, `delegate`,
  `read-only`.
- `eventkit reminders --list <title>...` (at least one) prints
  `{"reminders": [{"id", "list", "title", "notes", "due", "completed",
  "completed_at", "modified"}]}`: every unfinished reminder in those lists
  and those completed in the last 14 days. `due` is null, a date or a
  local wall time.
- Writes all take `--allow <title>` one or more times before the command
  word, and refuse (`refused`) when the reminder's current list or its
  destination is not one of them:
  - `eventkit --allow L... add --list L --title T [--due D] [--notes N]`
    prints `{"after": <reminder>}`.
  - `eventkit --allow L... set <id> [--title T] [--due D | --clear-due]
    [--notes N | --clear-notes] [--list L]` (a clear flag conflicts with
    its value flag, a `usage` error; no value is magic) prints `{"before": <reminder>, "after":
    <reminder>}`.
  - `eventkit --allow L... done <id>` and `reopen <id>` print before and
    after.
  An id must match exactly; no prefixes. There is no delete command.
- On start the binary re-execs itself with TCC responsibility disclaimed,
  so macOS attributes access to the binary, not to its parent.

### relay

- `:ingest` sources `{:kind :apple-calendar [:calendars ["Home"]] :to
  "steward"}` and `{:kind :apple-reminders :lists ["Personal"] :to
  "steward"}`, with optional `:bin` (default: relay's own
  `tools/eventkit/eventkit`). Polled every 15 minutes for calendar and 5
  for reminders by default.
- Calendar events use the same event shape, diff and memo as `:ics`, and
  both kinds keep a snapshot in state. Labels `contaminated` and
  `calendar:<source>`.
- Reminders: memo of new, changed and completed reminders, labelled
  `private` only.
- Tools for a source's `:to` role: read the operator's calendar in a
  range, read the reminders snapshot, and add, edit, complete and reopen
  a reminder. Writes run on the host like `files_move`: recorded before
  (`reminders/changing`) and after (`reminders/changed` with before and
  after, or `reminders/failed`), then the snapshot updates.
- Text written into a reminder is cleaned the same way as text sent to
  the operator.
- Reminder writes are changes in the daily note's sense, flagged only
  when shaped by outside text, and revertable as described above.

## Log

- 2026-10-28: Built. Binary in tompkrts/tvypyntq, relay side in
  yxxuqtuw/sknkrswp, plus kzsqovvm (relay sent fractional-second
  instants the real binary refuses; bb verify now runs relay's read
  arguments through the real binary). Formula change in homebrew-tap
  3617700, local only. bb verify passes in full.
- 2026-10-28: Codex round 1 found reminder text laundering and four
  related defects; round 2 found five more in per-reminder provenance,
  so the design changed to one set of outside texts that only grows
  (pokutsmm, ppxztonv) and reverts restore before-images (zqlrnvlv).
- 2026-10-28: Codex round 3 found three in scope: daily_note_draft
  quotes a reminder title with the change record's labels instead of
  the outside-text set; a due-date edit plus undo can remove an
  independent alarm that sat at the new due time; reverts of add, done
  and reopen don't check whether Kira changed the reminder since.
  Third round with a finding, so stopped to ask Kira per AGENTS.md.
- 2026-10-28: Kira: fix every finding at its source, not with a patch.
  Rounds 4 to 6 replaced history-based reminder undo with per-field
  compare-and-swap and the rule that an undo never writes outside text.
- 2026-10-29: Rounds 7 to 9 found holes in reconciling writes whose
  outcome was unknown. Kira: remove reconciliation; a write or undo that
  fails partway counts as cancelled and is retried later. Round 10 found
  nothing in scope. The review commits are folded into noztwxtv ("Harden
  reminder writes and undo after adversarial review"). Left for Kira:
  run `relay eventkit-access` once in a terminal after upgrading, check
  that macOS lists the grant under the eventkit binary, and check that a
  timed reminder relay adds alerts at its due time.
