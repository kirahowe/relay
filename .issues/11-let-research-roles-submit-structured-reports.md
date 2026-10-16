# Let research roles submit structured reports

## Context

A research role returns findings as data, not prose, so code can check them. The tool is `report_submit` with a schema.

## Acceptance criteria

- [ ] `report_submit` relay tool accepts a summary plus findings.
- [ ] Each finding has title, url, organization, kind, a one-line summary, optional deadline, amount and location, and evidence (url plus verbatim quote).
- [ ] Malformed reports are rejected with a clear error.
- [ ] Property tests on the schema's round trip and validation.

## Plan

## Log
