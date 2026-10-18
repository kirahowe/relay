# Give the quarantined reader a read-only fetch that takes no free URLs

## Context

The quarantined reader (relay#21 and the reader halves in agentloop and
relay) reads only through read-class tools. relay's live web fetch tools
are classed egress, because a URL the model writes can carry data out. So
the reader cannot read live web pages yet.

Kira decided on 2026-10-17: add a read-classed fetch that takes no free
URL. It fetches only URLs from an allowlist or from earlier search
results, so the model cannot put data into a URL. The other option,
letting the reader take an egress inner tool under its hold rules, was
turned down.

## Acceptance criteria

- [ ] A fetch tool the policy classes as read, whose URL argument can only
      name an allowlisted URL or a URL returned by an earlier search in the
      same session.
- [ ] A URL that is neither is refused before any network request.
- [ ] The reader can be granted it, and a pentest scenario shows a reader
      on a hostile page producing only a clean, closed-schema answer.
- [ ] webtool's fetch guards still apply.

## Plan

## Log

- 2026-10-18: dropped as covered by relay#44. Kira's design for the
  personal-agent epic (D8, 2026-10-18) replaces the read-classed fetch
  with webtool's `ask`, which takes a query and no URL and fetches only
  the top search results, under the fetch guards. It is `:egress`, and
  the quarantined reader may now read through an egress tool, decided
  under the caller's labels. So the intent here, a reader that reads
  live pages without a free URL, is met, by the option this issue had
  turned down on 2026-10-17. The pentest s15-web-ask shows a reader on a
  hostile result page giving only a clean, closed answer.
