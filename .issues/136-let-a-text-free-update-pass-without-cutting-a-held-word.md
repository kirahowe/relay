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

- [x] An update that carries no text a client draws inline (usage_update,
  a session_info_update with no title, current_mode_update) leaves the
  held word held and passes on unchanged.
  - Receipt: `relay.hub/text-free?`; `relay.inert-hub-test`:
    `a-text-free-update-leaves-the-held-word-whole` (all three kinds,
    each passed as it came) and
    `an-update-that-carries-text-still-cuts-the-held-word`.
- [x] A test feeds a word split by a usage_update and gets it back as
  for-operator of the whole text.
  - Receipt: `a-text-free-update-leaves-the-held-word-whole` compares
    with `relay.text/conversation-text` of the whole text (see the Log).
- [x] docs/client-protocol.md says a message runs across those updates,
  and that `relay/linksLeftOut` counts one chunk's text, so a client reads
  any nonzero value across a message as "some links were left out".
  - Receipt: docs/client-protocol.md, the bullets on whole words and on
    what a message is.
- [x] It is known how often agentloop sends a usage_update in the middle
  of a message, and the answer is recorded here.
  - Receipt: the first Log entry below.

## Plan

## Log

- 2026-11-15, relay-ec. How often agentloop sends a usage_update in the
  middle of a message: never. Its translator (acp/translate.go, the
  EntryMessage case) sends one after an assistant message's chunks and
  tool calls, and another after a tool result that recorded model usage.
  But that is after the message's last chunk and before end_turn, and
  the last word is still held then. So any reply that did not end in a
  space had its last word cut, nearly every turn: "Meet at 10:30" went
  out as "Meet at 10[:\]30".
- 2026-11-15, relay-ec. "Text-free" is a fixed set of kinds
  (usage_update, current_mode_update, session_info_update) and none of
  the fields inert-update breaks (title, name, content, rawInput,
  rawOutput, entries). Being one of those kinds is not enough: a
  session_info_update with a title still cuts, as the criterion says,
  and so would one of those kinds that turned up carrying content. A
  tool call with no title is not text-free, because a client draws
  calls among the words. available_commands_update and
  config_option_update still cut. They are rare, and whether their
  strings get broken is relay#134's question.
- 2026-11-15, relay-ec. The test compares the joined chunks to
  relay.text/conversation-text of the whole text, which is what relay
  sends for a message that arrives in one piece. The criterion says
  for-operator, but conversation-text is the function for conversation
  text.
- 2026-11-15, relay-ec. A usage_update that turns the session clean
  still sends the held word first. That path goes through finish-turn
  before the session counts as clean, and its test
  (a-session-that-turns-clean-sends-what-it-held-first) is unchanged.
