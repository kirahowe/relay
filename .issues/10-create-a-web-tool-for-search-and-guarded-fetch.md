# Create a web tool for search and guarded fetch

## Context

A new Go repo speaking the envelope protocol. It gives roles `web_search` and `web_fetch`, class egress, labels contaminated. The live search part needs a Brave API key from Kira; everything else can be built and tested with a fake backend, and the `needs-kira` need applies to that live part only.

## Acceptance criteria

- [ ] `web_search` with Brave behind a pluggable backend interface.
- [ ] `web_fetch` allows http(s) and GET only, sends no cookies.
- [ ] Refuses loopback, private, link-local, 100.64.0.0/10, *.ts.net and the Mac's own addresses, and rechecks after every redirect.
- [ ] Size and time caps; a content-type allowlist.
- [ ] Fetched pages are stored as snapshots with a SHA-256.
- [ ] Tests include the redirect-into-private-address case.
- [ ] Live Brave test: needs Kira to supply an API key (tag needs-kira covers that step).

## Plan

## Log
