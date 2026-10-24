# Text search with the Vector API

logxy searches text in records (plain "contains" conditions, the find bar, the
fixed text a regex needs) one byte at a time with a scalar loop. It does not add
a second, SIMD search through `jdk.incubator.vector` next to it.

## Why this is out of scope

The newline search uses the Vector API because it runs over every byte of a
file at once, where vectors double the indexing speed (ADR 0002). Text
conditions are different: they search one value or one record at a time,
usually a few hundred bytes, where a vector loop's setup and tail eat much of
what it saves. The expected gain is marginal.

The cost is not: a vector search needs its own class kept apart from the
plugin's JVMs, which may lack the module, a scalar fallback for narrow CPUs,
a switch to force that fallback, and tests that hold both paths to the same
results, ASCII case folding included. That is a second search path to keep
correct and tested for every change to text matching.

Searching a whole buffer of records at once would give vectors the long input
they need, but that is out of scope on its own, see `whole-buffer-text-search.md`.

## Prior requests

- #116: "Search plain text with the Vector API"
