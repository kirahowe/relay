# Keep large integers exact in MCP structured results

## Context

From the post-QA Codex loop on relay#58, 2026-10-21. In scope under the
threat model (a correctly configured tool returning large integer ids
triggers it), but it predates #58, and an MCP server is not one of the
three components the strict dialect covers. Codex:

> MCP structured results still lose numeric precision after the strict
> screen. mcp/wire.go:121, mcp/result.go:69. When a stdio MCP tools/call
> (id 3) has the output schema {"type":"object","properties":{"n":
> {"type":"integer"}},"required":["n"]}, the response {"jsonrpc":"2.0",
> "id":3,"result":{"content":[],"structuredContent":{"n":
> 9007199254740993}}} passes the strict screen. The MCP SDK v1.8.0 then
> decodes StructuredContent into any without UseNumber, which gives
> float64 9007199254740992. canonicalValue serializes that rounded value,
> validation passes, and the model receives {"n":9007199254740992}.

The fix is in agentloop.

## Acceptance criteria

- [ ] A structured result with an integer above 2^53 reaches the model
      exactly; a test covers it.

## Log
