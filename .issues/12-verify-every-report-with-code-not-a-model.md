# Verify every report with code, not a model

## Context

Reports are checked by deterministic code. Checks produce flags only. Nothing is deleted. Reports are rendered so they cannot carry markup or hidden text.

## Acceptance criteria

- [ ] Each link fetches 200 through the guarded fetcher; cross-domain redirects are flagged.
- [ ] The evidence quote is found in the fetched snapshot.
- [ ] Domain is checked against the claimed organization and against first-seen domains.
- [ ] Punycode and mixed-script domains are flagged; hidden characters are flagged.
- [ ] Dates parse and are in the future.
- [ ] Reader-directed phrases (text addressed to the agent or operator) are flagged.
- [ ] Rendering is visible plus defanged: no Markdown or HTML, no images, full URLs with the registered domain shown.
- [ ] Property tests for each check.

## Plan

## Log
