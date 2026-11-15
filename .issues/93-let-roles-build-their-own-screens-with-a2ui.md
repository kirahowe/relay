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

Kira, 2026-11-14, on how a live link is shown, in the relay#95 session
and reported here by the relay#94 session: "I think you have the right
analysis, we should stop trying to fix the symptom and get to the root
of the issue. Links as data is a better direction."

- Shown text is always inert, and a link is data relay attaches to it.
  Only a channel's own mechanism carries the address (relay#118). This
  replaces relay#94's live link left whole between angle brackets.

Kira, 2026-11-14, on buttons (relay#98 has her answers in full):

- A press on a role-built button does one of two things. It tells the
  role that owns the screen, in a line relay writes, and that starts a
  turn for the role. Or it runs one of relay's own functions on relay's
  data, with no model turn.
- A press never changes config and never counts as Kira agreeing to
  anything that leaves the house.
- A role-built form for adding an agent ends at the steward, which adds
  the role as it can today. relay draws no confirmation screen for it.

Kira, 2026-11-14, on a component of relay's own. relay#96's spike had
put `Tracker`, a grid, in relay's catalog. Her words: "can we not build
that from primitives available from a2ui? if not then yes we should keep
it anyway."

- It can be built from primitives (relay#102's Log has the test), so
  `Tracker` leaves relay's catalog, and the catalog is a subset of A2UI's
  basic catalog with nothing added. This reading of her answer is mine.
- Nothing is lost by it. The tracker card kind stays as it is, and the
  board goes on drawing it.

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

The coordinating session's, 2026-11-14, and Kira may overrule them:

- A role-built surface is a fourth card kind, beside checklist, tracker
  and note. It reuses card events, the board, `_relay/cards` and its
  notifications, refs and the ready view. Retiring the three older kinds
  is optional and later (relay#102). This is less than the Context above
  says, where A2UI replaces the card kinds.
- A surface has one rev and one set of labels in the first version, not
  one for each component or path.
- relay resolves templates, `@path`, `formatString` and `@index` before
  any client sees a surface, so a client gets final strings.
- The first catalog is what relay-tui can operate: Text, Row, Column,
  List, Card, Divider and CheckBox. Button is the second step, now that
  relay#98 is decided. TextField and ChoicePicker come when relay-tui can
  type and choose in them. relay holds the catalog file, and relay-tui's
  copy is checked against it.
- One function in relay-tui decides what is drawn as a followable link.
  relay#95 owns it and surfaces use it.

## The parts

- relay#94 Keep a link live when relay can say where it came from
- relay#95 Call a link safe from the dashboard
- relay#96 Draw A2UI surfaces in relay-tui
- relay#97 Keep role-built surfaces in the log and send them to every
  client
- relay#98 Decide what a button on a role-built screen may do
- relay#100 Draw surface cards on relay-tui's board
- relay#101 Teach roles when and how to build a surface, with eval cases
- relay#102 Decide whether checklist, tracker and note become built-in
  surfaces
- relay#106 Put buttons on role-built surfaces by Kira's rule
- relay#107 Let Kira fill in a form a role built
- relay#111 Keep a new surface in the conversation until Kira pins it
- relay#112 Let Kira put a surface back as it was
- relay#113 Decide how tabs, pages and placement work above the board
- relay#114 Let a surface show relay's own data without the role
  rewriting it
- relay#118 Carry a live link as data beside inert text

relay#111, relay#113 and relay#114 each wait on a decision of Kira's.
relay#111 questions a decision recorded above: the coordinating session
has a surface on the board from the start, and the relay-macos session
reads Kira as wanting it kept in the conversation until she pins it.

The order of building: relay#97 and relay#100 first, with a catalog that
shows things and takes ticks. Buttons next (relay#106), then forms
(relay#107), which carry the epic's own example, the screen for adding
an agent. relay#101 can run once relay#97 has landed.

Filed along the way and not parts of this: relay#103, a seed lost when a
property failed in `bb verify`; relay#104, conversation text that
reaches a plain ACP client as the agent wrote it; relay#105, an answer
to `_relay/cards` that grows with the archive; relay#108, a bound on the
live links a client is sent; relay#109 and relay#110, two bugs in
today's board; relay#115 and relay#116, on relay as a product;
relay#117, what Proton Mail follows in relay's mail; relay#119, whether
a link in text no outsider shaped is left as written, which is Kira's to
decide after relay#118; relay#120, a role's title that relay sends with
its links whole; relay#121, how far outside text is broken, which is
also Kira's; relay#122, a ready-view property in relay-tui that fails on
one seed; relay#123, a check that relay-macos's copies of the shared
test data match relay's; relay#124, relay#125 and relay#126, three
faults in how links are broken; relay#127, relay#128 and relay#129,
three relay-tui follow-ups from the reviews of relay#95 and relay#100;
and relay#130, an address that a character with no width hides from the
link breaker.

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
- 2026-11-14: filed relay#100 to relay#104 on behalf of every session.
  Each session works in its own jj workspace, and each workspace would
  have given its next issue the same number, so one session files them
  all.
- 2026-11-14: asked Kira about `Tracker` and tested her question. A grid
  of Rows with weights lines up in relay#96's renderer at 60 columns and
  wider. The coordinating session pointed out that the catalog needs no
  grid at all while the tracker card kind exists, which is what the
  Decisions now say. The test and its limits are in relay#102.
- 2026-11-14: nothing is left to do on the epic itself. It waits on its
  parts: relay#96 and relay#98 are in review, relay#94, relay#95,
  relay#97 and relay#100 have sessions on them, relay#101 waits on
  relay#97, and relay#102 is Kira's to decide after real use.
- 2026-11-14: the relay#97 session reported that its first landing has
  no Button, and sent two follow-ups. One was the first eval case of
  relay#101, which now holds it. The other is relay#105. Buttons and
  forms had no issue, so I filed relay#106 and relay#107 as parts, and
  moved the form for adding an agent from relay#101 to relay#107.
- 2026-11-14: the relay-macos session, where Kira is talking through the
  Mac app, sent four more parts (relay#111 to relay#114) and two issues
  about relay as a product (relay#115 on secrets, relay#116 on the
  threat model). Kira told that session to file what was relevant.
  relay#98 is done.
- 2026-11-14: the relay#94 session sent relay#118, the rework of how a
  live link is shown that Kira approved in the relay#95 session, and it
  is a part. It took over the half of relay#117 about a push. Kira
  answered relay#117's questions here: she reads relay's mail in Proton
  Mail on her phone, and she left how a push looks to the session.
- 2026-11-15: Kira told the relay#97 session that the user should not
  have to know anything about how surfaces work, and that the agent
  decides what makes sense and acts as the designer. That session
  recorded it in relay#101, with its own undecided proposal of a
  designer role the steward consults, and her always-on UX agent as
  later work, since she said most users will not need it.
