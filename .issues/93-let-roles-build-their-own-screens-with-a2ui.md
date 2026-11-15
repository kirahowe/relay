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

Kira, 2026-11-14, on buttons (relay#98 has her answers in full):

- A press on a role-built button does one of two things. It tells the
  role that owns the screen, in a line relay writes, and that starts a
  turn for the role. Or it runs one of relay's own functions on relay's
  data, with no model turn.
- A press never changes config and never counts as Kira agreeing to
  anything that leaves the house.
- A role-built form for adding an agent ends at the steward, which adds
  the role as it can today. relay draws no confirmation screen for it.

Mine, and Kira may overrule them:

- relay plays A2UI's "agent" toward clients. A role authors a surface
  through a tool; relay checks it against the catalog, keeps it as log
  events and replays it to each client. Clients keep sending intents and
  drawing what relay returns.
- A2UI's two kinds of action carry the two things a press may do. An
  `event` is the press that tells the role that owns the surface. A
  `functionCall` that names a function in relay's catalog reaches relay as
  `callAgentFunction` and starts no model turn, so it becomes relay's own
  ops, as a card tick is now.
- The catalog leaves out Image, Video and AudioPlayer. A client fetches
  their URLs when it draws them, before Kira sees anything, so a check
  cannot help. A picture, when a client can show one, comes from a file
  relay holds.
- A2UI has no binding for ACP. relay carries its messages in `_relay/`
  methods.

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
- 2026-11-14: six sessions started at once, one each on this issue and on
  relay#94 to relay#97, and one (relay-32) that coordinates them and
  lands their work. This session wrote no code. It took relay#98 to Kira
  and recorded her answers here and there.
- 2026-11-14: read the v1.0 specification on actions. A Button's `action`
  is an `event` (`name`, `userMessage`, `context`) or a `functionCall`
  (`@call`, `args`). For an `event` the renderer sends an `action`
  message with the name, the surface, the component, a timestamp, the
  resolved `userMessage` and the context. A `functionCall` runs in the
  renderer when it has the function. Otherwise the renderer sends
  `callAgentFunction` with a `functionCallId` and waits for
  `agentFunctionResponse`. So `callAgentFunction` is a message a
  `functionCall` turns into, where the Decisions here first called it a
  kind of action. I corrected the wording.
- 2026-11-14: the steward can already add a role (`role_add`, for a role
  granted `:may-add`), and the daily note lists a new role. The epic's
  example, a screen for adding an agent, therefore needs a form and
  nothing new underneath.
- 2026-11-14: relay#96's spike put a component of relay's own, `Tracker`,
  in relay's catalog, because the basic catalog has no grid. Kira's
  decision above says the catalog is a subset of A2UI's types. This is
  the one place the work so far steps outside it, and it is hers to
  accept or refuse.
