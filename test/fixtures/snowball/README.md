Snowball English stemmer test data

voc.txt is a vocabulary of 42,649 English words. output.txt holds the stem
Snowball's English stemmer gives each word, one per line, in the same order.
Both files are complete and unchanged.

Source: https://github.com/snowballstem/snowball-data, directory english,
commit 019921c3a41c2b70063ee3feb215a00c8e594597, fetched 2026-10-17.

The algorithm these files match is algorithms/english.sbl in
https://github.com/snowballstem/snowball at commit
6389c79f91cd67ed2de3ebdcf84ec8c2d2d23c11. test/relay/porter2_test.clj checks
every pair.
