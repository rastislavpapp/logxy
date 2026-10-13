# logxy

A desktop viewer for large log files: it indexes one file, or several merged by time, and lets the user filter, find and follow them.

## Language

**Session**:
One or more open files shown together in one tab, followed while they change.
_Avoid_: tab (the UI around a session), document

**Load**:
One pass of a session over its files: indexed, merged if there are several, then followed until the session reloads or closes.
_Avoid_: open, generation

**Record**:
One log entry, possibly spanning several lines; identified by its record number in the session's records.
_Avoid_: line (only in raw text, where a record is one line), entry, event

**Row**:
A record's position among those shown; differs from its record number while a filter is active.
_Avoid_: index, line

**Shown log**:
The records of one load as the user sees them: all of them, or a filter's result. It ends when the session reloads or closes.
_Avoid_: view, shown rows, filtered rows
