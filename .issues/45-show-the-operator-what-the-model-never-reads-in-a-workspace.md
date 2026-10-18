# Show the operator what the model never reads, in a workspace pane

## Context

Part of the personal-agent epic Kira started on 2026-10-18. Design
sections D4, D5 (client side) of the epic design, which is kept with these issues as
.issues/personal-agent-design.md. Kira's principles: assume the model is
fully compromised; nothing acts in the real world for the operator without
explicit consent, given at the time or in advance; roles draft and the
operator reviews; answers cite sources; the operator never needs to know
relay's internals.

## Acceptance criteria

- [ ] Envelope results may carry display. agentloop never gives it to the model, and sends it to ACP clients.
- [ ] relay stores displays as docs under shown/, with their labels. Wake notifications mention them.
- [ ] The quarantined reader supports show_operator.
- [ ] acp-tui has a workspace pane that shows displays, held proposals with their previews, and citations. The operator can approve, deny and trust from it, and it renders from relay's data.
- [ ] Trust from the pane has the same operator-presence guarantee as ACP approvals.
- [ ] A pentest shows display content never reaching the model.

## Decisions

## Log
