# Try relay from the phone with Agmente

## Context

Split from #66 on 2026-10-25. #66 built the WebSocket listener, and it has
been deployed under relay.serve since 2026-10-22. What is left needs
Kira's phone: setting up the listener and trying Agmente against it. The
README section "Reaching relay from your phone" has the details.

Agmente does not declare relay/review, so relay cannot know what it shows
for an approval and does not hold it to a review digest (see #79). Note
what Agmente shows for an approval while trying it.

## Acceptance criteria

- [ ] Agmente lists the conversations and continues one started in
  relay-tui. Record what works and what does not.
- [ ] ntfy notifications for approvals and finished turns open the right
  conversation, if Agmente supports a link for it.

## Plan

Kira's steps:

- [ ] Put a token in the keychain:
  `security add-generic-password -a "$USER" -s relay-websocket -w "$(openssl rand -hex 32)"`
- [ ] Add `:serve {:websocket {:port 8765 :token-command ["security"
  "find-generic-password" "-s" "relay-websocket" "-w"]}}` to
  ~/Sync/relay/relay.edn.
- [ ] Restart the service with `bb service restart`. Look for the
  listener line in ~/Library/Logs/relay/serve.log. Restarting ends live
  conversations.
- [ ] `tailscale serve --bg --https=8765 http://127.0.0.1:8765` (ntfy
  keeps 8443).
- [ ] In Agmente, add `wss://<the Mac's tailnet name>:8765` with the token.
- [ ] Try it out: list conversations, pick up one started in relay-tui,
  and answer an approval on the phone. Record the results against the
  criteria above.

## Log
