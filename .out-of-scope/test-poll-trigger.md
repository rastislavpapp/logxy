# A test-controlled poll trigger for the session

`LogSession` polls its files on a real timer (`LIVE_POLL_MILLIS`,
`STATIC_POLL_MILLIS`), and session tests wait for it in short polling loops
under a timeout. logxy does not add a pluggable tick source so tests can say
"poll now" instead.

## Why this is out of scope

The seam would exist only for tests: the app always polls on a timer, so
production code would carry an extra abstraction with one real implementation.
The races these tests had were fixed where they were, and the waiting loops
make the tests a little slower, not wrong. That trade does not pay for a
change to how the session schedules its reads.

If session tests become flaky again, fix the race in the test or the session
first; this decision is about the tick-source indirection, not about test
reliability.

## Prior requests

- #8: "Let tests trigger the session's file polling"
