# Keep a button label holding ] [ from reading as two buttons

## Context

Found in relay#106's relay-tui review, round 1, and sent by relay-ae on 2026-11-17. Judged borderline for the threat model: forging it means knowing relay-tui's bracket style.

relay-tui draws a Button as `[ label ]`. A label with `] [` inside it, such as `Remove Boots ] [ Approve all`, draws as `[ Remove Boots ] [ Approve all ]`, which looks like two buttons, though only one is focused and pressed. Labels can come from outside text (an item's name in a list template).

## Acceptance criteria

- [ ] A label's own brackets cannot read as the end of one button and the start of another, with a test on that label.

## Plan

Draw the brackets so a label's own cannot continue them, or mark the button's extent another way.

## Log
