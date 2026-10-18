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

- The extension methods are `_relay/items`, `_relay/resolve` and
  `_relay/trust`, with a leading underscore. ACP reserves names that start
  with `_` for extensions, and the Go SDK refuses any other name for
  CallExtension.
- agentloop knows nothing of relay's dates. The request gives
  `display_ref_prefix` (`shown:<date>/`, the date the wake or conversation
  started in the operator's zone), and agentloop adds `DisplayID(call id)`:
  the id in lower case when it is a short run of `[a-z0-9_-]`, else
  `call-` and 16 hex digits of its sha256. Doc paths are lower case, so the
  id must be. A conversation that runs past midnight keeps its first day.
- The model's note is fixed text plus the ref and nothing of the display,
  since a display can carry labels the result does not (the reader's
  closed answer drops contaminated; its display keeps it).
- A display's labels are the union of its own, the result's, the deferred
  ones and the session's as the call ran. A display with a forbidden label
  is dropped (agentloop and relay tool both), so a public role never keeps
  a private display.
- relay reads displays from the session entries (the run's transcript, or
  the conversation's session file), not from the ACP line, so wakes and
  conversations share one parser and the source is agentloop's own log.
  In a conversation relay keeps a display when it sees the display's
  update go by, before passing it to the client, and sweeps every session
  again when the conversation ends.
- Displays are kept by a new command, `:display/show`, as the role, with
  the display's labels, as a `doc/put` event whose version records the
  call, title and sources. `:doc/put` now refuses any path under `shown/`
  for everyone, so a doc there is always something the operator was
  shown. The same display again is a no-op; another at its path is
  refused.
- A wake that kept displays sends a `shown` notification naming the paths
  only. A conversation does not, since the operator saw them in the pane.
- Trust from the pane is as strong as an ACP approval: relay acp takes
  `_relay/trust` only with `--approvals` (relay chat), only for a doc this
  conversation kept, and only with the version and sha256 `_relay/items`
  listed, through the same `:label/trust` command as `relay trust doc`.
  The event records `via: chat`. Without `--approvals`, `canTrust` is false
  and the pane shows the `relay trust doc` command instead. acp-tui adds a
  confirmation armed after the same 600 ms as approvals.
- The reader's show_operator text comes in the same reply as the answer,
  as an `operator` key, so it costs no extra model call. The whole reply is
  checked for repeated keys before `operator` is taken out. The display's
  title is the caller's question and its sources are the inner result's
  display sources, if any.
- `_relay/resolve` calls `relay.refs/resolve` as it is. It does no
  public-role filtering, which suits relay acp, whose client is the
  operator's. acp-tui's ref grammar is the same as `relay.refs/parse`'s.
- `_relay/items` takes held proposals from the permission requests relay
  acp passed on, with the preview parsed from the request's tool call
  content (`_meta relay/preview`), so it works with the consent change
  whichever lands first.
- acp-tui: the pane key is ctrl+x (bubbles textarea binds ctrl+g). New
  domains are tracked per run in the client, from calls the operator
  allowed and items they trusted; relay keeps no history of approved
  domains yet.

## Log
