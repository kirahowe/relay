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
   role names, and it says so; that check comes after the deploy (step 4
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

3. Run these before the deploy:

   ```sh
   bb service stop              # a live conversation is a wake the deploy would wait for
   container system start       # bb test runs a live test against the running container
   ```

   `bb deploy` now moves and builds webtool too, so its live checkout
   (`~/code/projects/webtool`) needs an empty working copy on a single
   commit, as relay's, agentloop's and relay-tui's do.

## The deploy

From here on, `bin/relay` means the live checkout's
(`~/code/projects/relay/bin/relay`), and every command names your live
data directory with `--data <live data>`, so neither another relay on
PATH nor `~/.config/relay/config.edn` decides which relay runs on which
data.

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
   bin/relay --data <live data> house build   # the steward's shell image; needs the network
   security add-generic-password -a "$USER" -s relay-brave -w   # the Brave Search key, if not there yet
   ```

   `bb deploy` builds the image itself when the live `relay.edn` has
   `:house`, but this first time the live file is still the old one, so
   build it by hand now. Later deploys do it for you. webtool was moved
   to its `main` and built by the deploy; the fetch rule relies on that
   build reporting a redirect instead of following it. Its `webtool.json`
   names the key command:
   `"search": {"backend": "brave", "api_key_command": ["security", "find-generic-password", "-s", "relay-brave", "-w"]}`.
   agentloop, relay-tui and webtool were built by the deploy.

   Before you change hub, save the log as it is. If you roll back later,
   it holds hub's old title and instructions:

   ```sh
   bin/relay --data <live data> log --json > ~/relay-log-before-house.jsonl
   ```

   Give hub the new text. It stays private: hub may write to the scout
   all the same, and the scout learns only hub's id, so it can answer:

   ```sh
   bin/relay --data <live data> update hub --title "<hub's title>" --instructions < setup/steward.md
   ```

   Add a scout, and the daily note's skill and routine for hub. The
   routine text names the skill's path, so point it at hub's:

   ```sh
   bin/relay --data <live data> add scout --public --title Scout --reports-to hub < setup/scout.md
   bin/relay --data <live data> put skills/hub/daily-note.md < setup/daily-note/skill.md
   sed 's|skills/steward/|skills/hub/|' setup/daily-note/routine.md \
     | bin/relay --data <live data> routine hub daily-note --schedule "daily 07:10"
   ```

   If an older role did research with reports, it has nothing to do now;
   remove it or leave it idle.

6. Lift the pause with the `rm` command the deploy printed, then start
   the service again:

   ```sh
   bb service start
   ```

7. Subscribe to the agent's calendar: `bin/relay --data <live data> calendar url` prints the
   address, with a token that opens the calendar and nothing else. Add it
   in your calendar app as a subscription.

8. To see the first note without waiting:

   ```sh
   bin/relay --data <live data> routine run hub daily-note
   bin/relay --data <live data> note
   bin/relay --data <live data> note now
   ```

## Later, as ops

- The agent's own mail address and its provider. Then add an `:email`
  channel to `:messages` and routes for it, and a `:pop3` source in
  `:ingest` with your provider's `:authserv-id`, so your replies by mail
  are verified as yours.
- A domain for that address.

Neither blocks the deploy: until then messages reach you by push and in
relay-tui, and you reply from the ready view or with `relay reply`.

## What `bb deploy` does and does not do

`bb deploy` moves relay, agentloop, relay-tui and webtool, builds
agentloop, relay-tui and webtool with `make build`, and runs `bb test`
before and `relay status` after. When the live `relay.edn` has `:house`
it also builds the steward's image with `relay house build`. It checks
`container system status` first, and if Apple's container system is
not running it says so and how to start it, without starting it. It
does not restart `relay serve`. Steps 3, 5 and 6 cover the rest by
hand.

## Rolling back

`bb rollback` moves relay, agentloop, relay-tui and webtool back and
rebuilds them. It does not rebuild the steward's image. Put `~/relay.edn.old` back in place first. The old
relay requires `:notify` and does not know `:messages`, and the
rollback ends with `relay status`, which fails on the new file and
leaves relay paused.

webtool goes back with the rest, to where the deploy found it, so the
old `:verifier` gets the webtool it ran with.

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
3. Straight away, with the old relay (`bin/relay` in the live
   checkout, now rolled back), undo step 5:

   ```sh
   bin/relay --data <live data> unroutine hub daily-note
   bin/relay --data <live data> remove scout
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
   bin/relay --data <live data> update hub --title "<the title it printed>" --instructions < ~/hub-old.md
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
