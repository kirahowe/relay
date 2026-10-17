# Lock down the ntfy server

## Context

The relay topic is open on 127.0.0.1:2586 and on the tailnet, and ntfy accepts a publish over GET (`/relay/publish?message=`). Anyone who can reach the server can post to Kira's phone. The commented lockdown at the end of setup/ntfy/server.yml covers it: deny-all default, a read-only user for the phone, and a write-only token for relay supplied through `:token-file`. This needs Kira: the phone login in the ntfy app, and a change to live config.

## Acceptance criteria

- [ ] Uncomment and finish the lockdown in setup/ntfy/server.yml (auth-file, auth-default-access deny-all).
- [ ] A phone user with read-only access to the topic; Kira logs in on the phone in the ntfy app.
- [ ] A relay user with write-only access and a token, read by relay through `:token-file` in relay.edn.
- [ ] An unauthenticated GET or POST to the topic is refused; relay's own notification still arrives on the phone.
- [ ] README setup notes match the new config.

## Plan

## Log

- 2026-10-17: setup/ntfy/lockdown landed on main (nsmlykno). Its token is in the keychain item relay-ntfy (wusmqlow). Kira still has to run it; it needs the phone login.
