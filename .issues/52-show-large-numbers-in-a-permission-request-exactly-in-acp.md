# Show large numbers in a permission request exactly in acp-tui

## Context

Filed from the Codex review of relay#51, with the reviewer's text:

> ACP rounds large numeric fixed arguments before displaying approval.
> acp-tui/internal/wire/wire.go:162. The SDK decodes
> ToolCallUpdate.RawInput into any using ordinary json.Unmarshal,
> converting numbers to float64. A fixed numeric recipient ID of
> 9007199254740993 consequently displays as 9007199254740992, while
> agentloop's digest and execution retain the original ID. Fixed values
> are absent from the title, and previews are optional. This existing
> decoding gap now also affects fixed arguments: an ordinary numeric
> identifier can differ between approval and execution. Preserve exact
> JSON numbers and add a wire-round-trip test above 2^53.

It predates #51 and applies to any number in a request's input, the
model's included. Fixing it needs the raw JSON of the request, below the
SDK's decoding.

## Acceptance criteria

- [x] acp-tui shows every number in a request's input as written.
  - Receipt: acp-tui zwrrpnsy (relay#58 QA round) reads rawInput from the raw line, not the SDK's float64 decode.
- [x] A wire test with a number above 2^53.
  - Receipt: acp-tui internal/wire/wire_test.go, rawInput 9007199254740993 and a 34-digit decimal shown exactly.
