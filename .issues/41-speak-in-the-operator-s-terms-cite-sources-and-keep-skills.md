# Speak in the operator's terms, cite sources and keep skills

## Context

Part of the personal-agent epic Kira started on 2026-10-18. Design
sections D5 (relay side), D6, D10 of the epic design, which is kept with these issues as
.issues/personal-agent-design.md. Kira's principles: assume the model is
fully compromised; nothing acts in the real world for the operator without
explicit consent, given at the time or in advance; roles draft and the
operator reviews; answers cite sources; the operator never needs to know
relay's internals.

## Acceptance criteria

- [ ] Every relay read tool prints each item's [[kind:id]] ref, and a pure resolver checks a ref against the log.
- [ ] The shared role instructions require refs for claims, keeping skills and a charter for each role, and never naming relay's internals to the operator.
- [ ] Skills live at skills/<role>/<slug>.md. The role's system prompt lists them, under the label filters.
- [ ] The hub's default instructions are rewritten to match.
- [ ] Eval cases: no internals in replies, citations present and resolvable, writing a skill, using a skill, and placeholders for unknown facts.

## Decisions

## Log

## Decisions (voice workstream)

- Note refs are `note:<role>/<name>`, since note names are per role. A `shown:` ref resolves the doc `shown/<id>.md` (D4 stores displays there).
- A ref's id escapes `[`, `]`, newline and a `%` that would read as an escape, so format then parse round-trips for any id. Escaping is invisible for ordinary ids and URLs.
- `relay.refs/resolve` does no public-role filtering: it serves the operator's chat client, and returns `:labels` so the client can judge. Do not expose it to a role.
- Read tools print a ref only where it adds no new text: a doc's path or a note owner's id is shown in the ref only when the item's labels already cover the labels of that path or id. Otherwise the property test in labels_test found markers leaking.
- doc_get puts the ref on a trailing line so the content still starts the output.
- Skill enforcement is in core (`decide* :doc/put` and `:doc/remove`), for any actor that is a role; the operator is exempt. A bare `skills/x.md` is refused for roles.
- Contaminated skills: left out of the prompt and counted as "some are left out" without a number or path (same as notes). Trusting the doc (label/trust) puts it back. Their labels join the wake's labels.
- The forbidden-words eval grader leaves out the plain verb "hold": Haiku wrote "the only fact I hold" and the word list as written would fail it. It checks "on hold" and "held/holding your call".
- Added a `:citation` item kind to the scenario matcher: refs in memos to the operator and in outcomes of memos from the operator, resolved against the state.
- Baseline on Haiku: see test/scenarios/BASELINES.md. skill-written-after-multistep is 1/3.

## Left undone

- `relay facts` and the other CLI renderers do not print refs; only the role tools do.
- Shared skills and ACP exposure of `resolve` (another agent).
- No Codex review: nothing here touches holds or classes. The one security-relevant point (refs and paths echoing text a contaminated session chose) is covered by the labels property test.
