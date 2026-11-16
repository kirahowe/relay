# Break a host that follows another host's port with nothing between

## Context

Sent by the relay#97 session on 2026-11-15, from the third review round
on relay#97. It is in `relay.text`, older than relay#97 and outside its
diff.

`relay.text/defang` leaves a host whole when it is glued after
`host:digit`. The `host-candidate` pattern takes an optional `:\d` tail,
which consumes a digit, so the next host starts in the middle of a run
and is never considered. The reviewer's reproductions:

    "a.bc:1evil.com/steal?d=1" => "a[.]bc:1evil.com/steal?d=1"
    "x.yz:9www.evil.com"       => "x[.]yz:9www.evil.com"
    "me@a.bc:1evil.com/x"      => "me@a[.]bc:1evil.com/x"

It affects every text an outsider may have shaped, not only surfaces.
It came up through a surface whose formatString joined
`evil.example:8080` to itself.

Checked by the relay#93 session when filing, on `integration` at
xxwnlrsk (8dd1545c):

- All three reproduce exactly (`host-candidate` is at
  `src/relay/text.clj:117` there).
- Apple's NSDataDetector follows each of the three as relay shows it
  today. From `a[.]bc:1evil.com/steal?d=1` it makes
  `https://1evil.com/steal?d=1`, from `x[.]yz:9www.evil.com` it makes
  `https://9www.evil.com`, and from `me@a[.]bc:1evil.com/x` it makes
  `https://1evil.com/x`. It does the same with words on either side.

So this is a link with a path and a query, in outside text, that mail
and the phone follow whole. The relay#97 session had it at p3, as a
string someone has to craft against relay's own pattern, which the
threat model leaves out. I raised it to p2 because of what Apple's
finder does with it, and because a role that joins two strings with
nothing between can make one by mistake, as the surface did.

## Acceptance criteria

- [x] In outside text, a host that directly follows `host:port` is
  broken, with a test for each of the three strings above.
  - Receipt: `relay.text-test/defang-leaves-file-names-paths-and-times-alone`,
    under "a host written straight after a port's digit is broken too",
    has the three. They read `a[.]bc:1evil[.]com/steal?d=1`,
    `x[.]yz:9www[.]evil[.]com` and `me@a[.]bc:1evil[.]com/x`.
- [x] The issue says whether Apple's link finder or a terminal follows
  any of the three as they are shown today.
  - Receipt: the Log. Apple's finder followed all three as they were
    shown, and follows none as fixed. No terminal was run on them.

## Plan

relay#118 reworks `relay.text` and has a property that outside text
shows no link that can be followed. That property should come to
generate this case.

## Log

- 2026-11-15: Apple's link finder follows all three, as above. No
  terminal has been checked, so the second criterion stays open.
- 2026-11-15, the relay#118 session: fixed on relay#118's chain. The
  fix is the one made for the third round of relay#118's review, in
  sxskvylu: the pattern now only looks ahead at a port's first digit, so
  the host after it is read as a host. The three strings are cases in
  `relay.text-test/defang-leaves-file-names-paths-and-times-alone`,
  under "a host written straight after a port's digit is broken too",
  added in slposyyt. They now read `a[.]bc:1evil[.]com/steal?d=1`,
  `x[.]yz:9www[.]evil[.]com` and `me@a[.]bc:1evil[.]com/x`. Apple's link
  finder follows none of the three as fixed, alone or with a word either
  side (`scripts/inert-text-check.swift`). `scripts/inert-text-fuzz.clj`
  generates the shape (`a.bc:1`, `x.yz:9` and `me@a.bc:1` before a
  host). No terminal was run on the strings, before the fix or after.
- 2026-11-15 approved: 2026-11-15: Kira accepted it through the coordinating session's question dialog; every criterion is met. The fix's code was replaced by relay#130's rule, which breaks these dots anyway; the three cases are still tests in relay.text-test and pass.
