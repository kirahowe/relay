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

- [x] Breaking the links in a single word of 8,000 characters takes
  under 50 ms, with a test that fails on the present pattern.
  - Receipt: 1.0 ms on this machine.
    `relay.text-test/a-very-long-word-does-not-slow-link-breaking` has
    the case, best of five runs under 50 ms; the old pattern took 505 ms.
- [x] The link tests' properties still pass.
  - Receipt: `bb test` on slposyyt, 1,196 of 1,197, the one failure
    being relay#99's container test. `relay.links-test` and
    `relay.text-test` pass whole.

## Plan

## Log

- 2026-11-15, the relay#118 session: fixed on relay#118's chain, in
  slposyyt. `relay.text/defang` on one word of 8,000 letters now takes
  1.0 ms on this machine.
  - The scheme pattern looked back from every character. `break-schemes`
    now walks the text once, from each `://` back over the scheme's own
    characters. `relay.text-test/the-one-pass-scheme-finder-breaks-what-the-pattern-broke`
    holds it to the old pattern's output on 2,000 generated strings.
  - The time test found a second fault: a word of 25,000 dotted labels
    overflowed the stack in the pattern that reads host names. A word
    over 500 characters now has every dot broken and is not read for
    hosts. A file name that long loses its exemption. That is my call:
    no real name is that long, and breaking too much is the safe side.
  - `relay.text-test/a-very-long-word-does-not-slow-link-breaking` has
    the issue's case (8,000 letters, the best of five runs under 50 ms)
    and three words of 100,000 characters under 1.5 s each for `defang`,
    `links-in` and `anchored`. The old pattern took 505 ms on 8,000
    letters, so it fails both.
  - `relay.links-test` (27 tests, its properties included) and
    `relay.text-test` pass.
- 2026-11-15 approved: 2026-11-15: Kira accepted it through the coordinating session's question dialog; every criterion is met. relay#130 removed the 500-character cap, since its breaker is linear; a-very-long-word-does-not-slow-link-breaking still holds and passes.
