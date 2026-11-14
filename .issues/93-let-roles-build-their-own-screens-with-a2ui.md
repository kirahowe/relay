# Let roles build their own screens with A2UI

## Context

Kira, 2026-11-14: the goal is an app that takes shape beneath the person
using it. It starts with one agent, and the agent builds whatever screens
fit how it is being used, including a screen for adding more agents. Kira
chose A2UI (https://a2ui.org, v1.0, Apache 2.0) for this.

A2UI covers how a screen is drawn. A role sends a tree of components from
a catalog (the list of components a client agrees to draw) and a data
model the components bind to. It has nothing for what relay's cards rest
on: state in the log, revs, several clients on one board, labels on each
text, a text form. So A2UI replaces the closed set of card kinds and the
card wire form, and relay keeps everything underneath.

## Decisions

Kira, 2026-11-14:

- A2UI for role-built screens.
- relay-tui gets its own renderer. The terminal renderers that exist are
  community projects on v0.9 and pre-release.
- relay's catalog is a subset of A2UI's types.
- A link is trusted by where it came from, never by which host it points
  at (relay#94).
- Kira can call a link safe herself (relay#94, relay#95).
- Scouts' links arrive live (relay#94).

Mine, and Kira may overrule them:

- relay plays A2UI's "agent" toward clients. A role authors a surface
  through a tool; relay checks it against the catalog, keeps it as log
  events and replays it to each client. Clients keep sending intents and
  drawing what relay returns.
- A2UI's two kinds of action map onto what relay has. `callAgentFunction`
  starts no model turn, so it becomes relay's own ops, as a card tick is
  now. `event` starts a turn, so it becomes operator text to the role that
  owns the surface.
- The catalog leaves out Image, Video and AudioPlayer. A client fetches
  their URLs when it draws them, before Kira sees anything, so a check
  cannot help. A picture, when a client can show one, comes from a file
  relay holds.
- A2UI has no binding for ACP. relay carries its messages in `_relay/`
  methods.

## Open

What a button on a role-built screen may do (relay#98). Cards were
designed with no buttons and no approvals.

## The parts

- relay#94 Keep a link live when relay can say where it came from
- relay#95 Call a link safe from the dashboard
- relay#96 Draw A2UI surfaces in relay-tui
- relay#97 Keep role-built surfaces in the log and send them to every
  client
- relay#98 Decide what a button on a role-built screen may do

## Acceptance criteria

- [ ] Every part above is closed or dropped.

## Log

- 2026-11-14: read A2UI's v1.0 specification, its basic catalog guide and
  its renderer list. The basic catalog takes a URL in `Image.url`,
  `Video.url`, `Video.posterUrl` and `AudioPlayer.url`, and in the
  `openUrl` function. Its own check looks only at the scheme (http, https
  and, for media, data). `Text` takes Markdown without links or images.
