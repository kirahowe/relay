# Tighten the hidden-finding invariant and header text

## Context

From the review of relay#27 (oomymlxw, 2026-10-17). Neither breaks a guarantee.

- The relay#27 invariant in core_test is checked by URL, not by what each hidden finding matched. It passes when any shown finding that isn't fake has the same canonical URL, so it would not catch a finding hidden behind the wrong one. Check that `(:seen-as f)` resolves to the right finding directly.
- `render/seen-as-text`, the review header's text for a hidden finding, still says "waiting for review" when its match has since been decided. This predates relay#27, but relay#27 makes matches to decided findings more common.

## Acceptance criteria

- [ ] The invariant follows `:seen-as` to the finding it names.
- [ ] The header text says how the match was decided.

## Plan

## Log
