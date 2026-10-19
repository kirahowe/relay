# Make agentloop cleanroom's reader

## Context

Cleanroom's reader is headless Claude Code today. Moving it to agentloop fits the open-standards and small-repos direction. Related project: cleanroom, a mail gateway.

## Acceptance criteria

- [ ] Cleanroom's reader runs on agentloop instead of headless Claude Code.
- [ ] The reader's tool and label restrictions are unchanged or tighter.
- [ ] How the two repos split the work is undecided; record it.

## Plan

## Log

- 2026-10-17: built as cmd/agentloop-reader in cleanroom, on the branch agentloop-reader in the worktree ~/code/projects/.next/cleanroom-reader (0a17181, d656822, 257b574 on a0575ad). The split: cleanroom owns the reader, because the reader contract is already a command template there. agentloop is used as a library (anthropic, mcp), with no cleanroom code in agentloop. The API key comes from --key-command, and there is no Claude Code login. A failure exits 1 with the reason on stderr. Two Opus review rounds: round 1 found the key command escaping the process-group kill, which is fixed with a test; round 2 found no in-scope defect. Not merged: go.mod replaces agentloop with ../agentloop, because the mcp package exists only on agentloop's local main. Once Kira pushes agentloop, drop the replace, pin the version and merge. It has not been run end to end through cleanroom ask against the demo inbox, and the example policies still use claude-reader.
- 2026-10-18: Pinned to the published agentloop (cleanroom 4043aa3, make test green) and merged into cleanroom main locally. Kira accepted it without the end-to-end cleanroom ask run. cleanroom stays unpushed until Kira says so.
