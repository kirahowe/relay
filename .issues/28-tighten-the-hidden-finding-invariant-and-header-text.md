# Tighten the hidden-finding invariant and header text

## Context

From the review of relay#27 (oomymlxw, 2026-10-17). Neither breaks a guarantee.

- The relay#27 invariant in core_test is checked by URL, not by what each hidden finding matched. It passes when any shown finding that isn't fake has the same canonical URL, so it would not catch a finding hidden behind the wrong one. Check that `(:seen-as f)` resolves to the right finding directly.
- `render/seen-as-text`, the review header's text for a hidden finding, still says "waiting for review" when its match has since been decided. This predates relay#27, but relay#27 makes matches to decided findings more common.

## Acceptance criteria

- [x] The invariant follows `:seen-as` to the finding it names.
- [x] The header text says how the match was decided.

## Plan

## Log

2026-10-17: Both done.

Invariant. The check in the model-based test now resolves each hidden
finding's `:seen-as` to the finding it names (a ledger entry through
`:ledger` or `:withdrawn` to the finding that made it) and requires
that finding to exist, be a different one, be shown, not be decided
fake and have the same canonical URL. It is resolved in the test
itself rather than through core's `matched-decision`, so the check does
not share the code it checks.

With the relay#27 fix switched off (`as-it-stands` returning the report
unchanged), the random property test did not fail in 15,000 trials
over five seeds: the generator almost never builds the five steps
needed (report on page a, verify, report on page a, verify, review the
first as fake). The old URL invariant had the same exposure. So a new
deterministic test, `the-hidden-finding-invariant-follows-seen-as`,
runs those steps through `check-step` and asserts that the invariant
holds behind a waiting or accepted match; that it fires when `:seen-as`
points at another finding although a shown one has the URL (this case
passes the old URL check, confirmed by putting it back); and that it
fires with the relay#27 view switched off (`core/report` redefined to
the raw report). The fix was restored after each experiment.

Header text. `as-it-stands` now adds the current decision to the
`:seen-as` of a finding hidden behind a "pending" match that has since
been decided, and `render/seen-as-text` says "which has since been
accepted" or "which you have since judged not relevant" instead of
"waiting for review". Only the "pending" kind gets it: the other kinds
already name a decided state. The key is not part of the finding
digest, so decisions are unaffected. Test: the relay#27 regression test
in reports_test checks the header for both decisions. README updated.

