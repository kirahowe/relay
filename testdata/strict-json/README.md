# Strict JSON cases (relay#58)

The one table of lines that relay, agentloop and relay-tui must read
identically. Each line of `cases.jsonl` is a test case:

- `name`: what it checks.
- `b64`: the exact bytes of one message (one ACP line, without its
  newline), base64-encoded so invalid UTF-8 survives.
- `expect`: `"reject"`, or `{"method": <string or null>}`, the value of
  the exact member `method` when the message is accepted (null when it
  has none).

The dialect: RFC 8259 JSON in valid UTF-8; the message is one JSON
object and only whitespace follows it; no duplicate member names at any
depth (compared after unescaping); member names are matched exactly and
case-sensitively; no byte order mark; unpaired surrogate escapes
rejected; nesting at most 128 levels (the top object is level 1); a
number literal at most 1000 characters; a number's exponent (the
integer after `e` or `E`, sign included) between -9999 and 9999
inclusive, by value, so leading zeros in it are allowed as JSON allows
them.

This copy, in relay, is the source. agentloop and relay-tui keep
byte-identical copies at `testdata/strict-json/cases.jsonl`, and each
repo runs every case through its real reader. To change the table,
change it here and copy it to both.
