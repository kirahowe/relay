# The saved view puts a checklist's cursor back by line number

## Context

relay-tui. Sent by the relay#100 session on 2026-11-15, from relay#100's
fourth review round. It is how the board behaves on trunk, and
relay#100's chain does not fix it.

The reviewer's text:

> The saved view puts a checklist's cursor back by line number, on
> another item after a role's insert, with no notice. It is round
> three's first finding for the kinds the fix does not cover. Sequence:
> checklist 10 with items 1, 2, 3, cursor on item 2, quit. A role adds
> item 9 above while the client is closed. After the restart the cursor
> is on line 2, which is now item 1; no message.

The relay#100 session's note: relay#100 closed this for surface cards,
where a box has no id, by opening on the title after a restart. A
checklist item and a tracker row have ids, so the saved view could keep
the id and follow it, or open on the title as a surface does.

## Acceptance criteria

The first is the relay#100 session's suggestion.

- [ ] After a restart the cursor of an open checklist or tracker is on
  the item or row it was left on, or on the title with a note when that
  one has gone. A test adds an item above while the client is closed.
- [ ] `make check` passes.

## Plan

## Log

