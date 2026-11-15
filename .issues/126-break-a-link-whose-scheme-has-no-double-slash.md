# Break a link whose scheme has no double slash

## Context

Sent by the relay#95 session on 2026-11-15, from the confirm round on
relay#95. A fresh read-only agent stood in for Codex, whose quota is out
until 19 November.

The reviewer's text:

> Other, pre-existing, outside G2's wording. `ssh:CANARY@evil.test`,
> `mailto:CANARY@evil.test` and `file:/tmp/x.command` are drawn whole in
> a contaminated reply, a role's title, a notice, a ready item and its
> from line (ran it). `urlRE` (defang.go:24) and `linkable` know only
> `scheme://` and `www.`. Ghostty's finder takes `mailto:`, `file:`,
> `ssh:`, `tel:`, `magnet:` and `news:` with no slashes (its
> src/config/url.zig, fetched today). By reading, relay's own defang
> also leaves `ssh:user@host` (text.clj:123-148). Untargeted, but needs
> a click; I did not test what macOS does with one, and mailto only
> opens a draft. File it; do not block on it.

The relay#95 session's notes. It found the same family from relay#118's
shapes: relay-tui's `render.Defang` leaves `https:/x.pl`,
`http:/evil.test/x` and `https:evil.test` whole. The relay#118 session
fixed the one-slash form in relay, and Apple's finder follows it. There
are two halves. relay-tui's Defang decides what conversation text shows,
and the relay#95 session will fix it with relay#95's second part unless
someone takes it first. `relay.text/defang` decides what a card or a
message shows, since a ready item's text has been through it, and that
is the relay#118 session's ground.

Checked by the relay#93 session when filing, on `integration` at
oztslqlv (1cdd42dd), which does not have relay#118 yet. What
`relay.text/defang` makes of each, and what Apple's NSDataDetector then
follows in the result:

| written | relay shows | Apple's finder follows |
|---|---|---|
| `ssh:CANARY@evil.test` | as written | `mailto:CANARY@evil.test` |
| `mailto:CANARY@evil.test` | as written | the same |
| `tel:+15551234567` | as written | the same |
| `sms:+15551234567` | as written | the same |
| `magnet:?xt=urn:btih:CANARY` | as written | nothing |
| `file:/tmp/x.command` | `file:/tmp/x[.]command` | nothing |
| `news:alt.CANARY` | `news:alt[.]CANARY` | `news:alt%5B.%5DCANARY` |
| `https:/x.pl` | as written | not run here |
| `http:/evil.test/x` | `http:/evil[.]test/x` | not run here |
| `https:evil.test` | `https:evil[.]test` | not run here |

So relay leaves `ssh:`, `mailto:`, `tel:`, `sms:` and `magnet:` whole.
Apple's finder makes a mail draft of `ssh:user@host`, so on the phone
and in mail it carries nothing out by itself. In a terminal whose finder
takes `ssh:`, the name before the `@` goes to the host when the
connection opens, and nobody has run what macOS does on that click.

Whether `mailto:`, `tel:` and `sms:` are broken is Kira's question in
relay#121. This issue is the rest: `ssh:`, `file:`, `magnet:`, `news:`
and their kind, which relay shows nowhere on purpose, and the web
schemes written short in relay-tui. Putting the first group here and
not in relay#121 is my judgment.

## Acceptance criteria

- [ ] relay-tui's `render.Defang` breaks a web scheme written with one
  slash or none (`https:/x.pl`, `http:/evil.test/x`, `https:evil.test`),
  with a test for each.
- [x] In outside text, `relay.text/defang` breaks an address with a
  scheme and no `//` that is not `mailto:`, `tel:` or `sms:`: at least
  `ssh:`, `file:`, `magnet:` and `news:`, with a test for each.
  - Receipt: `relay.text-test/defang-leaves-file-names-paths-and-times-alone`,
    under "a scheme a terminal follows with no slashes after it is
    broken"; slposyyt on relay#118's chain. See the Log.
- [ ] relay-tui's Defang does the same, with a test for each. (One
  criterion as filed; the relay#118 session split it on 2026-11-15 so
  that relay's half could be ticked.)
- [ ] `mailto:`, `tel:` and `sms:` follow Kira's answer in relay#121, in
  both repos.
- [ ] The Log says what macOS does when `ssh:user@host` is clicked in a
  terminal that follows it.
- [ ] `bb verify` and `make check` pass, and an adversarial review finds
  nothing in scope.

## Plan

Related: relay#95, relay#104, relay#118 and relay#121.

## Log

- 2026-11-15, the relay#118 session: relay's half is fixed on relay#118's
  chain, in slposyyt. relay-tui's half, what macOS does on a click, and
  relay#121's three schemes are still open, so this stays out of review.
  - `relay.text/defang` writes `ssh:`, `sftp:`, `file:`, `magnet:`,
    `news:` and `git:` as `ssh[:]` and so on, where the scheme does not
    run on from a letter, a digit or one of `+ . -`, and something other
    than a space follows the colon. A word that holds `[:]` then has
    every dot broken. `sftp` and `git` are my
    addition, as the same kind. Any other scheme with no `//` is left
    as it is.
  - What it shows now: `ssh[:]CANARY@evil[.]test`,
    `file[:]/tmp/x[.]command`, `magnet[:]?xt=urn:btih:CANARY`,
    `news[:]alt[.]CANARY`. The web schemes written short were fixed
    earlier on the chain (wxmtmmwq): `hxxps:/x[.]pl`,
    `hxxp:/evil[.]test/x`, `hxxps:evil[.]test`. Apple's link finder
    follows none of the seven (`scripts/inert-text-check.swift`).
  - `mailto:`, `tel:` and `sms:` are shown as written, as before. They
    wait for relay#121.
  - Tests: `relay.text-test/defang-leaves-file-names-paths-and-times-alone`,
    under "a scheme a terminal follows with no slashes after it is
    broken", has `ssh:`, `magnet:`, `file:`, `news:` and an upper-case
    `SSH:`, and holds `Re: the news: nothing`, `key:value`, `at 10:30`,
    `ssh: later`, `mailto:a@b.test` and `tel:+15551234` to staying as
    written. `scripts/inert-text-fuzz.clj` generates the schemes.
  - relay-tui's Defang was told of the new broken forms (the relay#95
    session), and draws them as relay sends them.
