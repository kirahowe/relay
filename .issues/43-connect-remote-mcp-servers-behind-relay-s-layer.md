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

- agentloop uses the official Go SDK's streamable HTTP client, since it
  already depended on the SDK. It adds its own rules around it: https or
  loopback only, no proxy, redirects only within the origin, the token
  only to that origin, bodies capped at 1 MiB, no standalone GET stream,
  no stream resumption (MaxRetries -1).
- The bearer token is the credentials command's output, kept in memory
  only, with no credentials file for a remote server: nothing needs to
  read a file, and a file would be one more copy of the token on disk. On
  a 401 agentloop runs the command again (at most every 10 s) and resends
  once, since a refused request was not acted on.
- The SDK's frame checks replace the stdio wire's duplicate-key and
  one-line checks for HTTP. The SDK decodes each message once and
  agentloop acts on that decoding, so there is no second parser to
  disagree. Wrapping the SDK's connection would lose its unexported
  session hook, which sets the protocol version header.
- fixed_args and hidden_args are enforced in agentloop, in the mcp tool:
  removed from the schema the model sees, refused through InputChecker
  (before the policy, so an always-hold tool never holds a call that
  would then be refused) and again in CallWith, then the merged call is
  validated against the server's own schema. The digest stays the
  server's definition's. Each name must be a property of the schema, and
  a required argument cannot be hidden.
- A new tool protocol `mcp-http` (agentloop) and `:mcp-http` (relay)
  names a tool from a remote server, so a tool's protocol says how it is
  reached; validation pairs each protocol with the right server kind.
- Connection tools are named `<connection>_<tool>` (sanitised, at most 64
  characters), so they cannot clash with relay.edn's tools.
- The connect-time digest is a `:pin`, not relay.edn's strict `:digest`,
  so `relay fresh` can accept a changed definition, as the task asks.
  connect also records the pins in the log (new operator-only command
  `:tools/pin`), so connecting again replaces older pins. Dispatch uses
  `:digest`, else the log's pin, else the connection's `:pin`.
- Proposed `:act` tools get `:always-hold true`, so "asks you first every
  time" is true before D1 lands. Once D1 makes act always held, and D2
  adds :consent, the orchestrator may want to drop this default:
  always_hold overrides consent rules.
- The keychain item holds base64 JSON (refresh token, access token and
  its expiry, any client secret), written through `security -i` on stdin
  so no secret is in an argument list. mcp-token reuses an access token
  with more than 10 minutes left, else refreshes under a file lock so two
  wakes never spend a rotating refresh token twice.
- PKCE is required. An authorization server whose metadata lists
  challenge methods without S256 is refused; one that lists none is
  accepted (S256 is still sent), so a server that omits the field is not
  locked out.
- With no protected-resource metadata, connect falls back to the MCP
  server's origin as issuer (the 2025-03-26 rule). A resource named in
  the metadata must be on the server's origin, at or above its path.
- relay.edn is not edited by connect; it prints the lines to add. Writing
  relay.edn would reformat the operator's file.
- Tests never touch the keychain or a browser: test-support's isolate!
  makes both throw unless a test binds its own. The pentest writes the
  connection file the way connect would (from agentloop's own listing)
  and takes the token from a file; the OAuth flow itself is covered by
  relay.connect-test against agentloop's fakeremote.

## Log
