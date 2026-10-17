# Coding standards

Checked in review, against the diff. Formatting and line length are detekt's (`gradle/detekt.yml`); these are the judgement calls it cannot make.

## Domain terms

Names, KDoc and comments in the diff use the terms `GLOSSARY.md` defines, never the words its _Avoid_ lists name (e.g. "shown rows" for the shown log). Older code that still uses an avoided word is no precedent.

## Dense lines

A line does one thing a reader can name. Under 120 characters a line can still pack too much; split it when it stacks:

- a scope function chain with a fallback: `x?.let { … } ?: y`, `a.takeIf { … }?.let { … }`. Prefer an early `return`, or a named `val` and a plain `if`.
- a lambda inside a lambda on one line, or an `if` inside a lambda inside an `if`.
- a call whose arguments are themselves calls with arguments: name the inner results.

## Splitting a long line

- Name a part with a `val`, or turn the expression into a `when`; wrapping is the fallback, and one argument per line the last resort.
- A value picked from three or more cases is a `when`, not an `if`/`else if` chain.
- A lambda's parameters stay on its opening line (`{ item ->`).

## Restructuring keeps behaviour

Pulling parts out of an expression must keep what it did:

- **Evaluation order** of calls with side effects, such as drawing from a random source, advancing an iterator or mutating state: pulled out into `val`s, they still run in the order the expression ran them.
- **Null fallbacks**: splitting `a?.let(f) ?: b` must still fall back to `b` when `f` returns null, not only when `a` is null.
- **Conditions**: reusing an existing helper for an inline check only when the helper tests exactly the same thing.
- **Guards**: work a branch skipped before (a lookup done only for some cases) stays skipped.

## Helpers

A helper extracted to shorten lines serves all the places that repeat the code, or none: one used by two of six identical sites leaves the reader two ways to read the same thing.
