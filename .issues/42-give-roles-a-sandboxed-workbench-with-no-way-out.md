# Give roles a sandboxed workbench with no way out

## Context

Part of the personal-agent epic Kira started on 2026-10-18. Design
sections D7 of the epic design, which is kept with these issues as
.issues/personal-agent-design.md. Kira's principles: assume the model is
fully compromised; nothing acts in the real world for the operator without
explicit consent, given at the time or in advance; roles draft and the
operator reviews; answers cite sources; the operator never needs to know
relay's internals.

## Acceptance criteria

- [ ] agentloop runs a workbench child session. Its sh tool runs under sandbox-exec with a deny-default profile: no network, minimal mach-lookup, deny-listed reads, writes only to scratch, and limits.
- [ ] The caller's schema decides the labels, as for the quarantined reader. The class is read.
- [ ] relay grants it with :workbench. relay.edn holds a configurable read deny list.
- [ ] Pentests with real sandbox-exec: curl, DNS, a unix socket, open, osascript, pbcopy, writing outside scratch and reading a denied path all fail.
- [ ] The Codex review loop has converged.

## Decisions

## Log
