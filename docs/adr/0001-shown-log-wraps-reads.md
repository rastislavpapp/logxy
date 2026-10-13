# The shown log wraps record reads instead of handing out Records

The session hands the UI a shown log, which forwards each record read to the session's records rather than exposing them. When the load ends (reload, format change, close), the session marks the shown log ended before closing any file, and from then on it reports no rows and its reads return empty. A stale reference left in a queued repaint or callback then goes quiet, where a reference to the records themselves would read from closed files and throw. The forwarding costs one check per read, never per record in the passes over all records, which use the raw row array inside `core`.

## Considered Options

- Expose the records next to the row mapping: a smaller interface, but every stale read throws again, which is the bug this replaces.
- Let the shown log implement `Records`: it could then be passed anywhere records are expected, so the boundary between "read now" and "read off the UI thread" would blur.
