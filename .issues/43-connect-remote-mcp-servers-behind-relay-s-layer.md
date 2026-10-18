# Connect remote MCP servers behind relay's layer

## Context

Part of the personal-agent epic Kira started on 2026-10-18. Design
sections D9 of the epic design, which is kept with these issues as
.issues/personal-agent-design.md. Kira's principles: assume the model is
fully compromised; nothing acts in the real world for the operator without
explicit consent, given at the time or in advance; roles draft and the
operator reviews; answers cite sources; the operator never needs to know
relay's internals.

## Acceptance criteria

- [ ] agentloop speaks streamable HTTP MCP, with a bearer token from a credentials command.
- [ ] relay connect runs OAuth 2.1 with PKCE and a loopback redirect, and keeps the refresh token in the keychain. It proposes classes from the annotations, shows a plain summary, asks for the code, writes <data>/connections/<name>.edn and pins the definitions at connect.
- [ ] relay mcp-token prints a fresh access token.
- [ ] :fixed-args and :hidden-args are enforced before the server sees a call.
- [ ] Tests run only against a local HTTP MCP test server and a fake OAuth server. A pentest covers a definition that changes after connect.
- [ ] The Codex review loop has converged.

## Decisions

## Log
