# Hold act calls for consent and stop holding house changes

## Context

Part of the personal-agent epic Kira started on 2026-10-18. Design
sections D1, D2, D3 of the epic design, which is kept with these issues as
.issues/personal-agent-design.md. Kira's principles: assume the model is
fully compromised; nothing acts in the real world for the operator without
explicit consent, given at the time or in advance; roles draft and the
operator reviews; answers cite sources; the operator never needs to know
relay's internals.

## Acceptance criteria

- [ ] agentloop's rule has class house (allowed whatever the labels), and holds every act call unless a standing consent rule matches exactly. Egress is unchanged.
- [ ] relay.edn accepts :house, :consent (act tools only) and :preview. relay's registry, routine and dead-letter tools are house.
- [ ] The operator's preview command produces a held call's preview. relay stores it with the hold, and relay approve, relay deny and the ACP permission request show it.
- [ ] The example config, relay init, README, resources/AGENTS.md and the spec describe this. A deploy note covers moving rem to :house.
- [ ] Property tests for the rule. Pentest scenarios for act always held, consent matching exactly, and house never held.

## Decisions

## Log
