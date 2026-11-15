# Keep link breaking quick on one very long word

## Context

Sent by the relay#97 session on 2026-11-15, from the third review round
on relay#97. It is in `relay.text`, older than relay#97 and outside its
diff.

`relay.text`'s scheme pattern is quadratic on a long run with no space
(line 177 of `src/relay/text.clj` in relay#97's chain). `safe-text` on
one word of 8,000 letters takes about half a second, and a surface
holding four such strings took about two seconds for each op, since
`relay.cards` measures a surface as outside text before recording any
change. A pasted token or a minified blob would do it. It costs time
only; nothing is exposed. The same pattern runs on every card, message
and ready item made of outside text.

Checked by the relay#93 session when filing: on `integration` at
xxwnlrsk (8dd1545c), `relay.text/defang` on one word of 8,000 letters
took 505 ms.

## Acceptance criteria

- [ ] Breaking the links in a single word of 8,000 characters takes
  under 50 ms, with a test that fails on the present pattern.
- [ ] The link tests' properties still pass.

## Plan

## Log
