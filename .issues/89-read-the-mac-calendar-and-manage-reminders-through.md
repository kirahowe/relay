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

- Reverting an added reminder marks it completed and does not delete it.
  The binary has no delete command at all, so no bug or injected
  instruction can delete one of Kira's reminders.
- Reverting an edit puts back the fields as they were before, but only
  while the reminder still shows what relay wrote. If Kira changed it
  since, the revert refuses and says to fix it by hand, so relay never
  overwrites her own edit.
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

- [ ] `tools/eventkit` reads calendars, events and reminders as JSON, and
      adds, edits, completes and reopens reminders only in the lists it
      is given. It has no delete command.
- [ ] `:kind :apple-calendar` and `:kind :apple-reminders` ingest sources
      send the `:to` role memos of what changed, with no approval.
- [ ] Calendar memos are labelled contaminated and `calendar:<source>`.
      Reminder memos are labelled private only.
- [ ] The `:to` role can read the latest calendar and reminders snapshot
      on demand.
- [ ] The `:to` role can add, edit, complete and reopen reminders in the
      configured lists. A write to any other list is refused in relay and
      in the binary.
- [ ] Every reminder write is in the log with the fields before and after.
- [ ] Writes shaped by outside text are flagged in the daily note and can
      be reverted. Revert semantics as above.
- [ ] Lost Calendar or Reminders access shows up as one outage alert
      that says how to grant access again.
- [ ] `tools/cal`, `tools/rem` and `tools/shared` are gone, along with
      their README steps and example config.
- [ ] The Homebrew formula builds and installs the binary.
- [ ] README, resources/AGENTS.md and spec/relay.allium describe the new
      behaviour.
- [ ] `bb verify` passes, and the Codex review loop converges.

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
  - `eventkit --allow L... set <id> [--title T] [--due D|none] [--notes
    N|none] [--list L]` prints `{"before": <reminder>, "after":
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
