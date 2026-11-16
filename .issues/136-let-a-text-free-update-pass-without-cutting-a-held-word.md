# Let a text-free update pass without cutting a held word

## Context

Filed by the coordinating session on 2026-11-15, from relay-macos-99's
port of relay#104.

In a contaminated session, relay holds back the text after the last
space of a message until the word is whole (`relay.inert`). Any update
that is not a text chunk makes `relay.hub/inert-lines` call
`flush-streams` with `inert/cut`, so the held word goes out as a cut
word, with every dot and colon broken. A usage_update, a labels-only
session_info_update or a current_mode_update in the middle of a message
then turns `10:30` into `10[:]30` and `e.g.` into `e[.]g.`, though none
of those updates carries text a client draws inline.

relay-macos merges an agent message's chunks across a usage_update.
relay's message boundary should be the same.

## Acceptance criteria

- [ ] An update that carries no text a client draws inline (usage_update,
  a session_info_update with no title, current_mode_update) leaves the
  held word held and passes on unchanged.
- [ ] A test feeds a word split by a usage_update and gets it back as
  for-operator of the whole text.
- [ ] docs/client-protocol.md says a message runs across those updates,
  and that `relay/linksLeftOut` counts one chunk's text, so a client reads
  any nonzero value across a message as "some links were left out".
- [ ] It is known how often agentloop sends a usage_update in the middle
  of a message, and the answer is recorded here.

## Plan

## Log
