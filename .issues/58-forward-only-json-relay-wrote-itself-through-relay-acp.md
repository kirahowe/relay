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
- [x] Codex loop converges.

## Log

- 2026-10-20: re-encoding plan stopped before any code; replaced by this.
- 2026-10-20: relay's half landed: relay.json, the screen replaced, the
  shared table and property tests, README and spec. bb test passes (754
  tests); bb pentest passes the proxy and envelope scenarios (s5, consent-acp,
  s6-acp-tui, s7-kill-conversation, s9, s16; 20 of 20); bb tck is
  CONFORMANT (39 passed, 17 skipped as not advertised). The Codex loop is
  still to run.
- 2026-10-20: the Codex loop converged in two rounds (below). bb test
  passes (756 tests); agentloop's make test passes.
- 2026-10-20: QA findings fixed (see "QA round" below). bb test passes
  (760 tests); agentloop's make test and acp-tui's make check pass; bb
  pentest passes the proxy and envelope scenarios (20 of 20); bb tck is
  CONFORMANT (39 passed, 17 skipped). A second Codex loop on the fixes
  converged in three rounds.

## Codex loop

Before round 1, relay's copy of the shared table was one line behind
agentloop's and acp-tui's (the case "capitalised name with params a lax
reader cannot type"). Copied; relay reads it as the table says.

Round 1, five findings:

1. Fixed in agentloop. The fold check refused a client's
   `"_meta":{"Title":"hello"}`, valid ACP that relay and acp-tui accept,
   because `Title` folds to an SDK field. The SDK keeps `_meta` as raw
   JSON and decodes `data` into `any` or a string, so v1 folds no names
   inside either; the screen now skips those values. A test checks the
   field types against the SDK.
2. Fixed in relay. With `:keywords?`, a name like `"\ud800/x"` puts the
   lone surrogate in the keyword's namespace, and the check looked only
   at the name part, so `parse-outcome` accepted what both Go readers
   refuse. The check now covers the whole keyword.
3. In scope, not a defect of this change; to file against acp-tui.
   acp-tui shows a tool's rawInput through the SDK's v1 decode into
   `any`, so an integer past 2^53 shows rounded on a permission prompt
   (`9007199254740993` as `9007199254740992`), and `1e400` makes the SDK
   drop the update. The SDK did this before relay#58, and acp-tui never
   passes rawInput on, so the dialect's rule (exact where passed on)
   holds. It still means Kira can approve a call whose shown argument
   differs from the real one.
4. In scope, needs Kira: relay refuses an exponent past an int
   (`1e-2147483648`, which BigDecimal cannot hold), while agentloop and
   acp-tui accept it, since the dialect sets no bound. relay sits in the
   middle, so such a line reaches no one and the failure is closed. To
   remove the difference, either the dialect gains an exponent bound that
   the Go readers enforce too, or relay keeps such numbers some other
   exact way. That is a change to the dialect, so it is Kira's call.
5. Rejected. acp-tui drops a line with an unknown folded member of the
   wrong type (`"Method":1`) that relay passes. The failure is closed at
   the endpoint, and only a hand-crafted line can put such a name in a
   typed position: agentloop and relay write with encoders, and model
   text lands only in free-form values. Out of scope under "an attacker
   who studies relay's code to target Kira".

Round 2: no findings. Codex checked both fixes and the four copies of the
table.

## QA round

Four findings, all fixed.

1. Blocking, agentloop. agentloop refused four table lines that relay
   and acp-tui accept: the case variants (`Method`, `METHOD`, `paramſ`,
   and `Method` beside `method`). agentloop's table tests skipped
   exactly those cases. The fold check now removes such a member rather
   than refusing the line, as acp-tui's ForV1 does. To the dialect it is
   an unknown member, so after the removal the SDK reads what relay and
   acp-tui read. A line with nothing to remove still passes byte for
   byte. The table tests check every accepted case through the screen,
   reading the method with v1 the way the SDK does. This keeps the
   dialect as it was; the other fix the finding offered (refusing folded
   names everywhere) would have changed it. New table case: "capitalised
   names nested in params", copied to all four places.
2. Blocking, relay. The agent could forge relay's answer to
   `_relay/cards`: the screen passed every agent response, whatever its
   id. relay acp now counts the client requests it passed to the agent
   and passes an agent response only when it settles one of them
   (`relay.workspace/owe`, `settle`). It predates this issue, but it
   broke the README's claim, so it is fixed here rather than filed.
   Judgment calls:
   - Ids match as the ACP SDK matches them: numbers by value (`3`, `3.0`,
     `30E-1`), strings by text. A response is any message with an id and
     no non-empty method, which is how the SDK reads one.
   - A count, not a set. The SDK answers a reused id with an error and
     then answers the original too; with a set the second answer would
     be dropped and the turn would hang.
   - A response with a null id is dropped like any other unowed one.
     The SDK sends those only for frames it cannot read.
   - Codex, rounds 1 and 2: a request for one of relay's methods clears
     whatever the agent still owes under its id, before relay works on
     the answer. Otherwise a client that gave up on a request and reused
     its id for `_relay/cards` could get the agent's late answer. A line
     the strict reader refuses clears nothing: it may be a malformed
     answer to the agent, and the agent's ids can equal the client's.
3. Minor, acp-tui (relay#52). The permission prompt showed rawInput
   through the SDK's v1 decode, rounding numbers. acp-tui now reads
   rawInput from the request's own params, which the SDK puts in the
   handler's context (`acp.InboundParamsFromContext`), with the strict
   reader, numbers as written. A first version kept inputs from the
   screen by session and tool call; Codex found it missed an escaped
   method and let a request the SDK refused lend its input to a later
   one, and reading the handler's own params rules out both. This
   meets relay#52's criteria; relay#52 is still open in the tracker for
   Kira to move.
4. Minor, relay. An envelope tool's schema could not take `"number"`:
   the check knew no such type. It now accepts any number, BigDecimal
   included.

Also fixed: three property-test generators that failed on rare seeds
(relay.json: `1` then `0` concatenate to the single number `10`, and a
BigDecimal with no fraction digits is written as an integer or with an
exponent; relay.vet: a random key can be a JSON Schema keyword such as
`if`). In each case the test was wrong and the code was right.

acp-tui's TestEnterWhileBusyQueuesOnePrompt/40x10 failed once under the
full `make check` load and passed alone and on reruns. It is a UI timing
test unrelated to these changes; left alone.

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
  hold them. Fails closed. Superseded by the exponent bound below, which
  refuses them in all three readers.
- Figures relay only sums never go through BigDecimal arithmetic that
  could throw: costs go through `double` at the edges
  (`core/wake-cost`, `acp/usage-update`, agentloop `cost-usd`) as
  before. The reader's `:doubles?` option exists for such reads but no
  site needs it yet.

- relay acp reads each line once per direction. See "One read per line"
  below; a per-thread cache did this at first. The surrogate checks also
  got cheaper: the UTF-8 encoder's canEncode finds a raw lone surrogate,
  and indexOf rules out a line with no `\u` escape before any regex
  runs.
- A re-encoded line (with-preview, with-inert-displays) loses the sign of
  a negative zero: `-0` and `-0.0` come out as `0` and `0.0`, because
  data.json reads them as Long 0 and BigDecimal 0.0. Only lines carrying
  a preview or a display are re-encoded. Left alone: it is a
  low-bandwidth difference, which the threat model puts out of scope.

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

## Exponent bound

Codex finding 4 asked for a decision. The orchestrator decided: a
number's exponent (the integer after `e` or `E`, sign included) must be
between -9999 and 9999 inclusive, by value, so leading zeros are allowed
as JSON allows them. The shared README says so, and the table has eight
new cases: `1e9999`, `1E-9999` and `1e+0009999` pass; `1e10000`,
`1e-10000`, `1e-2147483648` and a 30-digit exponent are refused; and
`"1e10000"` inside a string passes. All three repos have the table
byte for byte and enforce the bound in their strict readers, each with
a property test that sweeps exponents to three times the bound with up
to 30 leading zeros.

Judgment calls:

- The Go readers check the exponent of each number token they already
  read for the length bound. The check strips the sign and leading
  zeros and refuses more than four digits left, so no exponent is ever
  converted to an int that could overflow.
- relay checks it after Jackson has passed the text and before
  data.json reads it, on the same fast path as the length check: only a
  text with an exponent of five or more significant digits somewhere is
  walked token by token, to skip ones inside strings. Once Jackson has
  passed a text, every run of number characters outside a string is a
  number literal or the `e` that ends `true` or `false`, which has no
  digits and passes.
- agentloop's tighter bound for model input (`Input`, 100-character
  literals) uses the same scan, so it gets the exponent bound too. Model
  output that breaks it would have been refused by relay downstream
  anyway.
- relay, agentloop and acp-tui docs (README, spec, DESIGN.md) list the
  bound with the others.

Checks: relay `bb test` passes (761 tests); agentloop `make test && make
build` and acp-tui `make check && make build` pass.

## One read per line

relay acp asks many things of each line: the screen, the permission and
display checks, the observer, the trace, extension-request,
client-request-key, agent-response. The first version kept the last
string each thread had read in a ThreadLocal inside `relay.acp/message`,
so asking about the same string object again cost nothing. It worked,
but the read-once guarantee depended on every caller passing the same
object on the same thread, and nothing in the code showed it.

Now `pump!`, the shell, reads each complete line once with
`relay.acp/line`, which returns `{:text :message}`: the line's text and
what the strict reader made of it (the object, or nil when it refused
the line). The screen and the tap both get that value, and so does every
decision about the line. The ThreadLocal is gone.

Judgment calls:

- Every function that takes a line also takes plain text and reads it
  itself, since `line` passes a value it already read straight through.
  The tests call these functions with strings, and I left them that way
  so they stay the spec unchanged. Functions that only search the text
  (`line-id`, the `relay/display` and `request_permission` substring
  checks) take the text from the value and never parse.
- The `:extension` hook of `proxy!` now gets the read line, not the
  string, so the handler can pass it to `workspace/extension-request`
  without a second read. One test's stand-in hook did `str/includes?` on
  its argument and now looks at `(:text line)`. That changes the
  fixture, not the behaviour under test.
- A line relay rewrites (`with-preview`) is read again so the request is
  recorded as the client sees it. That line is relay's own output, not
  a second read of the agent's line.
- A function given bytes used to return nil from some checks (they
  tested `string?`) and read them in others. They all read bytes now. No
  caller passes bytes; `pump!` decodes them in `line`.

Measured with a 2.25 MB session/update (a tool_call_update with about
1.9 MB of text holding escapes, tabs and non-ASCII, plus a 2000-element
rawOutput array), sent ten times by an agent through `proxy!` with
approvals on. The figure is wall time per line until the client has it.
The bench script counts reads by wrapping `relay.json/check!`. It lives
in the session scratchpad, not the repo.

| | ms per line | strict reads per line |
|---|---|---|
| cheshire parse alone (the old reader) | 6.7 | n/a |
| strict reader alone | 77 | 1 |
| proxy, every check reading for itself (cache disabled) | 475 | 6 |
| proxy, before this change (per-thread cache) | 89 | 1 |
| proxy, after (one read passed down) | 89 | 1 |

The read itself is the cost: 77 of the 89 ms is the strict reader, and
the rest is decoding and copying. The change keeps the single read the
cache already gave and makes it structural, so a new check cannot bring
back the 475 ms path by passing a fresh string. The existing test
relay-acp-reads-each-line-once-in-each-direction checks one read per
line in each direction. bb test passes (761 tests).
