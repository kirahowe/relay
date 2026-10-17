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
