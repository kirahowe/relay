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
- [x] The Codex review loop ran three rounds (see the log).

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

**A role with the workbench may hold private and contaminated.** When
relay must guess a session's labels (core/session-labels-bound), a role
granted the workbench counts as able to hold both, since agentloop
drops contaminated only for a closed schema.

**relay init gives the hub the workbench**, with or without webtool,
and the pentest scenario is s18-workbench-sandbox (s16 and s17 were
taken by the display and remote MCP scenarios).

**No os.name check in relay's config.** agentloop refuses a workbench
request on a system without sandbox-exec, which is where the knowledge
belongs.

## Log

2026-10-18. Built in agentloop/workbench (a package of its own) and
granted in relay with :workbench. Verified: agentloop `make test` and
`make build`, relay `bb test` (617 tests), `bb pentest` (33 pass, 31
skip for missing sibling binaries), and s18-workbench-sandbox against
the real sandbox.

Three Codex rounds, read-only, each round fixed before the next:

1. Seven findings, all accepted. sysctl was allowed at large, which
   hands over every other process's command line; where the scratch
   directory goes was a setting, and one inside iCloud Drive would be
   uploaded by a process outside the sandbox; a prefix deny entry
   resolved only its parent, so a symlinked ~/.claude left its target
   readable; a command could write file after file and fill the disk
   before the check that ran after it; the gcloud and other cloud
   credential paths were missing; a schema of then and else with no if
   was classified as closed, which let prose into a clean session
   (quarantine's classifier, which the workbench inherits); and relay
   accepted :deny_read in the harness settings and silently dropped it.
2. Two findings, both accepted. A command can unlink a file it is still
   writing, so no walk sees its size, which the free-space floor now
   bounds; and a dotfiles setup links each file separately, so the
   targets of symlinks inside a denied directory are denied too, with
   the walk bounded to two levels and 2000 entries (unbounded it took
   minutes on the real deny list). Round two also confirmed that a
   nested sandbox-exec cannot loosen the profile.
3. Three findings, all accepted. A command may delete its own scratch
   directory, so the free-space floor is read from the session directory
   above it; a command can take the permissions off a directory it made,
   so a walk that cannot see everything counts as over the limit and
   cleanup puts the permissions back; and Hugging Face keeps its token
   in ~/.cache/huggingface as well.

Rounds two and three found no escape from the profile itself, only ways
around the resource bounds. Stopped at three rounds, as the dev
instructions say, with nothing in-scope left open.
