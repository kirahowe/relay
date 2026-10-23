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

2026-10-23, phase 2 (app/chat split in relay-tui):

- The split is in the types. `core.App` (one per connection) holds the
  board, which view the pane shows (`Board.Items`) and whether the pane
  has the keys (`PaneFocus`), plus `Live`, which says relay's methods can
  be called. `core.State` keeps the conversation: transcript, input,
  pastes, permissions and its own pane items. `core.Hub` gains an `App`
  field beside the roster; single-agent mode gets `core.Single{App;
  State}` and `ReduceSingle`. Phase 3 can put the saved view on `App` (or
  derive it from `App` plus the hub's focus and cursor). Embedding in
  `Single` keeps the single-mode code and tests readable; the two
  types share no field names.
- Pane focus and the board/items choice moved to the app, not just the
  cards. With no conversation open there is no chat state to hold them,
  and decision 7 saves "pane open or closed, board or items" per device.
- Consequence: only the conversation on screen may switch the pane to
  its items (a display or proposal) or take the keys from the pane (a
  permission request). A display in a background conversation no longer
  flips the shared view; it waits in that conversation's items.
- Moving to another role gives the keys back to the conversation
  (`PaneFocus` false). Keeping them made ctrl+n land on the board, not
  the chat the operator moved to see.
- Card trust safety: an unsent card trust is now dropped when the
  screen changes under it (focus, list, help, end question) and on
  ctrl+x. Before, a card trust survived ctrl+x away and back with its arm
  delay already spent. The arm delay, rev+sha256 match and
  approvals/refusal handling are unchanged.
- The board is asked for on HubConnected (after initialize) in hub
  mode, and on Connected (after session/new) against a single agent.
  ConvAttached no longer asks. Board effects (RequestCards, SendCardOp,
  SendCardTrust) are emitted bare, not wrapped in ForSession; the agent
  emits their answers unrouted.
- A snapshot (`_relay/cards` answer) that leaves out a card the client
  held now records it as gone by that rev, so an older copy that
  arrives later cannot bring it back. Found by the new property test of
  interleaved answers and notifications.
- With no role in focus (the list has the keys) the board shows beside
  the roles on a wide screen but takes no keys; focus a role (with or
  without a conversation) and ctrl+x. Left as is: making the list hand
  keys to the pane is a UX change for the main session to decide.
- relay: `_relay/cards/op` and `_relay/cards/trust` no longer accept
  `sessionId`. `_relay/cards`, which took any params and read only
  `archived`, now refuses anything else too, `sessionId` included, so all
  three board methods treat a session the same way. A client from before
  this change (an old acp-tui) gets an error on the board, so relay and
  relay-tui must be deployed together; `bb deploy` builds both.
- The relay-tui card fixtures (testdata/relay) were regenerated with
  scripts/card_fixtures.clj, which no longer adds a sessionId.
- Verified live: an artificial data dir with a made-up role (gardener)
  and a card added with `relay card add`, then `relay-tui -- relay acp
  --approvals --data <dir> --run-dir <dir>` in a pty at 170x40. The board
  showed beside the roles before any conversation opened; alt+1, ctrl+x,
  enter, j, x ticked an item, and `relay cards` showed it done, with no
  conversation opened (the role's agent was /usr/bin/false).
