# Moving to the house and to Homebrew

Notes for moving your live relay to the house, and from launchd jobs that
run `~/code/projects/relay` to a Homebrew install that runs as one
service. They are written from the code on `main` and README's Setup and
Upgrading sections; nothing here was run against your live data. The
formulas were installed from a local head and `relay run` was tried on
staging data, then uninstalled.

## What changed

relay no longer holds a call, asks for approval or keeps trust levels,
reports, a ledger, a quarantined reader or a workbench. Roles work inside
the house without asking you, and the walls are in code: no tool can act
as you, a house role has no `egress` tool, the steward's shell is a
container with no network, mail goes only to you, and scouts see nothing
private and fetch only what the web showed them. What relay tells you is
now a message that `:messages` routes to channels (a push now, mail
later), with a daily note in place of the morning brief. New are the
mailbox and other ingest sources, the steward's shell, file moves you can
undo, the agent's own calendar, the ready view and replies.
`docs/architecture.md` draws the whole system.

relay also installs from your tap now. `brew install --HEAD
kirahowe/tap/relay` brings agentloop, relay-tui and webtool with it, and
`brew services start relay` runs `relay run`, which runs `relay serve`
and `relay tick` every minute. `bb deploy`, `bb rollback`, `bb service`,
the pause file and the relay.tick and relay.serve plists are gone. The
checkouts under `~/code/projects` become where you develop, on `main`.

## Before the switch

1. See what the new relay refuses in your live settings. From a checkout
   of the new `main` (a fresh jj workspace outside `~/Sync` is fine), run
   it against your live data directory. `relay status` only reads: it
   folds your log with the new code, which is a useful check in itself,
   and it writes nothing.

   ```sh
   <new relay>/bin/relay --data <live data> status
   ```

   It lists every problem it finds, numbered, each with its fix. The
   new relay refuses settings the old one requires (`:notify` above
   all), so one file cannot please both. Fix the problems in a copy, not in the live file, and check the
   copy in a scratch directory that holds only it:

   ```sh
   mkdir -p ~/relay-edn-check
   cp <live data>/relay.edn ~/relay-edn-check/relay.edn
   cp <live data>/relay.edn ~/relay.edn.old      # for a rollback
   # edit ~/relay-edn-check/relay.edn, then:
   <new relay>/bin/relay --data ~/relay-edn-check status
   ```

   Repeat until it prints the status. With no log there it cannot check
   role names, and it says so; that check comes after the switch (step 6
   below). Do not run `relay init` there.

2. Make these changes in the copy. Each removed setting and its
   replacement is in README's Upgrading table:

   | Old setting | Where | In the new file |
   |---|---|---|
   | `:notify {:kind :ntfy :url ... :topic ... :token-command ...}` | top level | `:messages` with a push channel, below |
   | `:brief` | top level | `:daily-note`, below, and your own calendar as an `:ics` source in `:ingest` |
   | `:verifier` | top level | remove it |
   | `:chat-client` | top level | rename it `:client` |
   | `:format` other than 1 | top level | leave it out, or set it to 1 |
   | `:reports`, `:quarantine`, `:workbench` | a role's grants | remove them |
   | `:reader`, `:workbench` | a harness | remove them |
   | `:class :act` | a tool | `:class :edit` for a tool that only drafts, or remove the tool |
   | `:always-hold`, `:consent`, `:preview` | a tool | remove them |
   | an `:egress` tool with a free-text parameter, such as the old `"web"` with `query` | a tool | name the parameter in `:queries`, such as `:queries ["query"]`, and grant the tool to the scout only |
   | a calendar or mail tool labelled `contaminated`, such as the old `"cal"` | a role's `:tools` | take it off hub: your calendar now comes in through `:ingest` |

   Your main role is `hub`, and the new default is `"steward"`, so name
   it in both places:

   ```clojure
   :dead-letter-handler "hub"
   :serve {:websocket {:port 8765
                       :token-command ["security" "find-generic-password" "-s" "relay-websocket" "-w"]
                       :default-role "hub"}}
   ```

   Messages: start with the push channel only. Your old `:notify` keys
   carry over, with `:kind :push :via :ntfy`. Add the email channel
   later, once the agent has its own address.

   ```clojure
   :messages {:channels {"phone" {:kind :push :via :ntfy
                                  :url "<the url from your old :notify>"
                                  :topic "<the topic from your old :notify>"
                                  :token-command <the token-command from your old :notify>}}
              :routes [{:channels ["phone"]}]}
   ```

   Copy the url, topic and token-command from your old `:notify`.

   The daily note, written by hub:

   ```clojure
   :daily-note {:at "07:30" :role "hub"}
   ```

   Add `:grants "scout/grant-search"` to it once a scout has a routine of
   that name, so the note can say when the search did not run.

   The steward's shell: the folders hub may read, read-only, and the
   grant. Add `:files` only for folders you want it to tidy.

   ```clojure
   :house {:mounts [{:path "/Users/kira/notes" :trusted true}]}
   :grants {"hub" {:may-add true :tools [...] :shell true}}
   ```

   `:trusted true` says the folder holds only your own writing. A plain
   path counts as outside text: what hub reads there is marked as coming
   from outside, and a change it shapes is flagged in the daily note.
   Leave it off for a folder such as Downloads.

   relay refuses to mount your home directory itself or a folder that
   holds it, and the data directory or a place for secrets, or anything
   inside them. Folders inside your home directory, such as
   `/Users/kira/notes`, are fine.

   Take the old `"cal"` tool, and any other tool labelled
   `contaminated`, off hub's `:tools`. A tool's label has no source, so
   everything hub writes after a call to it is marked as drawing on that
   tool, and your calendar now comes in through `:ingest` below.

   The scout's tools: only a public role may have an `egress` tool, and
   an `egress` tool must be an `:envelope` tool with `:parameters`, that
   either fetches a `url` or searches with free text named in
   `:queries`. Copy the `"web"` tool from `setup/relay.example.edn`,
   point its `:command` at your webtool and its `webtool.json`, and grant
   it to the scout only:

   ```clojure
   "scout" {:public true :tools ["web"]}
   ```

   A web tool that hub held before (through `:quarantine`, say) comes
   off hub's grants.

   Your own calendar, which the brief used to read, comes in as memos:

   ```clojure
   :ingest {"calendar" {:kind :ics :url "<your calendar's private address>" :to "hub"}}
   ```

   Leave the `:pop3` mailbox and `:github` sources out until you want
   them; README's "The mailbox and other sources" has their shape.


## The switch

From step 3 on, `relay` is the installed one (`/opt/homebrew/bin/relay`,
which comes before `~/.local/bin` on your PATH), and every command names
your live data directory with `--data <live data>`. The old checkouts
stay where they are until step 9, so the way back is open until then.

3. Push `main` in relay, agentloop, relay-tui and webtool, and the tap's
   `main` (`~/code/projects/homebrew-tap`). The formulas build from
   GitHub.

4. Stop the old jobs. Stopping relay.serve ends every live conversation.
   Keep the plists for now, in case you roll back:

   ```sh
   launchctl bootout gui/$(id -u)/relay.tick
   launchctl bootout gui/$(id -u)/relay.serve
   ```

   Wait for running wakes to finish: `~/code/projects/relay/bin/relay
   --data <live data> status` lists them under Recent wakes. Then save the
   log as it is. If you roll back, it holds hub's old title and
   instructions:

   ```sh
   ~/code/projects/relay/bin/relay --data <live data> log --json > ~/relay-log-before-house.jsonl
   ```

5. Install:

   ```sh
   brew trust kirahowe/tap
   brew install --HEAD kirahowe/tap/relay
   container system start       # for relay house build below
   ```

   If your live relay.edn's harness `:bin` or `:client` names a binary
   in `~/code/projects`, point it at `/opt/homebrew/bin/agentloop` and
   `/opt/homebrew/bin/relay-tui` in the copy, and the `"web"` tool's
   `:command` at `/opt/homebrew/bin/webtool`. Those paths stay put across
   upgrades. tools/rem and tools/cal are not in the formula: macOS ties
   their Reminders and Calendar permissions to the built binary, so keep
   pointing at the copies you built in `~/code/projects/relay/tools`.

6. Put the new file in place and check it against your live data:

   ```sh
   cp ~/relay-edn-check/relay.edn <live data>/relay.edn
   relay --data <live data> status
   ```

   This time it can check role names. If it still says the steward is
   missing, `:dead-letter-handler` or `:default-role` does not name
   `hub`.

7. One-time steps, before the service runs:

   ```sh
   relay --data <live data> house build   # the steward's shell image; needs the network
   security add-generic-password -a "$USER" -s relay-brave -w   # the Brave Search key, if not there yet
   ```

   The fetch rule relies on webtool reporting a redirect instead of
   following it, which the installed webtool does. Its `webtool.json`
   names the key command:
   `"search": {"backend": "brave", "api_key_command": ["security", "find-generic-password", "-s", "relay-brave", "-w"]}`.

   Give hub the new text. It stays private: hub may write to the scout
   all the same, and the scout learns only hub's id, so it can answer.
   The setup texts are in the installed copy:

   ```sh
   cd "$(brew --prefix relay)/libexec"
   relay --data <live data> update hub --title "<hub's title>" --instructions < setup/steward.md
   ```

   Add a scout, and the daily note's skill and routine for hub. The
   routine text names the skill's path, so point it at hub's:

   ```sh
   relay --data <live data> add scout --public --title Scout --reports-to hub < setup/scout.md
   relay --data <live data> put skills/hub/daily-note.md < setup/daily-note/skill.md
   sed 's|skills/steward/|skills/hub/|' setup/daily-note/routine.md \
     | relay --data <live data> routine hub daily-note --schedule "daily 07:10"
   ```

   If an older role did research with reports, it has nothing to do now;
   remove it or leave it idle.

8. Start the service. It finds the data directory in
   `~/.config/relay/config.edn`, as the old jobs did not, so check that
   the file names `<live data>`:

   ```sh
   brew services start relay
   relay status
   tail -f "$(brew --prefix)/var/log/relay.log"   # serve's startup, and the tick when it wakes someone
   ```

   Subscribe to the agent's calendar: `relay calendar url` prints the
   address, with a token that opens the calendar and nothing else. Add
   it in your calendar app as a subscription. To see the first note
   without waiting:

   ```sh
   relay routine run hub daily-note
   relay note
   relay note now
   ```

9. Once you are happy with it, clear the old setup away:

   ```sh
   rm ~/Library/LaunchAgents/relay.tick.plist ~/Library/LaunchAgents/relay.serve.plist
   rm ~/.local/bin/relay ~/.local/bin/agentloop ~/.local/bin/relay-tui
   rm -f "$HOME/Library/Application Support/relay/paused" \
         "$HOME/Library/Application Support/relay/deploys.edn" \
         "$HOME/Library/Application Support/relay/deploy.lock"   # whichever are there; ntfy's config stays
   ```

   Then the checkouts become single development checkouts on `main`.
   Each of `~/code/projects/{relay,agentloop,relay-tui,webtool}`:
   `jj new main`. Forget the workspaces under `~/code/projects/.next`
   (`jj workspace forget <name>` from the repo, then move the directory
   to the Trash), and remove the `relay -> house` link there.

## Upgrading from now on

```sh
# push main in each repo that changed, then:
brew upgrade --fetch-HEAD relay agentloop relay-tui webtool
brew services restart relay
relay house build     # when the steward's image changed
relay status
```

The restart ends every live conversation. Running wakes carry on, on
the code they started with: they run in a process group of their own,
so the restart does not touch them. Nothing is paused and nothing is
tested first: run `bb verify` in the checkout before you push.

## Later, as ops

- The agent's own mail address and its provider. Then add an `:email`
  channel to `:messages` and routes for it, and a `:pop3` source in
  `:ingest` with your provider's `:authserv-id`, so your replies by mail
  are verified as yours.
- A domain for that address.

Neither blocks the switch: until then messages reach you by push and in
relay-tui, and you reply from the ready view or with `relay reply`.

## Rolling back

Until step 9 the old checkouts and plists are untouched, so the way back
is to stop the service and load the old jobs again:

1. `brew services stop relay`.
2. Put `~/relay.edn.old` back as `<live data>/relay.edn`.
3. Load the old jobs:

   ```sh
   launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/relay.tick.plist
   launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/relay.serve.plist
   ```

4. `brew uninstall relay agentloop relay-tui webtool`, so the old
   checkouts' builds are first on PATH again (`~/.local/bin`).

A rollback only works before the new relay has written to the log. The
old relay stops on any event kind it does not know ("This version of
relay does not know event type ..."), and refuses to read the whole log
past it. The new relay writes these kinds, which the old one has never
seen:

`message/created`, `message/delivered`, `message/failed`,
`message/replied`, `ingest/polled`, `ingest/failed`,
`prepared/recorded`, `tool/broken`, `tool/working`,
`settings/misnamed`, `digest/composed`, `digest/part-written`,
`change/reverted`, `offer/accepted`, `offer/declined`, `offer/outcome`,
`suggestion/dismissed`, `github/announced`, `calendar/added`,
`calendar/changed`, `calendar/removed`, `files/moving`, `files/moved`,
`files/undone`.

Nearly every tick writes one of them: any message to you is a
`message/created`, and so are hub's memos to you. In practice, once the
service has run, the old relay cannot read your log, and the log is
append-only. The way back from then on is forward: a fix on the new
relay.

Steps 6 and 7 run before the service starts. The commands they use
write only `role/added`, `role/updated`, `doc/put` and
`routine/written` events. The old relay can read all of these, so you
can still roll back from there. But the events stay in the log, and the
old relay acts on them:

- It runs hub's `daily-note` routine every day at 07:10.
- It wakes hub on the instructions from `setup/steward.md`, which name
  tools the old relay does not have (`daily_note_write`, `sh`,
  `files_move`, the calendar tools).
- It keeps the public `scout` role.

So after the rollback above, with the old relay
(`~/code/projects/relay/bin/relay`), undo step 7:

```sh
~/code/projects/relay/bin/relay --data <live data> unroutine hub daily-note
~/code/projects/relay/bin/relay --data <live data> remove scout
```

and give hub its old title and instructions back, from the log you saved
in step 4:

```sh
jq -rs '[.[] | select((.type == "role/added" or .type == "role/updated")
                      and .data.id == "hub" and .data.instructions != null)]
        | last | .data.instructions' ~/relay-log-before-house.jsonl > ~/hub-old.md
jq -rs '[.[] | select((.type == "role/added" or .type == "role/updated")
                      and .data.id == "hub" and .data.title != null)]
        | last | .data.title' ~/relay-log-before-house.jsonl
~/code/projects/relay/bin/relay --data <live data> update hub --title "<the title it printed>" --instructions < ~/hub-old.md
```

Read `~/hub-old.md` before you run the update. Add `--public` only if
hub's text was public before. Without it, hub's text goes back to
private. The doc `skills/hub/daily-note.md` stays; nothing reads it
once the routine is gone.
