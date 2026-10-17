# Build a red-team corpus for research roles

## Context

A fixture web server in staging serves attack pages. Each page is a scenario that must end flagged, refused or harmless.

## Acceptance criteria

- [ ] Attack pages: white-on-white instructions, Unicode tag characters, a lookalike domain, an invented deadline, a redirect into the tailnet, a page telling the agent to email someone.
- [ ] Each is a staging scenario with an asserted outcome of flagged, refused or harmless.
- [ ] The scenarios run under the bb task from the staging runner.

## Plan

Prior-art survey, 2026-10-17. Reuse where a source exists and write only what is specific to relay. Each licence below is unverified until checked at vendoring time.

- Hiding techniques: take test cases from the standards, not our own lists. Homoglyphs from Unicode confusables.txt and UTS #39. Tag characters U+E0000 to U+E007F (ASCII smuggling). Bidi and zero-width characters from UTS #55 and Trojan Source. CSS and HTML hiding per OWASP LLM01: display:none, font-size:0, white-on-white, off-screen, comments, alt text, aria-label, meta and JSON-LD.
- Pages: vendor a few dozen realistic injection pages from WAInjectBench (https://github.com/Norrrrrrr-lyn/WAInjectBench) once its licence and format are confirmed. Take payload wording from garak (Apache-2.0) and the PyRIT converters (MIT), and attacker goals from AgentDojo and InjecAgent. Do not take any of their harnesses. Skip WASP: it needs the WebArena stack, and its paper text is CC BY-NC-SA.
- SSRF variants (decimal and octal IPs, IPv6-mapped addresses, rebinding): PayloadsAllTheThings (MIT).
- Write ourselves, since no public corpus covers them: a redirect into the tailnet (100.64/10, *.ts.net), bypasses of the guarded fetch and the parse sandbox, report-schema attacks (citing a URL the role never fetched, or fabricated evidence for the verifier), the invented deadline, the page telling the role to email someone, markdown-image exfil through webtool's output, and text aimed at the operator's review.
- Format: keep relay's scenario format. Add an `:expected` of flagged, refused or harmless and an ATLAS technique id (AML.T0051.001 for indirect injection) per scenario. No new taxonomy. A manifest records each fixture's source and licence.
- webtool refuses localhost, so the fixture server needs a test-only fixture mode in webtool. Consider a Go build tag that only test builds have.

## Log
