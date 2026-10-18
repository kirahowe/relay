# Hold act calls for consent and edits only after untrusted text

## Context

Part of the personal-agent epic Kira started on 2026-10-18. Design
sections D1, D2, D3 of the epic design, which is kept with these issues as
.issues/personal-agent-design.md. Kira's principles: assume the model is
fully compromised; nothing acts in the real world for the operator without
explicit consent, given at the time or in advance; roles draft and the
operator reviews; answers cite sources; the operator never needs to know
relay's internals.

## Acceptance criteria

The class first called house is edit, held once the session is
contaminated (design change of 2026-10-18, below).

- [x] agentloop's rule has class edit (held when the session is contaminated), and holds every act call unless a standing consent rule matches exactly. Egress is unchanged.
- [x] relay.edn accepts :edit, :consent (act tools only) and :preview. relay's registry, routine and dead-letter tools are write.
- [x] The operator's preview command produces a held call's preview. relay stores it with the hold, and relay approve, relay deny and the ACP permission request show it.
- [x] The example config, relay init, README, resources/AGENTS.md and the spec describe this. A deploy note covers moving rem to :edit.
- [x] Property tests for the rule. Pentest scenarios for act always held, consent matching exactly, and edit held exactly when contaminated.

## Decisions

- Kira decided the hold rule on 2026-10-18: act is held on every call
  unless standing consent matches, egress is unchanged.
- Design change from the orchestrator, same day: the class is `edit`, not
  `house`, and an edit call is held once the session is contaminated (the
  old act rule). relay cannot follow labels outside itself, so injected
  text written into the operator's reminders would be read back labelled
  only private, and a clean session would take it in clean. Only relay's
  own `write` keeps labels, so only `write` is free whatever the labels.
  relay's registry, routine and dead-letter tools are therefore `write`,
  not edit. A tool that overwrites or deletes with no history is `act`.
  The name is in one constant in each repo (`trifecta.Edit`,
  `relay.config/edit-class`) and one doc paragraph, since it may change
  again.
- An edit tool counts for quarantine (`core/quarantines?`), as act and
  egress do, so a role holding rem still has contaminated memos
  quarantined.
- agentloop keeps `trifecta.Decide(labels, tool)` with its signature, as
  the rule for a call no consent covers, and adds `DecideCall(labels,
  tool, input)` for the whole rule. The quarantined reader, which another
  workstream is changing, calls Decide unchanged and never needed consent
  (its inner tools are read).
- Consent values compare after JSON normalisation and no further: a
  number by its literal (1 is not 1.0), a string character for character.
  This fails closed: a near miss is held, never allowed. Input that names
  a key twice anywhere matches nothing, since the tool could read either
  value. An empty rule is refused in relay.edn and in the request, since
  it would cover every call. Consent beside always-hold is refused too.
  An empty :consent vector is allowed and means no rules.
- The rule's index goes on agentloop's decision entry as `consent`.
- A preview is stored as a separate `hold/previewed` event after
  `hold/opened`, under its own labels (what it reported, the tool's static
  labels and the session's), so the hold's own labels stay the session's.
- The preview runs in the shell (dispatch) after the harness returns,
  only for holds relay has not recorded yet, with a 30 second limit. A
  failed preview opens the hold with a note saying so.
- In a conversation relay runs the preview in the proxy, only for a
  request it passes to the client, and rewrites that one line. The
  preview item carries `_meta {"relay/preview": true}` both on the
  toolCall.content item and on its inner text block, so a client may read
  either. The permission/asked event records the preview.
- "Links whose domain the operator has never approved" is computed from
  the log: hosts of links in previews of holds the operator allowed and
  of conversation calls the client allowed. Hosts are compared lower
  case, with fullwidth dots folded, nothing else (babashka has no IDNA),
  so a lookalike host is always new.
- In the ACP markdown, characters that draw as nothing become \u{XXXX},
  and lookalikes are named, since the preview is text an outsider may have
  written and it is shown to the operator for a decision.
- The staging reminder tools (remind-demo, fail-demo) are edit, which
  keeps every scenario that used them as act unchanged.
- Pentests that relied on routine_write being held now check that it runs
  and carries the session's labels, or use a new act stand-in,
  campaign_send (with consent and a preview). The pentest env declares
  rem as edit.
- The new pentest scenarios are named `consent-*`, since s13 and s14 are
  taken and other workstreams may add numbers.

## Deploy note for Kira

Your live relay.edn declares `rem` as `:act`. After this deploys, every
rem call waits for you (relay holds), whatever the session has seen,
where today it waits only once the role is contaminated. That is the safe
direction, so nothing breaks, but it will ask a lot. If none of your
lists is shared with other people, change it to `:edit`, which behaves as
`:act` did before:

    "rem" {:class :edit :labels ["private"] ...}

If a list is shared, keep `:act` and add `"contaminated"` to rem-read's
labels. relay's own role, routine and dead-letter tools become `write` by
themselves: a contaminated role can now add a role or write a routine
without asking, and what it writes keeps its labels. Deploy agentloop
with relay: relay's requests now name the class edit and may carry
`consent`, which an older agentloop refuses.

## Log

- 2026-10-18: built in agentloop and relay.
- Codex review, round 1: (a) relay coerced quoted numbers in every
  tool's input after agentloop's consent match, so a call could run on a
  value neither consent nor preview saw. Fixed: only relay's own tools
  are coerced. (b) The ACP preview was live markdown: an image link in a
  campaign body would be fetched by an image-rendering client before
  approval, and an escaped @ or a character reference hid a link's host.
  Fixed: the preview is fenced, and link-hosts also reads decoded text.
- Round 2: the lookalike and new-host notes quoted preview text outside
  the fence. Fixed: the notes are fenced too.
- Round 3: consent matched {"group":"Kira only","Group":"Everyone"},
  which a case-insensitive decoder (Go's encoding/json) reads as
  Everyone. Fixed in agentloop: keys that collide under case folding, at
  any depth, match nothing. Three rounds each found an in-scope defect,
  so per the review rule the loop stops here and goes to Kira; no fourth
  round was run.
