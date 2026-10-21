# Keep a live conversation with each role that any client can switch to

## Context

Kira wants relay to feel like xAI's Grok Bot: a list of roles down the
side, one key to switch between conversations with them, and several
conversations going at once. A conversation keeps running when the client
closes, and Kira can pick it up on another device. The TUI is the first
client. The same backend and data model must later serve a phone, and
maybe a native or web app.

Today a conversation is one `relay acp <role>` process started by its
client. Closing the client ends the wake and kills any running turn, and
no other client can attach. acp-tui holds one session and prints chat
into the terminal's scrollback, so it cannot redraw a different
conversation.

Prior art: Grok Bot (https://x.ai/news/introducing-grok-bot). It has a
sidebar of bots, each with its own ongoing conversation. Work continues
when you leave, a bot pulls you back only for approvals, and the same
thread continues on desktop or phone.

## Decisions

Kira, 2026-10-21:

- One conversation per role at a time to start. Several per role can come
  later if needed.
- No resuming a conversation that was closed. Continuity between
  conversations comes from the memory system. The spec's rule that every
  session starts empty stays.
- A conversation that is still running can be picked up from any device.
  There is one user on several devices, used at different times. This is
  not multiplayer.
- An always-on service on this Mac is fine. It is the server, and clients
  reach it.

Mine, 2026-10-21 (Kira can overrule):

- A conversation holds its role only while a turn runs. Today it holds the
  role for as long as the client is connected, so a conversation left open
  for days would keep the dispatcher from waking that role, and its memos
  would wait. While the conversation is idle, a dispatched wake may run. A
  prompt sent during that wake waits until the wake ends, and the client
  says so. The role still runs one turn at a time. This changes the
  OpenConversation rule in spec/relay.allium, not labels or holds.
- The client protocol is ACP plus `_relay/*` extensions, on the cards
  pattern: a request, then a `.../changed` notification. A plain ACP client
  such as Zed or Agmente still gets a working switcher through standard
  `session/list`.
- The service runs one agentloop per conversation, as a wake does today.
  agentloop can host many sessions in one process, but a process per
  conversation keeps a crash to one conversation and keeps the wake model.

## Plan

1. #63 Track conversations and each role's status in relay's core (pure).
2. #64 Run conversations in a relay service that outlives its clients.
   `relay acp` becomes a stdio bridge to it, so `relay chat` keeps working.
3. #65 Switch between roles and their conversations in acp-tui.
4. #66 Reach the relay service from the phone over the tailnet. Try
   Agmente first.
5. Later, a web client. #56 (serve the board over HTTP and SSE) belongs
   with it.

#64 and #66 add a long-running service and a network listener, so they get
the Codex review loop. Kira installs launchd jobs and deploys.

## Log
