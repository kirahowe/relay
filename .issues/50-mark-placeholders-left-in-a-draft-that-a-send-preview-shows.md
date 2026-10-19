# Mark placeholders left in a draft that a send preview shows

## Context

Found while building the concert email eval (relay#46). The send binds to
one stored draft version. In the live Opus runs the hub saved a draft with
`Photo: [Photographer]`, asked the operator who took the photo, and
proposed the send of that version. If the operator approves the send
without first answering, 287 people get "[Photographer]".

The preview shows the stored text, so the operator can see it. A
placeholder in square brackets is easy to miss in a long body, though.

## Acceptance criteria

- [x] The approval screen, the ACP permission request and the workspace pane mark a `[Placeholder]` left in a preview's body
- [x] The mark is computed by relay from the preview content, never from the model

## Plan

relay already marks links to hosts never approved in a preview. A
placeholder mark could sit beside that, using the same pattern the
placeholders eval grades (a single-bracketed phrase that is not a ref).

## Log

Done 2026-10-19: relay.text/placeholders finds single-bracketed phrases that are not refs, links or checkboxes. render/preview and acp/preview-markdown list them as still to fill in; the pane shows the preview markdown, so it shows them too. Unit and property tests in consent_test.
