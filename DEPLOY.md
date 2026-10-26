# Deploying the house

Notes for moving your live relay to the house. They are written from the
code on `main` and README's Upgrading section; nothing here was run
against your live data.

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

## Before `bb deploy`

1. See what the new relay refuses in your live settings. From a checkout
   of the new `main` (a fresh jj workspace outside `~/Sync` is fine), run
   it against your live data directory. `relay status` only reads: it
   folds your log with the new code, which is a useful check in itself,
   and it writes nothing.

   ```sh
   <new relay>/bin/relay --data <live data> status
   ```

   It names the first problem and stops. The new relay refuses settings
   the old one requires (`:notify` above all), so one file cannot please
   both. Fix the problems in a copy, not in the live file, and check the
   copy in a scratch directory that holds only it:

   ```sh
   mkdir -p ~/relay-edn-check
   cp <live data>/relay.edn ~/relay-edn-check/relay.edn
   cp <live data>/relay.edn ~/relay.edn.old      # for a rollback
   # edit ~/relay-edn-check/relay.edn, then:
   <new relay>/bin/relay --data ~/relay-edn-check status
   ```

   Repeat until it prints the status. With no log there it cannot check
   role names; that check comes after the deploy (step 4 below).

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
   :house {:mounts ["/Users/kira/notes"]}
   :grants {"hub" {:may-add true :tools [...] :shell true}}
   ```

   relay refuses a mount that is, holds or sits inside the data
   directory, your home directory or a place for secrets.

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

3. Run these before the deploy:

   ```sh
   bb service stop              # a live conversation is a wake the deploy would wait for
   container system start       # bb test runs a live test against the running container
   ```

## The deploy

`bb deploy` as usual. Its last step is `relay status` against your live
data, and the live `relay.edn` is still the old one, so that step
fails. The deploy says that only `relay status` failed, gives the `rm`
command that lifts the pause, and leaves relay paused. That is the
window for the rest:

4. Put the new file in place and check it against your live data:

   ```sh
   cp ~/relay-edn-check/relay.edn <live data>/relay.edn
   bin/relay --data <live data> status
   ```

   This time it can check role names. If it still says the steward is
   missing, `:dead-letter-handler` or `:default-role` does not name
   `hub`.

5. One-time steps, still paused:

   ```sh
   relay house build                       # the steward's shell image; needs the network
   make -C ~/code/projects/webtool build   # bb deploy does not touch webtool
   security add-generic-password -a "$USER" -s relay-brave -w   # the Brave Search key, if not there yet
   ```

   webtool needs its new build: the fetch rule relies on it reporting a
   redirect instead of following it. Move its checkout to its `main`
   first. Its `webtool.json` names the key command:
   `"search": {"backend": "brave", "api_key_command": ["security", "find-generic-password", "-s", "relay-brave", "-w"]}`.
   agentloop and relay-tui were built by the deploy.

   Before you change hub, save the log as it is. If you roll back later,
   it holds hub's old title and instructions:

   ```sh
   relay --data <live data> log --json > ~/relay-log-before-house.jsonl
   ```

   Give hub the new text, and make its text public so the scout can
   answer it. A role whose id was written in private cannot send a memo
   to a public role at all. Public text may go out in a web search, so
   keep anything private in hub's notes and facts, not its
   instructions:

   ```sh
   relay update hub --public --title "<hub's title>" --instructions < setup/steward.md
   ```

   Add a scout, and the daily note's skill and routine for hub. The
   routine text names the skill's path, so point it at hub's:

   ```sh
   relay add scout --public --title Scout --reports-to hub < setup/scout.md
   relay put skills/hub/daily-note.md < setup/daily-note/skill.md
   sed 's|skills/steward/|skills/hub/|' setup/daily-note/routine.md \
     | relay routine hub daily-note --schedule "daily 07:10"
   ```

   If an older role did research with reports, it has nothing to do now;
   remove it or leave it idle.

6. Lift the pause with the `rm` command the deploy printed, then start
   the service again:

   ```sh
   bb service start
   ```

7. Subscribe to the agent's calendar: `relay calendar url` prints the
   address, with a token that opens the calendar and nothing else. Add it
   in your calendar app as a subscription.

8. To see the first note without waiting: `relay routine run daily-note`,
   then `relay note`, then `relay note now`.

## Later, as ops

- The agent's own mail address and its provider. Then add an `:email`
  channel to `:messages` and routes for it, and a `:pop3` source in
  `:ingest` with your provider's `:authserv-id`, so your replies by mail
  are verified as yours.
- A domain for that address.

Neither blocks the deploy: until then messages reach you by push and in
relay-tui, and you reply from the ready view or with `relay reply`.

## What `bb deploy` does not do

`bb deploy` moves and builds relay, agentloop and relay-tui
(`relay.deploy/builds` builds only agentloop and relay-tui) and runs
`bb test` and `relay status`. It does not start Apple's container
service, build the steward's image (`relay house build`), move or build
webtool, or restart `relay serve`. Steps 3, 5 and 6 cover those by hand.

## Rolling back

`bb rollback` moves relay, agentloop and relay-tui back and rebuilds
them, as before. Put `~/relay.edn.old` back in place first. The old
relay requires `:notify` and does not know `:messages`, and the
rollback ends with `relay status`, which fails on the new file and
leaves relay paused.

webtool is not rolled back. It stays on its new `main`. The old
`:verifier` runs webtool's `check` command, and that command is still
on webtool's `main`, so the old relay works with it.

A rollback only works before the new relay has written to the log. The
old relay stops on any event kind it does not know ("This version of
relay does not know event type ..."), and refuses to read the whole log
past it. The new relay writes these kinds, which the old one has never
seen:

`message/created`, `message/delivered`, `message/failed`,
`message/replied`, `ingest/polled`, `ingest/failed`,
`prepared/recorded`, `tool/broken`, `tool/working`,
`settings/misnamed`, `digest/composed`, `digest/part-written`,
`change/reverted`, `offer/declined`, `offer/outcome`,
`suggestion/dismissed`, `github/announced`, `calendar/added`,
`calendar/changed`, `calendar/removed`, `files/moving`, `files/moved`,
`files/undone`.

Nearly every tick writes one of them: any message to you is a
`message/created`, and so are hub's memos to you. In practice, once the
new relay has run with the pause lifted, the old relay cannot read your
log, and the log is append-only. The way back from then on is forward: a
fix on the new relay.

Steps 4 and 5 run while relay is still paused. The commands they use
write only `role/added`, `role/updated`, `doc/put` and
`routine/written` events. The old relay can read all of these, so you
can still roll back from inside that window. But the events stay in
the log, and the old relay acts on them:

- It runs hub's `daily-note` routine every day at 07:10.
- It wakes hub on the instructions from `setup/steward.md`, which name
  tools the old relay does not have (`daily_note_write`, `sh`,
  `files_move`, the calendar tools).
- It keeps the public `scout` role.

Each of those wakes is spent on tools that do not exist. To roll back
from inside the window:

1. Put `~/relay.edn.old` back as `<live data>/relay.edn`.
2. Run `bb rollback`. It rebuilds the old relay, runs `relay status`
   and lifts the pause.
3. Straight away, with the old relay, undo step 5:

   ```sh
   relay unroutine hub daily-note
   relay remove scout
   ```

4. Give hub its old title and instructions back. They are in the last
   `role/added` or `role/updated` event for hub in the log you saved
   before step 5:

   ```sh
   jq -rs '[.[] | select((.type == "role/added" or .type == "role/updated")
                         and .data.id == "hub" and .data.instructions != null)]
           | last | .data.instructions' ~/relay-log-before-house.jsonl > ~/hub-old.md
   jq -rs '[.[] | select((.type == "role/added" or .type == "role/updated")
                         and .data.id == "hub" and .data.title != null)]
           | last | .data.title' ~/relay-log-before-house.jsonl
   relay update hub --title "<the title it printed>" --instructions < ~/hub-old.md
   ```

   Read `~/hub-old.md` before you run the update. Add `--public` only
   if hub's text was public before the deploy. Without it, hub's text
   goes back to private.
5. Start the service again, which step 3 stopped:

   ```sh
   bb service start
   ```

The doc `skills/hub/daily-note.md` stays. Nothing reads it once the
routine is gone.
