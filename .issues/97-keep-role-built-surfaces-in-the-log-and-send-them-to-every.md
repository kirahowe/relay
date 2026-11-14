# Keep role-built surfaces in the log and send them to every client

## Context

Part of relay#93. relay plays A2UI's "agent" toward clients. A role
authors a surface through a tool, relay checks it against relay's catalog,
keeps it as log events and replays it to each client. Every string in it
goes out through the path cards use, so labels, hidden characters and
links (relay#94) are handled once.

## Acceptance criteria

- [ ] To be written once relay#96 shows what the renderer needs and
  relay#98 is decided.

## Plan

## Log
