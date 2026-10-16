# List sections: what is possible

Question: can rem read which section of a Reminders list a reminder is in,
and add or move a reminder into a section? Checked on macOS 26 (Darwin
25.5), 2026-10-16. Short answer: no supported route exists for reading or
writing. rem was not changed.

I touched none of Kira's reminder content. Everything below comes from SDK
headers, framework resources shipped with macOS, and runtime introspection
of classes. Nothing needed Reminders access.

## 1. EventKit (macOS 26 SDK)

Found nothing.

- `grep -i section` over every header in `EventKit.framework/Headers`
  matches nothing. The only `group` hits are `EKParticipantTypeGroup` and an
  address-book deprecation note.
- `EventKit.tbd` has no symbol containing "section".
- Runtime check (a small Swift program listing selectors on `EKReminder`,
  `EKCalendar`, `EKCalendarItem`, `EKObject` and `EKEventStore`): no
  selector contains "section" except `inboxRepliedSectionHasContent` and
  `inboxRepliedSectionItems` on `EKEventStore`, which belong to the
  calendar invitation inbox.

So EventKit has no section concept, public or private. The section data
lives only in Apple's private ReminderKit layer. Calling that is not a
supported mechanism and rem does not.

Verdict: not feasible.

## 2. Shortcuts

Found no section parameter or property.

- `shortcuts` has `run`, `list`, `view` and `sign` only. `shortcuts run
  <name> --input-path <file>` can run a shortcut headless, so a shortcut
  would work as a write path if an action existed.
- The Reminders app's own App Intents metadata
  (`Reminders.app/Contents/Resources/Metadata.appintents/extract.actionsdata`)
  holds one action, "Open List", and one entity, `AnyListEntity`, with no
  properties.
- The built-in Reminders actions live in WorkflowKit. Its localisation
  table (4685 English strings) has no string mentioning a section. The
  parameters of "Add New Reminder" that appear in it are `WFCalendarDescriptor`
  (list), `WFParentTask` (parent reminder), `WFTags`, `WFPriority`, `WFFlag`
  and `WFURL`. A parent reminder is a subtask, not a section.
- ActionKit's localisation tables have no section string either, only a
  failure message that subtasks cannot be created in some accounts.

I could not read `~/Library/Shortcuts` (Operation not permitted), so I did
not inspect the live action database. The static evidence above is
consistent, but a newer action could exist that it does not show. Kira can
check in 30 seconds: in the Shortcuts app, add "Add New Reminder" and "Find
Reminders" and look for a Section parameter or filter. If one exists, the
write path would be: build a shortcut that takes a text input, then have
rem call `shortcuts run <name> --input-path <file>` with the arguments
written to a temp file, never interpolated into a command line.

Verdict: no section action found. Not feasible until someone finds one in
the Shortcuts app.

## 3. The Reminders Core Data store

Blocked, stopped.

`ls ~/Library/Group\ Containers/group.com.apple.reminders` returns
"Operation not permitted". The directory exists (owner kira, mode 0700,
dated 2025-04-03), but this process may not list it. That is a macOS
privacy check, so I did not try to get around it, and I read no schema or
data. To read the store, the process that runs rem would need Full Disk
Access (System Settings > Privacy & Security), and relay's launchd-run
tool would need it too.

Even with that permission I would not build on it:

- The schema is undocumented and changes between macOS releases.
- The store is Core Data in WAL mode. A read-only open sees a consistent
  snapshot, but only if the `-wal` and `-shm` files are readable and the
  open stays truly read-only (`mode=ro`, `PRAGMA query_only`). Anything
  else risks checkpointing someone else's database.
- Mapping an EventKit id to a store row is unverified. From memory (not
  checked here), EventKit's `calendarItemIdentifier` is the reminder's
  UUID, which the store keeps as an identifier column, and sections sit in
  a section table with membership held as data on the list row. Treat
  that as a guess, not a finding.
- Reading would cover only the read half. There is still no supported way to
  write.

Verdict: blocked by permission. Feasible only for reading, fragile, and it
needs Kira's decision to grant Full Disk Access.

## 4. AppleScript

Found nothing.

`sdef /System/Applications/Reminders.app` lists accounts, lists and
reminders. A reminder has name, id, container, creation and modification
dates, body, completed, completion date, due date, allday due date, remind
me date, priority and flagged. A list has id, name, container, color and
emblem. There is no section class, element or property. `container` of a
reminder is a list or a reminder (its parent), never a section.

Verdict: not feasible.

## What this means for rem

Nothing safe and supported exists, so rem gains no section column, no
`sections` command and no write path. The README says so in one paragraph.

If sections matter enough, the options are:

1. Check the Shortcuts app for a Section parameter (route 2). If found,
   the write path above is small and keeps rem's mode rules.
2. Grant Full Disk Access and accept a read-only, version-fragile reader
   of the Core Data store. I would want a fresh look at the real schema,
   on a test list, before writing it.
3. Work around sections instead: one list per aisle, or a prefix or tag in
   the title that the model sets and reads itself.

Testing any of this needs sections created by hand in the Reminders app:
make a list such as "relay-sections-test" and add sections there, since no
tool can create them.
