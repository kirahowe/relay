#!/bin/sh
# Run the ACP Test Compatibility Kit (agentclientprotocol/acp-tck) against
# `relay acp clerk`, in a fresh data directory, with agentloop behind it
# and a mock Anthropic API standing in for the model.
#
#   scripts/tck.sh            (or: bb tck)
#
# Needs Go, uv and a checkout of agentloop beside this repository. The
# script builds agentloop's mock provider into a temporary directory and
# runs the sibling checkout's bin/agentloop; it changes nothing in the
# agentloop checkout. The acp-tck commit and the requirements allowed to
# be SKIPPED are read from agentloop's own scripts/tck.sh, so the two runs
# hold relay to exactly what agentloop advertises. The JSON report goes to
# out/tck.json.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
agentloop_src=$(cd "$root/../agentloop" && pwd)
agentloop_tck="$agentloop_src/scripts/tck.sh"
cd "$root"

TCK_REF=$(sed -n 's/^TCK_REF=//p' "$agentloop_tck")
ALLOWED_SKIPS=$(awk '/^ALLOWED_SKIPS="/{on=1; next} on && /^"/{exit} on' "$agentloop_tck")
if [ -z "$TCK_REF" ] || [ -z "$ALLOWED_SKIPS" ]; then
	echo "tck: could not read TCK_REF and ALLOWED_SKIPS from $agentloop_tck" >&2
	exit 1
fi

# The sibling checkout's build, never the agentloop on PATH, which may be
# the live install.
if [ -x "$agentloop_src/bin/agentloop" ]; then
	agentloop_bin="$agentloop_src/bin/agentloop"
else
	echo "tck: agentloop is not built; run make -C ../agentloop build" >&2
	exit 1
fi

mkdir -p out
work=$(mktemp -d)
mock_pid=
cleanup() {
	if [ -n "$mock_pid" ]; then kill "$mock_pid" 2>/dev/null || true; fi
	rm -rf "$work"
}
trap cleanup EXIT INT TERM

go build -C "$agentloop_src" -o "$work/mockprovider" ./internal/cmd/mockprovider

"$work/mockprovider" >"$work/mock.url" 2>"$work/mock.err" &
mock_pid=$!
i=0
until [ -s "$work/mock.url" ]; do
	i=$((i + 1))
	if [ "$i" -gt 100 ]; then
		echo "tck: the mock provider did not start" >&2
		cat "$work/mock.err" >&2
		exit 1
	fi
	sleep 0.1
done
url=$(head -n 1 "$work/mock.url")

data="$work/data"
mkdir -p "$data"
cat >"$data/relay.edn" <<EDN
{:timezone "UTC"
 :default-harness :agentloop
 :harnesses {:agentloop {:kind :agentloop
                         :bin "$agentloop_bin"
                         :base-url "$url"
                         :model "mock"
                         :api-key-command ["printf" "mock-key"]
                         :retry {:retries 0}}}
 :notify {:kind :none}}
EDN
bin/relay init --data "$data" >/dev/null
echo "You are under test." | bin/relay add clerk --title Clerk --data "$data" >/dev/null

mkdir -p "$work/agent-cwd"
status=0
uvx --from "git+https://github.com/agentclientprotocol/acp-tck@$TCK_REF" acp-tck \
	--agent-cwd "$work/agent-cwd" \
	--cancel-prompt "MOCK-SLOW: keep busy until cancelled" \
	--report-json out/tck.json \
	-- "$root/bin/relay" acp clerk --data "$data" || status=$?

# relay must have ended every conversation the kit opened itself: none
# left running, and none reaped because its relay process died first.
running=$(bin/relay status --data "$data" | grep -c '\[running\]' || true)
reaped=$(bin/relay log --json --data "$data" | grep -c '"lost-wakes":\[[0-9]' || true)
if [ "$running" != 0 ] || [ "$reaped" != 0 ]; then
	echo "tck: problem: $running conversation(s) left running, $reaped reaped" >&2
	status=1
fi

python3 -I - out/tck.json "$ALLOWED_SKIPS" "$status" <<'PY'
import json, sys
report = json.load(open(sys.argv[1]))
allowed = set(sys.argv[2].split())
counts, bad = {}, []
for r in report["requirements"]:
    st = r["status"]
    counts[st] = counts.get(st, 0) + 1
    if st == "FAIL" or st == "NOT_TESTED" and r["tier"] == "MANDATORY":
        bad.append(f"{r['id']} {st}")
    elif st == "SKIPPED":
        if r["id"] in allowed:
            print(f"tck: skipped (not advertised) {r['id']}")
        else:
            reasons = "; ".join(t.get("message") or "" for t in r.get("tests", []))
            bad.append(f"{r['id']} SKIPPED unexpectedly: {reasons}")
print("tck:", ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
print("tck: verdict", "CONFORMANT" if report["verdict"]["conformant"] else "NOT CONFORMANT")
for b in bad:
    print("tck: problem:", b)
sys.exit(1 if bad or sys.argv[3] != "0" else 0)
PY
