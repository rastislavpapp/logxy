# Text search over the whole read buffer

Text conditions on spans are tested record by record through a sequential read
buffer. logxy does not add a second strategy that searches the whole buffer at
once and maps the hits back to records.

## Why this is out of scope

It is a speculative optimization with no benchmark behind it. The per-record
test already works on raw bytes, and a buffer-wide search would need its own
hit-to-record mapping and would only apply to some conditions ("contains" on a
span), so filtering would carry two paths to keep correct and in sync.

Filter speed is still the top goal: if `:tools:benchmark` shows text filtering
bound by per-record overhead, that measurement is a new request, judged on its
numbers.

## Prior requests

- #16: "Text search over the whole read buffer"
