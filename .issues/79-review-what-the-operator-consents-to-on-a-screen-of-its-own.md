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
