# Let bb deploy stop and restart relay serve itself

## Context

A deploy waits for every running wake, and each live conversation in
relay serve is one. On 2026-10-23 Kira deployed with a relay chat
conversation still open in the service. Killing the client did not end
it, and the deploy sat on "Waiting for hub (conversation)". The README
says to stop the service before `bb deploy` and start it after, and
`bb service stop|start` (added the same day) makes that shorter.

The idea: `bb deploy` stops the service itself, after saying that this
ends every live conversation, and starts it again when the deploy is
done or rolled back. The service has to be restarted after a deploy
anyway, since it runs the code it started with.

Needs Kira: it touches a launchd job from the deploy.

Related: there is no command for ending one conversation from a
terminal. acp-tui's ctrl+s or stopping the service are the only ways.

## Acceptance criteria

- [ ] 

## Plan

## Log
