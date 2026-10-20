# Read JSON with one strict dialect across relay, agentloop and acp-tui

## Context

The second Codex loop on cards (#54) did not converge. Every round found
another malformed line that relay's JSON reader (Jackson, via cheshire)
and acp-tui's (Go's encoding/json v1) read differently: a duplicate
`method` key (Jackson keeps the last, Go v1 the first), a key Go v1 folds
to `method` when decoding into a struct (`Method`, `ſ`, `ı`), a second
JSON value after the first (`{...} null`). Where readers disagree,
relay's screen thinks a line is harmless while the client sees a
`_relay/` message. The hand-written screen in relay.workspace tried to
enumerate the disagreements.

No real traffic is malformed: agentloop writes every message with Go's
encoder, which cannot emit any of these. The bug class is that the
components read JSON by different rules.

Kira, 2026-10-20: standardize the JSON parsing and eliminate the class
at the source. A first plan (relay re-encodes every line) was dropped
before any code: it would not fix case folding, since relay would
faithfully re-encode `"Method"` and Go v1 would still fold it.

## The dialect

Every place where relay, agentloop and acp-tui read JSON that another
process wrote reads it by one rule, and rejects what breaks it:

- RFC 8259 JSON, valid UTF-8 only.
- Exactly one value per message (per line on an ACP stream); only
  whitespace after it.
- No duplicate member names, at any depth.
- Member names are matched exactly, case-sensitively. Unknown members
  are ignored as now.
- Numbers are kept exact where they are kept at all (no rounding through
  a double where the value is passed on).

Go: `encoding/json/v2` (standard library in Go 1.27, no experiment flag;
checked 2026-10-20) does all of this by default. Clojure: one shared
strict reader in relay (Jackson with STRICT_DUPLICATE_DETECTION, a check
that the input ends after the value, strict UTF-8, BigDecimal and
BigInteger), used by every boundary read.

With every reader agreeing, relay acp stays a byte pass-through: a line
either means the same thing to every component or is not passed on. The
agent-line screen becomes: parse strictly; drop the line if it fails;
drop an agent message whose `method` starts with `_relay/`. The
Go-folded key checks, the duplicate-key walk, the value counting and the
other hand-written string checks go, with tests that only exercised
them. Behaviour tests stay.

## Scope

The boundaries between the three components:

- relay acp, both directions (relay.acp, relay.workspace, relay.dispatch).
- acp-tui's wire package (everything it reads from the agent).
- agentloop's ACP server (what it reads from the client), and the tool
  envelope both ways (relay tool output read by agentloop, agentloop's
  envelope read by relay tool), plus any other JSON one of them reads from
  another. agentloop decodes ACP through caelis-labs/acp-go-sdk, which
  uses v1; screen each incoming line strictly before the SDK sees it.
- Other relay reads of outside JSON (external tool output, MCP results,
  config) move to the shared reader where it is a plain swap; list the
  rest in this issue.

## Acceptance criteria

- [ ] One strict reader per language, used at every boundary in scope.
- [ ] Duplicate names, case-variant envelope names, trailing values and
      invalid UTF-8 are rejected identically by relay, agentloop and
      acp-tui; one shared table of hostile lines is tested against all
      three (fixtures in each repo, same lines).
- [ ] The hand-written screen checks are gone.
- [ ] relay acp still passes bytes unchanged for valid lines; existing
      acp, tck (if it runs here) and proxy pentest scenarios pass.
- [ ] Numbers and strings survive exactly; property tests.
- [ ] README, spec/relay.allium, agentloop and acp-tui docs say so.
- [ ] Codex loop converges.

## Log

- 2026-10-20: re-encoding plan stopped before any code; replaced by this.
