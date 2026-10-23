# Keep the board on the dashboard whether or not a conversation is open

## Context

Kira, 2026-10-23: "boards don't seem to carry across chat sessions".

relay keeps one board for the operator: cards are log events, and
`_relay/cards` answers every active card to any connection. relay is not
the problem. acp-tui holds the board inside each conversation's state
(`Hub.Convs[i].State.Pane.Board`) and asks for it when that conversation
attaches. So the board exists only while a conversation is open. Reopen
the client with no role in focus, or focus a role whose conversation has
ended, and there is no board. Each role also keeps its own copy, with its
own selection.

## Acceptance criteria

- In hub mode the board belongs to the hub: one copy per connection,
  asked for once the connection is up, kept current by
  `_relay/cards/changed`.
- The board shows with no conversation open, and is the same board, with
  the same selection, whichever role is in focus.
- Card ops and card trust work from the hub's board with no conversation
  open.
- Single-agent mode (a plain ACP agent, or `relay acp <role>` embedded)
  keeps working as it does.
- A test that would have caught this: open a hub connection, focus no
  conversation, and see the cards.

## Log
