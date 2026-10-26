# logxy

A desktop viewer for large log files: it indexes one file, or several merged by time, and lets the user filter, find and follow them.

## Language

**Session**:
One or more open files shown together in one tab, followed while they change.
_Avoid_: tab (the UI around a session), document

**Recent session**:
A closed session as it can be opened again: its files, the parser chosen for each, and its filter.
_Avoid_: recent file, history entry

**Load**:
One pass of a session over its files: indexed, merged if there are several, then followed until the session reloads or closes.
_Avoid_: open, generation

**Record**:
One log entry, possibly spanning several lines; identified by its record number in the session's records.
_Avoid_: line (only in raw text, where a record is one line), entry, event

**Row**:
A record's position among those shown; differs from its record number while a filter is active.
_Avoid_: index, line

**Expand**:
Show a row's whole record in its message cell, wrapped: its later lines, or a first line too long for the cell. _Collapse_ goes back to the first line only.
_Avoid_: fold, unfold, unpack

**Parser**:
A saved description of how to split a file's lines into records and fields. Each is of one parser kind (pattern, regex, JSON, …); built-in parsers ship with the app and are tried on every file opened.
_Avoid_: format, layout

**Raw text**:
The parser of a file no other parser reads: each line is a record with only a message.
_Avoid_: unparsed, plain text

**Preamble**:
The lines at the start of a file before its first record, such as a build tool's or a framework's startup output; each is a record of its own with only a message.
_Avoid_: banner, startup noise

**Message template**:
The first line of a record's message with its variable parts, such as numbers and ids, replaced by `{1}`, `{2}`, … numbered from the left, as in `Request {1} done in {2} ms`. Records with the same template are the same kind of message.
_Avoid_: pattern (a parser kind), message pattern

**Template variable**:
One `{n}` of a message template, such as `{2}`; each record of the template has a value there. Short form _variable_ where the template is clear.
_Avoid_: slot, placeholder, parameter

**Variable value**:
The word a record has at a template variable, such as `404` in `Request 404 done in 12 ms` at variable 1.
_Avoid_: argument

**Shown log**:
The records of one load as the user sees them: all of them, or a filter's result. It ends when the session reloads or closes.
_Avoid_: view, shown rows, filtered rows

**Selection**:
The rows the user marked in a shown log. It ends with the shown log, except that the record at its lead stays selected when the next shown log still shows it.
_Avoid_: selected lines, marked rows

**Filter**:
What the user sets to choose a session's shown records: a query, and optionally a minimum level. A suspended filter is kept but shows all records.
_Avoid_: search (that is find)

**Query**:
A filter's conditions: a group of rules and groups, typed as text or built in the visual editor.
_Avoid_: expression, filter text

**Rule**:
One condition on one field, a template or a template variable, such as `level >= WARN` or `message == "Timeout*"`.
_Avoid_: condition, criterion, clause

**Group**:
Rules and groups joined all with AND or all with OR, possibly negated; a query is one.
_Avoid_: block, subquery
