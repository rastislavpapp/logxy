# UTF-16 and other non-ASCII-compatible encodings

logxy does not open log files written in UTF-16, UTF-32 or any other encoding
that is not ASCII-compatible. It reads files as UTF-8.

## Why this is out of scope

Speed comes from working on raw bytes: newline scanning, the line index, the
parsers and the filter's text matching all assume that ASCII characters are
single bytes with their ASCII values. UTF-16 breaks that everywhere: every
other byte is a NUL, and a line end is two bytes. Today, opening a folder skips
such a file because it looks binary.

Supporting it would mean a whole second path just for these files:

- detecting the encoding (BOM, or guessing from the NUL pattern when there is none)
- transcoding the file into a UTF-8 copy before indexing, like compressed files
  are decompressed
- keeping that copy up to date while the file grows, including a code unit or
  surrogate pair split across appends, and rotation or truncation of the source

That is a lot of machinery for a case that is rare among log files, so logxy
leaves it out. A UTF-16 log can be converted before opening, for example with
`iconv -f UTF-16 -t UTF-8`.

This decision covers encodings that are not ASCII-compatible. Single-byte
encodings such as ISO-8859-x or windows-125x already split and parse correctly;
how their non-ASCII characters are shown is a separate question.

## Prior requests

- #22: "UTF-16 and other non-ASCII-compatible encodings"
