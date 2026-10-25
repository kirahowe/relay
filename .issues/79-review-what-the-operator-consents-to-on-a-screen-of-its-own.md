# Review what the operator consents to on a screen of its own, with text relay made

## Context

2026-10-24. Five Codex rounds on relay-tui's saved view and navigation
(relay#74, #77) kept finding ways the dashboard's layout could show the
operator less than they were approving: a tracker grid cutting a
contaminated row, option labels capped at two rows, over-indented input
clipped by its box, a review heading skipping defang, escaping that made
a real control and its spelled-out form look the same. Each fix was
right and the next round found another path, because the claim being
proved ("the operator saw all of this before pressing y") was being
proved from a renderer built for browsing.

Kira agreed to separate the two jobs: the dashboard browses and may be
lossy; consent gets its own full-screen review, and relay owns the text
consented to, so every client (relay-tui, the phone later, a web client)
only has to show a text verbatim. The contract is the "Consent" section
of ~/code/projects/.next/DASHBOARD.md.

## Acceptance criteria

- relay attaches review text and digest to permission requests, card
  wire forms and doc items, and refuses an allow (or trust) whose digest
  is not the one it sent, for clients that declare relay/review.
- relay-tui answers and confirms only from the review pager, whose lines
  are the review text exactly, after every page was drawn, with a live
  key past the arm delay.
- Plain ACP agents still work: relay-tui builds the review text itself.
- The inline armable/certification machinery is gone.
- A Codex loop over the result converges.

## Log

- Generic ACP clients that do not declare relay/review (Agmente) are
  handled as before: relay cannot know what they show. Known gap, to
  revisit with relay#66.
- relay side, 2026-10-24. The proxy builds a permission request's
  review, not the hub. The tool's class is only in the role's manifest,
  which the proxy holds, and the proxy already rewrites the request with
  its preview and reads it back, so the review is made from the line
  every client gets. The hub keeps that line and checks an allow
  against its H. The proxy replaces any relay/review the agent sent and
  drops any relay/refused.
- T is escaped by relay.text/visible-strict, new beside visible: the
  tab, every space but U+0020 and anything outside the general
  categories L, M, N, P and S become \u{XXXX} (Go's unicode.IsPrint set,
  which the pager checks). visible is unchanged, since the command line
  relies on it keeping tabs.
- The preview in a request's T is the preview command's own text with
  the preview's labels, not the markdown clients get. That markdown
  escapes hidden characters without doubling backslashes, so a real
  control and its spelled-out escape look the same in it. The notes the
  markdown adds (placeholders, new link hosts, lookalikes) are left out
  because the contract does not list them; worth adding if Kira wants.
- The input is pretty-printed by a new relay.json/pretty: members in name
  order, two spaces an indent, numbers as generate writes them.
- Trust digest rule: every trust names the sha256 of the subject's
  review text, H, for every kind (relay.core/trust-digest). A card's and
  a doc item's "sha256" are now H, so the relay-tui binary of today,
  which echoes the sha256 it was sent, keeps working, and a client that
  declares relay/review matches H with no separate check. `relay trust`
  names the same digest, computed from the state it showed. Kinds other
  than card and doc get a review text of their kind, ref and version and
  their text behind the gutter. The digest of the raw text no longer
  trusts anything.
- An allow refused for a missing or wrong digest is not answered with an
  error (an answer is a response). relay sends the request again to that
  connection under a new id with params._meta relay/refused. After 3 bad
  allows from one connection for one request it stops, warns on stderr,
  and keeps the connection in the request's list, so the request is not
  unattended and is never refused for it, even in embedded mode. The
  client can still reject under its last id.
- Once a connection declares relay/review, a later initialize cannot take
  it back.
- A request's own fields (title, toolCall content with relay's preview)
  are unchanged: generic clients still show them.
- Known limits: defang is not injective (a contaminated "x.com" and
  "x[.]com" give the same T), and inside contaminated JSON a quoted
  number such as "12.50" is defanged, since its word starts with a quote.
  A code point Java's Unicode tables assign and Go's do not would make the
  pager fail closed.
- 2026-10-24, after the main session's review: a review no longer uses
  defang. In contaminated text, relay.text/visible-defanged escapes the
  colon of every :// as \u{003a} and every dot inside a word (fullwidth
  and ideographic dots too) as its own \u{XXXX}, in the same pass as
  visible-strict. Since a backslash still shows as two, T can be read
  back exactly, so "x.com" and "x[.]com" give different T and the input
  says exactly what goes out. Plain numbers and amounts keep their dots,
  now also inside JSON quotes or brackets ("12.50", [1.5]); a dotted quad
  does not. defang itself is unchanged for its other uses.
- Codex round 1, finding 2 (card T not injective across fields): fixed
  by one structural rule for every review. No untrusted value stands
  bare in one of relay's own lines: it is a JSON string on a labelled
  line (`Column 2: "Feb" [contaminated]`) or a gutter block. A card's T
  is no longer its text form but labelled lines per element; the wire
  form's `text` is unchanged. Injectivity is now tested by reading T
  back: test-only parsers turn the T of random requests, held calls,
  cards of every kind and docs back into their fields, and the property
  is parse(T(subject)) = subject. That replaces the pairwise checks.
- Codex round 1, finding 1 (lookalikes dropped): every review ends with
  "Characters to check:", naming by field (preview, input [path], tool,
  option n, title, item n, column n, row n, note, path, content) every
  untrusted value with a word mixing scripts or any character outside
  ASCII, with the code point and Unicode name. It reuses relay's
  detector, now relay.text/lookalike-findings (lookalikes renders it as
  before). Like preview-markdown, it names every non-ASCII character, not
  only known confusables: relay has no confusables table, and naming an
  accented letter costs a line while missing a lookalike costs consent.
- Codex round 1, finding 3 (CLI trust signed a digest of text it never
  showed): every CLI consent that authorizes now shows a review text
  itself, verbatim (a new confirm-review! that fails closed if the text
  holds anything a review may not), then relay's own notes through
  visible-line. `relay trust` shows core/trust-text and signs that
  text's digest. `relay approve` and `relay deny` show the held call's
  review (render/hold-review: relay.review/request-lines headed "Held
  call", no options) and relay's notes (hold, why held, label sources
  with their tool and call as JSON strings, a settings change). The
  "where it would go" line is left out of approve/deny: the input and
  the characters to check say it, and the line quoted role text bare.
  A hold's approval binds to the hold's exact input, so it names no
  digest. `relay holds` (browsing) is unchanged. Left as they are, since
  they authorize nothing a role wrote or bind to a fixed version: fact
  and doc revert (restore a stored version), release, fresh,
  trust-level, finding decisions and connect. Declassify does show a
  role's memo before it reaches a public role; it is a candidate for a
  review of its own and is not converted here.
- relay declassify gets a review (main session's request): its
  confirmation is confirm-review! of relay.review/declassify-text, the
  memo as declassifying makes it (the exact text the public role gets):
  id, from and to roles as JSON strings, subject as a labelled JSON
  string, body as a gutter block, the memo's labels and the characters
  to check. The memo's text is escaped by visible-strict alone, not
  visible-defanged: core/declassified already defanged it when
  contaminated, and the review must show exactly what the role is given.
  :memo/declassify now requires :sha256, the digest of that text, refuses
  (:conflict) when the memo would no longer give it, and records it in
  memo/declassified. Tested by parse(T) = memo.
- Codex round 2 (findings 1 and 2, one cause: reviews made from a text
  form, not the subject's fields). Every trust review is now built from
  the subject's own fields, each under its own labels
  (core/trust-record, relay.review/record-lines): a doc's path under the
  labels of whoever chose it (so a role-made `www.example.com/x` path is
  escaped, in the characters section too) and its content under the
  version's; a fact's topic, text, valid-from, valid-to, retracted,
  supersedes and why, each on its own line or block, so topic and text
  cannot blur and a literal "Why it changed:" stays inside the text's
  gutter; a memo's from, to, subject and body; a note's and a routine's
  owner, name, text, schedule, instructions; a role's id, title and
  instructions; a journal entry's owner and text. Card reviews were
  already field by field. Tested by reading T back from real fact
  histories (add, correct, retract through core) and random docs and
  records.
- Codex round 2, finding 3 (the reader's class): a request or hold for
  agentloop's read_quarantined names the tool it would run and that
  tool's class (tools/class-of: the operator's config, else relay's own
  tool), as "Tool "read_quarantined", relay's quarantined reader, to run
  the tool "web_fetch"" then "The tool it runs has class "egress"". The
  class has its own line; a tool relay cannot classify reads "has no
  class relay knows, so relay cannot say what this call can do". The
  hold path now also knows relay's own tools' classes, where it said
  "one of relay's own". Checked the other subjects against the rule:
  holds, requests and cards come from their records' fields; a
  declassify review comes from core/declassified, which is the text the
  public role is given, so that is the effective field, not a lossy one.

2026-10-24, relay-tui side (relay-tui llrtpsrm, uzssyusp, lkswlyro).

- The refusal: a JSON-RPC response cannot itself be refused, so relay
  re-sends the same session/request_permission under a new id with
  `_meta["relay/refused"]` (agreed with the main session). relay-tui
  reopens the review on it when it answered that tool call with an
  allow carrying a digest and nothing else is queued ahead of it. The
  re-asked request is a new identity, so it is read again from the
  start. The fake hub re-asks at most three times, then cancels.
- What the pager shows: valid UTF-8 where every character is a space,
  a newline, or passes unicode.IsPrint and is not a blank glyph (the
  default-ignorables and the braille, Khitan and notehead blanks, as
  relay.text has them). That is a little stricter than IsPrint alone.
  A mismatch with relay's tables fails closed, which is safe.
- Width: ansi.FirstGraphemeCluster with GraphemeWidth, which is what
  ansi.StringWidth and the emulator tests measure. A line breaks after
  a space when it can (never one at the line's own start), otherwise
  between graphemes.
- "Every page drawn" is tracked as byte ranges of T, so it survives a
  resize that moves the page breaks. A frame counts only once its
  report arrives (a frame later, about 50 ms). A page flipped past
  faster than that does not count, and on the last page the foot names
  how many pages were not on screen long enough. The core checks
  ReviewTextOK and relay's digest itself as well, so a faulty renderer
  still cannot arm a bad T.
- The header is the subject's kind ("Allow this tool call?", "Trust
  this card?" and so on) followed by T's first line, cut to the width,
  as the contract allows. The foot's option row shows the agent's
  option names through VisibleLine, cut to the width, because the keys
  need labels. T holds them whole. If the main session reads that row
  as another rendering of the subject, it can go.
- Keys: space, pgdown and f page on; pgup and b page back; j, k and
  the arrows scroll by a row; g and G go to the top and the end; y
  allows once, trusts or archives; tab, shift+tab, left and right move
  the option highlight; enter picks it (it starts on a reject); n
  rejects or cancels; esc cancels (a request answers cancelled, as the
  contract says); q goes back to the dashboard without answering a
  request, and cancels the pane's questions; ctrl+c stops the turn.
  h and l do not move the highlight, so typed letters cannot change
  the choice. Up and down scroll, because this is a pager.
- The dashboard summary takes enter (open the review), h, the role
  keys, the page keys, ctrl+c and ctrl+d. Esc there does nothing:
  nothing on the summary answers.
- v is gone. Enter opens the review, which has everything v showed.
  The transcript's Request entry that v made went with it.
- The end question and ctrl+y (trust a paste) are not consent subjects
  under the contract and stay inline. With Drawn now coming only from
  the pager, they arm ArmDelay after their surface came up
  (App.Became), from a live key. Both act on nothing outside.
- The pager closes when its conversation leaves the screen (Blurred),
  so coming back to the role never lands on a review with the keys.
  Enter opens it again.
- A card or doc without a review member fails closed. t on such a card
  says to run `relay trust card N`, a doc shows its trustCommand, and
  an archive review says relay sent no review text. Card and doc trust
  send the review's sha256 as their sha256 param, as the contract
  says. Card.SHA256 is still read but no longer sent.
- The text built for a plain agent follows the rules relay adopted
  after Codex's findings. Every agent value is a quoted string on a
  labelled line (tool, title, kind, each fixed argument, each option
  and its kind) or a gutter block ("  │ ") for content, preview and
  the pretty-printed input. The only escapes are \\, \" (inside
  quotes), \u{XXXX} and \x{XX}. Contaminated text has the ":" of
  scheme:// as \u{003a} and dots inside words as \u{002e}, except in
  plain numbers, so it still reads back. "Characters to check:" names
  mixed-script words, lookalike letters (a small table of Cyrillic and
  Greek Latin lookalikes, fullwidth forms and mathematical
  alphanumerics), and non-ASCII characters in host- or address-like
  words, by field and code point. Go has no Unicode names, so only
  code points appear. A test-only parser proves parse(T(request)) ==
  request.
- Removed with it: the inline review (render/review.go), the Armable
  sets and per-frame InputDrawn reports, Permission.Seen/SeenAt and
  inputseen.go, the v input view, the Request transcript entry, the
  TooSmall/Viewport rule and RequestFits, TrustPrompt.Since and
  CardTrust.Since. The shell no longer renders a full frame on every
  update to learn what it drew. It renders only the pager, and only
  while a review has the keys.
- Not carried over: the dashboard's "[new domain]" mark on a request's
  preview is not in the built T. The pane still marks new domains.

2026-10-24, the main session's decisions on two of those: nothing the
agent wrote appears on the review but in T's body.
- The foot's options row shows each option by its number and ACP kind
  only ("▸ 2 reject_once (n)"), the protocol's own words; the names
  stay whole in T. The number is the option's place in the list the
  agent sent (PermOption.N), which is how a review text numbers it, so
  it matches T even though relay-tui leaves out kinds it never offers.
  The client-built T numbers options the same way. The highlight and
  enter work on these entries.
- The top row shows only the kind in relay-tui's words ("Allow this
  tool call?", "Trust this card?", ...), not T's first line.
- Test: render.TestNothingAgentWrittenOutsideTheBody, random marked
  agent values in relay's T and in a built one, at random sizes, read
  and unread; no row outside the body holds a mark.

2026-10-24, Codex round 1 over ulqspnmk..zoxosqrk: five in-scope defects
in four classes, each fixed at its source with a property that fails
without the fix.

1. Width (relay-tui, "Measure the review as Bubble Tea draws it"). The
   pager wrapped by grapheme width, but Bubble Tea measures by code
   point unless the terminal negotiates mode 2027, so a line with
   skin-toned emoji was cut in Bubble Tea's buffer while all of it was
   reported drawn. Now one width, render.Method (WcWidth), is used for
   everything the renderer lays out. The program is held to it:
   ui.KeepWidthMethod is a tea.WithFilter that drops the terminal's mode
   2027 report, in main and every test harness. The review also fails
   closed, naming code points only, on any grapheme whose code-point and
   grapheme widths differ, since a terminal may draw it either way. The
   dashboard's wrap cuts a grapheme that is wider than its line on its
   own (a ZWJ family is 6 cells by code point), so it never draws past
   its width. Property: render.TestTheBufferShowsWhatTheReviewCertifies
   draws the review into an ultraviolet buffer under both width methods,
   and every body row is the line the frame certifies. It fails with the
   old grapheme wrap.
2. Content (relay-tui, "Build a plain agent's review from the call's own
   fields"). callContent kept only text, diff and terminal content, and
   joined a diff's fields before escaping, so an embedded resource
   disappeared and two different diffs could read the same. Now
   wire.CallContent reads the content from the request's params as
   received, not from the SDK's decoding, so fields the SDK drops are
   still seen. It returns each piece with its fields apart: a text; a
   diff's path, old text (none for a new file) and new text; a
   terminal's id; or an embedded resource's URI, mime type and text, or
   a blob's size and sha256. Anything else, a variant (image, audio,
   resource_link, an unknown type) or an unknown field (annotations
   included), goes in Permission.Unshown, and the review fails closed
   for allow, naming it; a reject still works. _meta is not shown, as
   ACP's extension slot. relay's preview text block is still read
   separately. parse(T) = request now covers every variant, and a test
   checks that the two diffs Codex gave read differently. Judgment
   call: image, audio and resource_link fail closed rather than being
   shown, which is the simplest rule that never shows less than was
   sent.
3. Invisible selection (relay-tui, "Send the highlighted option only
   once it was on screen"). The options row was cut from its end, so at
   width 20 with allow/reject/allow, tab could put the highlight on the
   third option with nothing visible changing, and enter sent it. Now
   the row is a window that always holds the highlighted entry, with ‹
   and › where more lie. If even that entry cannot fit, the row says to
   widen the window and does not count as drawing it. Frames report the
   highlighted entry they drew whole (ReviewDrawn.Pick). Enter on an
   allow (needsReviewAndPick) is taken only once a frame has drawn that
   entry, ArmDelay after the highlight last moved: PermissionMove resets
   it. Enter on a reject is still taken at once, and y and n name their
   own options, so they need only the review read. Properties:
   core.TestEnterNeedsTheHighlightOnScreen (fails without the check)
   and render.TestTheHighlightedOptionIsAlwaysInView.
4. A forged refusal (relay-tui, "Show a refusal only on relay's own
   re-ask"). Any agent's _meta["relay/refused"] was shown above T,
   through VisibleLine, which leaves links live, even when no allow had
   been refused. Now the core keeps it only on a hub conversation
   (State.Shared, which relay's hub at initialize gives) and only on a
   request for the call this client just allowed with relay's digest,
   arriving with nothing queued ahead. Everywhere else it is cleared on
   arrival. When shown it is relay's text as a quoted value with the
   same escapes and broken links as a contaminated value in T. Property:
   core.TestOnlyRelaysReAskShowsARefusal (hub or plain agent, allowed
   first or not, same call or not, links and controls in the reason). It
   fails without the check.
- Codex round 4 (a held call's numbers): the log reads decimals back as
  doubles, so a hold's review built from the reloaded hold rounded
  12345678901234567.0. hold/opened now also records :input-json (and
  :effective-input-json with fixed arguments), the input as the strict
  reader read it from agentloop's outcome, written back by
  relay.json/generate; the review reads that text with the strict reader
  (render/exact-input), so decimals and big integers are exact. A hold
  recorded before this has no text: its review uses the log's input and
  relay's notes say a decimal may be shown rounded. The digest approval
  forwards is agentloop's input_sha256, which agentloop computes over its
  own canonical encoding (Go's encoder over the literal number text) and
  checks again when it runs the call; relay keeps the value agentloop sent
  with it but does not recompute that digest, since doing so would mean
  reproducing Go's encoder and the literal text the strict reader
  normalizes. The general fix to the log's number reading is relay#82: it
  needs an audit of every comparison between logged and computed numbers.

2026-10-25, main session: the relay side converged. Codex rounds 1-4
over rvlyzwyo..lystvulw found 3, 3, 1 and 1 in-scope defects, all fixed
structurally (reviews built from subjects' own fields, quoted values or
gutter blocks with a read-back parser, characters section, every CLI
consent shows T, held-call reviews from agentloop's exact input).
Codex's quota then ran out until 2026-10-30, so round 5 was an
independent Claude reviewer (no session context) over
rvlyzwyo..mqxrywnq: no in-scope defect. Noted, not defects: `relay holds`
lists inputs from the log's doubles (not a consent surface; relay#82),
and -0 shows as 0.
