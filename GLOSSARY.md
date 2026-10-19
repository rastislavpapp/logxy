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

**Parser**:
A saved description of how to split a file's lines into records and fields. Each is of one parser kind (pattern, regex, JSON, …); built-in parsers ship with the app and are tried on every file opened.
_Avoid_: format, layout

**Message template**:
The first line of a record's message with its variable parts, such as numbers and ids, replaced by `{}`, as in `Request {} done in {} ms`. Records with the same template are the same kind of message.
_Avoid_: pattern (a parser kind), message pattern

**Slot**:
One `{}` of a message template, counted from the left; each record of the template has a value there.
_Avoid_: placeholder, variable, parameter

**Shown log**:
The records of one load as the user sees them: all of them, or a filter's result. It ends when the session reloads or closes.
_Avoid_: view, shown rows, filtered rows

**Selection**:
The rows the user marked in a shown log. It ends with the shown log, except that the record at its lead stays selected when the next shown log still shows it.
_Avoid_: selected lines, marked rows
