# Let the personal agent move and rename my files

## Context

Kira's request, 2026-10-19:

> I'd like my personal agent to be able to move and rename files on my
> computer. The example from tonight is that I got my tax returns filed and
> downloaded a whole bunch of confirmations, I'd like my agent to rename and
> move all of those files to the right places. I also had to download a whole
> bunch of bank statements, I'd like my agent to be able to rename and move
> those as well.

Both examples are batches of freshly downloaded financial documents. The agent
has to work out what each file is, give it a sensible name, and file it where
it belongs.

## Decisions (Kira, 2026-10-19)

- **Folder access is granted per role.** Kira gives specific roles access to
  specific folders. relay's guarantees come from roles having different
  permissions and talking to each other only mechanically, so file access
  works the same way: a role sees only the folders it was granted.
- **The agent infers the organization.** It reads the existing files and
  folder structure in its granted folders and works out where things go and
  how they are named. Kira does not write the rules down.
- **Moves and renames go to Kira first.** The agent proposes the batch and
  Kira approves it before anything changes on disk.
- **The role has no egress.** A role that reads Kira's files runs in a
  sandbox with no way to send anything out. With no egress it does not matter
  what it reads or processes, including tax returns and bank statements.

## Still to settle in the design

- A move must never overwrite or delete an existing file.
- A batch should be undoable from a log of what moved where.

## Acceptance criteria

- [ ] Kira can grant a role read and write access to specific folders, and
      the role cannot reach any path outside them.
- [ ] The role has no egress: no network and no tool that sends data out.
- [ ] Given a folder of downloaded tax confirmations or bank statements, the
      role reads the existing structure and proposes new names and
      destinations that follow it.
- [ ] The proposed batch is held for Kira's approval, and nothing moves until
      Kira approves it.
- [ ] No move or rename can overwrite or delete an existing file.
- [ ] Kira can undo a batch.

## Plan

## Log
