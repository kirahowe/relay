# Make hidden characters visible in card text that roles and resolve get

## Context

Filed from the second Codex review of cards (#54), on 2026-10-20. Both
findings are in scope, but neither breaks a guarantee this change made:
relay gives roles and `_relay/resolve` raw text with its labels for every
kind (memos, docs, facts, notes), and the brief asked for escaping only in
the wire form and the CLI. The question is whether relay should escape
hidden characters and defang links on its side for every kind, not just
for cards.

Codex, round 1:

> Card refs bypass wire sanitization. [refs.clj:131] returns raw card
> text through `_relay/resolve`. Create a contaminated item containing
> `zero<U+200B>width https://evil.example/x`, then resolve `[[card:1]]`:
> the response keeps the invisible character and the live URL, with no
> outsider marker. This breaks the guarantee that clients receive
> already-safe card text. Trigger: ordinary resolution of a card
> containing copied hostile web content.

acp-tui escapes and defangs resolved text by its labels today, so the
pane is safe. Another client would have to know to do the same.

Codex, round 2:

> Role reads and prompts receive unescaped, undefanged card text.
> [tools.clj:581] uses the raw text form; [prompt.clj:152] interpolates
> raw titles. Create a contaminated card titled
> `Pay<U+202E> https://example.org/x`. Both an accepted contaminated read
> and a prompt whose labels include contamination receive the hidden
> character and the live URL unchanged. Labels are preserved, but the
> stated escaping/defanging guarantee fails. Untargeted attack: copying
> concealed instructions from an ordinary malicious webpage into a card.

Role-facing text is guarded by labels and withholding: a contaminated
card is withheld unless the role accepts contamination, and the prompt
lists a card only when its labels are already within the session's.

## Acceptance criteria

- [ ] Decide whether `_relay/resolve` returns text made safe by relay
      (visible, defanged when contaminated, an outsider flag) for every
      kind, as the card wire form does.
- [ ] Decide whether role reads should show hidden characters as
      `\u{XXXX}` for every kind.

## Log
