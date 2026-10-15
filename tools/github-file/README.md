# github-file

Prints one file from a public GitHub repository at one ref. The operator
pins the repository and the ref in `relay.edn`, and the role passes only a
path, so a role granted this tool can read that branch and nothing else.

It is a babashka script and needs no build. It calls GitHub's contents API
without a token, which allows 60 requests an hour from one address, so it
only suits public repositories.

## Setup

Give the tool a name per repository and ref, with the pinned flags in
`:command`:

```clojure
:tools
{"slk-reviews" {:class :read
                ;; Anyone who can push to the branch writes what it returns.
                :labels ["contaminated"]
                :description "Upstream review reports for kirahowe/slk. Run with [\"help\"]."
                :command ["/path/to/relay/tools/github-file/github-file"
                          "--repo" "kirahowe/slk" "--ref" "claude/upstream-review"]}}
```

## Commands

- `help` prints the commands and the pinned repository and ref.
- `get <path>` prints the file at `<path>`, such as `state.json` or
  `reports/2026-10-16.md`. A path is relative, with no `.` or `..` parts.

A missing file prints `No <path> at <ref> in <repo>.` and exits 1. A path
the tool refuses, or a flag after the command, exits 2 without a request.
