# rem

A small command-line front end to Apple Reminders, built on EventKit. It
reads, adds, edits and completes reminders. It cannot delete anything, by
design: no command removes a reminder or a list, so a mistake stays
recoverable.

## Build

```sh
tools/rem/build.sh
```

This needs the Swift compiler (Xcode or the Command Line Tools) and
produces `tools/rem/rem`, which is git-ignored. `Info.plist` is linked into
the binary so macOS can show the Reminders usage description.

## Access

macOS asks once per binary for permission to use Reminders. The grant is
tied to the binary, so rebuilding or moving it may prompt again. If the
first run comes from an unattended process, the prompt may never appear
and access fails. Run any command that touches reminders once in an
interactive terminal and accept the prompt first. `rem help` does not need
access.

## Commands

Output is plain text, one item per line, with tab-separated fields.

- `rem help` prints every command.
- `rem lists` prints each list with its unfinished count.
  Example: `rem lists`
- `rem ls [--list <name>] [--today]` prints unfinished reminders as id,
  list, due date and title. `--today` shows what the Today smart list
  shows: due today or overdue.
  Example: `rem ls --list Groceries --today`
- `rem show <id>` prints one reminder in full, with notes.
  Example: `rem show 4F2A9C1B`
- `rem add <title> --list <name> [--due <when>] [--notes <text>]` creates a
  reminder and prints its line.
  Example: `rem add "Buy milk" --list Groceries --due "2026-01-15 09:00"`
- `rem set <id> [--title <t>] [--due <when>|none] [--list <name>] [--notes <text>]`
  edits a reminder. `--due none` clears the due date.
  Example: `rem set 4F2A9C1B --due 2026-01-16`
- `rem done <id>` marks a reminder completed.
  Example: `rem done 4F2A9C1B`

`<when>` is `YYYY-MM-DD`, or `"YYYY-MM-DD HH:MM"` for a timed reminder. A
timed reminder also gets an alert at that time. An id can be shortened to
any prefix that matches exactly one unfinished reminder.
