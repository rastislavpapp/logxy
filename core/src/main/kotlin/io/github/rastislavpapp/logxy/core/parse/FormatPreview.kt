package io.github.rastislavpapp.logxy.core.parse

import io.github.rastislavpapp.logxy.core.log.Columns
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** How one line of a file reads with a format; for trying out a format while editing it. */
class PreviewLine(
    val text: String,
    /** Field values if the line starts a record, else null. */
    val values: List<String>?,
    /** True for lines that do not match but look like they continue a record (stack traces). */
    val continuation: Boolean,
)

object FormatPreview {

    private const val SAMPLE_BYTES = 256 * 1024

    /** The first lines of [path], up to [maxLines], from its first 256 KB; empty lines left out if [skipEmpty]. */
    fun sample(path: Path, maxLines: Int, skipEmpty: Boolean = false): List<ByteArray> =
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val reader = LineReader(channel, 0, minOf(channel.size(), SAMPLE_BYTES.toLong()), SAMPLE_BYTES)
            buildList {
                while (size < maxLines && reader.next()) {
                    if (skipEmpty && reader.lineTo == reader.lineFrom) continue
                    add(reader.buffer.copyOfRange(reader.lineFrom, reader.lineTo))
                }
            }
        }

    fun preview(format: RecordFormat, lines: List<ByteArray>): List<PreviewLine> {
        val parser = format.newParser()
        val fields = format.schema.fields
        return lines.map { line ->
            val text = String(line, Charsets.UTF_8)
            if (!parser.match(line, 0, line.size)) {
                return@map PreviewLine(text, null, FormatCheck.looksLikeContinuation(line, 0, line.size))
            }
            val columns = Columns(format.schema)
            columns.beginRecord(0)
            parser.write(columns)
            val values = fields.indices.map { field ->
                columns.valueText(field, 0, format, columns.dictionaries[field]) ?: run {
                    val start = columns.ints[field]!![0]
                    when (val length = columns.spanLength(field, 0)) {
                        Columns.ABSENT_LENGTH -> ""
                        Columns.TO_RECORD_END -> String(line, start, line.size - start, Charsets.UTF_8)
                        else -> String(line, start, length, Charsets.UTF_8)
                    }
                }
            }
            PreviewLine(text, values, continuation = false)
        }
    }
}
