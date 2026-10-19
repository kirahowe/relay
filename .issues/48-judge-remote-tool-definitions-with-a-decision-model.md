# Judge remote tool definitions with a decision model

## Context

relay#47 left the definitions of remote MCP tools trusted once the
operator confirms them at relay connect. A deterministic check flags
suspicious text. Kira asked on 2026-10-19 for a follow-up: investigate
running a decision model over tool descriptions to estimate how likely
each is to be malicious. She named jev as an example of such a model.

## Questions to answer

- Which decision model: jev, a small classifier for prompt injection, or
  an LLM judge with a closed schema (a verdict enum and a score). Measure
  each against a corpus of benign and hostile definitions.
- Where it runs: at connect, and again on every definition change that
  `relay fresh` would accept.
- What it returns: a closed answer, so the model's own reading of the
  hostile text cannot reach a role.
- How it combines with the deterministic flags, and what the operator
  sees in the connect summary.

## Acceptance criteria

- [ ] A written comparison, with false positive and false negative rates
      on the corpus.
- [ ] A recommendation for Kira, with its cost per connect.

## Log
