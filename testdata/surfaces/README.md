# Surface cards (relay#97)

Surfaces as a role writes them, and each one's card as relay sends it to
a client. relay's tests check the pair, and relay-tui draws the cards in
its renderer tests.

- `<name>.json`: what a role gives `card_add` for a surface: `title`,
  `components` and `data`. `labels` are the labels of the session that
  wrote it, and `live` the links relay keeps live while it is shown.
- `<name>.card.json`: the card's wire form for that surface, as
  `_relay/cards` sends it (`docs/client-protocol.md`, "A surface card"),
  added as card 1840 by the role `steward`.

`checklist` and `note` are two of the fixed kinds of card laid out as
surfaces. `hostile` was written by a session that read outside text: it
holds an address joined from two pieces, an escape sequence, a
zero-width character, a right-to-left override and one live link.

This copy, in relay, is the source. To change a card, change the surface,
run `bb surfaces`, which writes every card again, and copy the directory
to relay-tui.
