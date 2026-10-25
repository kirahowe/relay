# Tell every connection a request was settled after a detach and a race

## Context

`relay.hub-test/requests-stay-bounded-however-clients-come-and-go` is a
property that fails on some seeds. It was seen first on 2026-10-23 and
reproduced at baseline `rvlyzwyo`, before relay#79. On 2026-10-24,
seed 1792894682111 shrank to:

    [[:open true 0] [:open true 0] [:new 3 0] [:perm 0] [:load 0 0]
     [:detach 0 0] [:race 3]]

reporting `[:not-told-settled 2 c5000]`: after a detach and a race, a
connection is not told that a permission request was settled.

Rerun with
`(relay.hub-test/requests-stay-bounded-however-clients-come-and-go 165 :seed 1792894682111)`.

## Acceptance criteria

- The hub tells every connection that was sent a request when it is
  settled, whatever order detach, load and the race come in, or the
  property is shown wrong and corrected.
- The shrunk case becomes a fixed regression test.

## Log
