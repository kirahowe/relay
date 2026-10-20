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

## relay's half: judgment calls

- The reader is `relay.json`. babashka exposes no Jackson classes, and
  its cheshire has no switch for BigDecimal. So Jackson, through cheshire
  with a strict factory, only checks the text (duplicate names, raw
  control characters, depth 128, end of input). clojure.data.json, also
  compiled into bb, then reads the value with `:bigdec true`. Both read
  only RFC 8259 JSON, so once Jackson has passed a text they agree on it.
  `relay.json` makes the two checks neither reader makes: unpaired
  surrogates, and the length of a whole number literal (Jackson counts
  digits, so `-` plus 1000 digits got past it). Each has a regex fast
  path, so a line that cannot break the rule is never walked.
- Strict UTF-8 works on bytes. `relay.json/text` decodes every invalid
  byte (stray, overlong, encoded surrogate) to a lone surrogate rather
  than U+FFFD, so the line can still be searched for an id but never
  parses. The BOM stays U+FEFF, which Jackson refuses. relay acp lines,
  tool envelopes (`relay tool` stdin, envelope tool and preview stdout,
  verifier output) and the staging stand-in tools are read as bytes.
- An ACP line must be one JSON object (`relay.acp/message`), as the
  shared table says. A batch or a bare value is dropped, as before.
- A blank agent line is now dropped, without the stderr note. It carries
  nothing.
- A valid agent line that only mentions `request_permission` in its text
  is no longer taken for an unreadable permission request. The rule now
  covers a line the strict reader refuses that mentions it, and a valid
  message whose method is `session/request_permission` that has no id.
- A client line the strict reader refuses is answered with an error when
  relay can find its id, as before for refused lines. When that id is an
  open permission request's, relay refuses the request to the agent
  instead (`client-error`), so a malformed answer cannot leave the turn
  hanging. Test: an-answer-the-strict-reader-refuses-is-a-refusal (this
  line, the id written twice, used to pass to the agent).
- Lines that spell a member in another case (`Method`, `paramſ`) now
  pass both ways: every component matches names exactly, so they are
  unknown members to all of them. The tests that expected them dropped
  now test duplicates and trailing values instead.
- The id regex used for lines relay cannot read (`relay.acp/line-id`) is
  unrolled and possessive. The old alternation could overflow the regex
  stack on a long string.
- `parse-envelope` keywordizes the input by printing it and reading it
  back with the strict reader, so decimals in a tool's input stay exact.
- Exponents past an int (`1e99999999999`) are refused: BigDecimal cannot
  hold them. Fails closed.
- Figures relay only sums never go through BigDecimal arithmetic that
  could throw: costs go through `double` at the edges
  (`core/wake-cost`, `acp/usage-update`, agentloop `cost-usd`) as
  before. The reader's `:doubles?` option exists for such reads but no
  site needs it yet.

### Reads left on cheshire, deliberately

- `relay.log`: the event log is relay's own, written by relay; it is not
  another process's JSON.
- Test helpers, fixtures, the pentest harness and its stand-ins: they
  stand in for clients, tools and servers, not relay.
- Process output relay gets as a String from babashka.process
  (agentloop's outcome line and session files, `agentloop mcp-tools`, a
  script harness's report.json, HTTP bodies) now goes through
  `relay.json`, but as text already decoded with U+FFFD for bad bytes.
  Go's encoder cannot emit invalid UTF-8, and none of these is passed
  on, so reading them as bytes was not worth the plumbing.
