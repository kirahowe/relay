# Tidy the deploy cleanup message and tool start failures

## Context

From the review of relay#31 and relay#32 (zwtlzxwt, otvwwqny, 2026-10-17). None breaks a guarantee.

- deploy_run.clj `forget-all!`: if `fs/delete-tree tmp` throws, the message about stuck workspaces is lost, and the interrupt hook catches the exception and can still say "Nothing was changed". Wrap the delete in try. The comment "one never made just fails to be forgotten" is also wrong: jj exits 0 with a warning for an unknown workspace.
- The envelope runner doesn't send a start failure through `failed`. The model sees "Cannot run program X" instead of "The tool X failed.", and nothing goes to the operator's log. The text quotes only config.
- When base-env's LANG names a locale that isn't installed, the perl wrapper prints locale warnings on stderr, which only shows in failure logs. A tool's argv[0] is now the resolved absolute path rather than the bare name.

## Acceptance criteria

- [ ] A failed temp-dir delete still reports stuck workspaces.
- [ ] An envelope tool that cannot start reports like a plain one.
- [ ] No locale noise from the wrapper.

## Plan

## Log
- (a) forget-all! now wraps the temp-dir delete in try and returns both the stuck-workspace commands and a line naming the directory it could not delete. The comment now says jj exits 0 with a warning for an unknown workspace.
- (b) run-envelope-tool catches a start failure from run-limited and sends it through `failed`, the same path as an exit or timeout: the model sees "The tool X failed.", stderr gets "X: could not start: ...".
- (c) Chose PERL_BADLANG=0 in the wrapper's own env (tools/group-env), deleted by the perl script before exec. Reason: dropping LANG from base-env would change the locale every tool and harness sees (UTF-8 handling), and LC_ALL=C in the wrapper env would leak to the tool unless also removed; this changes nothing the tool sees. Test runs the wrapper with LANG=xx_YY.UTF-8 and checks stderr is empty, the command still has that LANG and does not see PERL_BADLANG.
