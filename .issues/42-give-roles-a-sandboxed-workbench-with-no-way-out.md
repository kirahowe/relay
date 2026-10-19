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

- [x] agentloop runs a workbench child session. Its sh tool runs under sandbox-exec with a deny-default profile: no network, minimal mach-lookup, deny-listed reads, writes only to scratch, and limits.
- [x] The caller's schema decides the labels, as for the quarantined reader. The class is read.
- [x] relay grants it with :workbench. relay.edn holds a configurable read deny list.
- [x] Pentests with real sandbox-exec: curl, DNS, a unix socket, open, osascript, pbcopy, writing outside scratch and reading a denied path all fail.
- [ ] The Codex review loop has converged.

## Decisions

**No mach-lookup allowlist at all.** The design expected a minimal one,
found empirically. The tests found none is needed: with a deny-default
profile and no mach-lookup rule, sh, the coreutils, find, grep, rg, awk,
sed, sort, python3, sqlite3, jq, pdftotext, file, stat, shasum, tar and
zip all work. So the profile has no mach-lookup rule, which is stronger
than an allowlist and simpler to read. mdfind is left out, as the design
allowed: it starts but finds nothing without Spotlight's service.

**Killing a runaway: stop everything, then kill.** One pass that stops
and kills together leaks: a fork bomb forks faster than its processes can
be listed, so a child forked while the pass ran survives and goes on
forking. Stopping first converges, since a stopped process cannot fork.
Processes of the sandbox are found by asking sandbox_check which ones may
write this session's scratch directory and not a probe directory beside
it, so no other sandbox and no unsandboxed process is ever signalled; a
process group is killed whole only when none of its processes is plainly
outside. The process limit (ulimit -u, the current count plus 64) is set
outside the sandbox just before exec, so a command cannot raise it.

**The workbench is a package of its own, not a change to quarantine.**
It reuses quarantine's closed-schema classifier, Compile, Answer,
ParseReply, AnswerContent and ResultLabels unchanged, as the brief asked.
The reader cannot read through the workbench, since quarantine.tools
names only the request's own tools.

**Fail closed with no home directory.** The default deny list is built
from the home directory. Without one the workbench refuses to start,
rather than run with a list that misses the keychain and browser data.

**The child's model is the harness's :reader model.** A harness that
already names a small model for reading wants the same one here; the
request's workbench block may name another. The child is bounded at 40
turns, 4 sh calls a turn and 400000 tokens.

**relay's data directory is always denied.** The workbench reads the
operator's files, not relay's log, so relay adds its data directory to
the deny list in every request.

**The grant is refused on a public role and a script harness.** A public
role's session may not hold private, and a script harness's program could
claim any labels. The os check for sandbox-exec is left to agentloop, so
relay's config checks stay pure.

**No os.name check in relay's config.** agentloop refuses a workbench
request on a system without sandbox-exec, which is where the knowledge
belongs.

## Log
