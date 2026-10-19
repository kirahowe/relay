# Keep preview labels and fixed arguments in the approval

## Context

A cross-cutting security review of the personal-agent epic found two
High defects.

1. Preview contamination was lost between relay and the approval UI. A
   clean, private session proposes an act call, and the preview returns
   contaminated service text with a URL. relay added the preview text but
   left `agentloop/labels` as the session's, and `_relay/items` copied
   only the session's labels. acp-tui and the pane left the links live.
2. MCP fixed arguments sat outside the preview and the approval binding.
   An act call with a fixed recipient is held, the operator changes the
   recipient, and the held call resumes. The approval digest matched the
   unchanged model input and the pin matched the server definition, so
   the call ran with the new recipient. The preview never saw the fixed
   recipient either.

## Acceptance criteria

- [x] A permission request's preview carries its own labels (the
      command's, the tool's static ones and the session's) under
      `relay/labels`, apart from `agentloop/labels`.
- [x] `_relay/items` labels a proposal with the session's and the
      preview's labels.
- [x] acp-tui, `relay holds` and `relay approve` defang every preview,
      whatever its labels.
- [x] agentloop decides, holds and binds approval on the effective input
      (model input plus fixed args). A changed setting refuses at resume.
- [x] The preview command gets the effective input, and the approval
      shows the fixed args as the operator's settings.
- [x] Tests and pentest checks for both.

## Decisions

- Consent sees the effective input: what the model wrote with the fixed
  arguments added. The model cannot give a fixed argument (agentloop
  refuses that before the policy), so a rule on a fixed argument works
  and cannot be dodged by leaving it out or overriding it.
- The binding is `input_sha256` over the effective input, not a second
  digest. One digest keeps one rule for relay and every client. For a tool
  with nothing fixed the effective input is the model's, so existing
  holds keep their digests.
- The hold's digest is kept in the session (`CallInfo.HoldSHA256`), and a
  person's allow records the digest it was given for
  (`ApprovedSHA256`). A resolution must name the hold's digest. An allow
  whose effective input no longer has it becomes a deny with
  `SettingsChanged`, the same way a policy refusal becomes a deny, so the
  run carries on and the model is told. An allow recorded before such a
  change (a crash in between) also does not run.
- A hold opened before this change on a narrowed tool has a digest of the
  model input only, so its approval is refused as a settings change once
  deployed. That is the safe direction, and such holds should be rare.
- relay approve also refuses an allow when the tool's current fixed args
  differ from the ones the hold recorded, so the operator is told at the
  terminal. agentloop still enforces it at resume; relay's check is only
  for a clear message. Removing the fixed args counts as a change.
- Over ACP the request's `rawInput` is the effective input and
  `_meta["agentloop/fixed_args"]` names the values. ACP's rawInput is the
  input sent to the tool, so this is its meaning. The title still quotes
  the model's input.
- Preview labels go on the preview block's `_meta` (`relay/labels`), not
  on the tool call, so a client cannot mistake them for the session's.
  acp-tui unions them into how it shows the request and the proposal, and
  the session's labels never take them.
- `relay holds` defangs a clean preview itself and leaves a contaminated
  one to text/block, since defang is not idempotent.

## Log

- 2026-10-18: fixed in agentloop, acp-tui and relay; pentest checks in
  s17-remote-mcp and two new consent scenarios.
