package io.github.rastislavpapp.logxy.core.parse

import java.io.ByteArrayOutputStream

/**
 * Lays out the payloads in a record's text for reading, as the detail pane shows them with *Pretty-print* on: each
 * JSON object or array is indented with its strings unescaped, and each XML element is indented. Only well-formed
 * payloads change; the text around them, and anything that merely looks like one, such as a lone `{` or `<`, stays
 * as written. Where payloads overlap, such as XML in a JSON string, the one that starts first is laid out.
 */
object PrettyPrint {

    /** A payload longer than this, in UTF-8 bytes, stays as written, so that laying it out cannot hold up the UI. */
    const val MAX_PAYLOAD_BYTES = 1024 * 1024

    /**
     * How many times the text's length the search for JSON may read. Each `{` or `[` that starts no payload costs a
     * read to where its brackets close, or to the end of the text; without a limit, a text of many such brackets
     * would take time growing with the square of its length.
     */
    private const val JSON_READS = 8L

    /** [text] with its payloads laid out; [text] itself when it holds none. */
    fun text(text: String): String = Layout(text.toByteArray(Charsets.UTF_8)).text(text)

    /** One pass over the UTF-8 [bytes] of a text, writing it with its payloads laid out. */
    private class Layout(private val bytes: ByteArray) {
        private val out = ByteArrayOutputStream(bytes.size + bytes.size / 4)

        /** Where the bytes not yet written start. */
        private var written = 0

        private var jsonBudget = JSON_READS * bytes.size

        /** The laid out text, or [original] itself if no payload changed. */
        fun text(original: String): String {
            val elements = Xml.elements(bytes)
            var element = 0
            var i = 0
            while (i < bytes.size) {
                while (element < elements.size && elements[element] < i) element += 2
                val atElement = element < elements.size && elements[element] == i
                val end = if (atElement) xml(i, elements[element + 1]) else json(i)
                i = if (end > i) end else i + 1
            }
            if (written == 0) return original
            out.write(bytes, written, bytes.size - written)
            return out.toString(Charsets.UTF_8)
        }

        /** Lays out the XML element `bytes[from, to)` unless it is over the cap; returns [to]. */
        private fun xml(from: Int, to: Int): Int {
            if (to - from <= MAX_PAYLOAD_BYTES) replace(from, to, Xml.pretty(bytes, from, to))
            return to
        }

        /** Lays out the JSON payload at [from], if one starts there; returns its end, or -1 if none starts there. */
        private fun json(from: Int): Int {
            val first = bytes[from]
            if ((first != Json.OPEN_OBJECT && first != Json.OPEN_ARRAY) || jsonBudget <= 0) return -1
            val end = Json.valueEnd(bytes, from, bytes.size)
            // Without an end, the brackets were read to the end of the text.
            val read = if (end < 0) bytes.size - from else end - from
            jsonBudget -= read
            if (end < 0) return -1
            // A payload over the cap is skipped whole, so the payloads inside it stay as written too.
            if (end - from > MAX_PAYLOAD_BYTES) return end
            val pretty = if (isPayload(from, end)) Json.pretty(bytes, from, end) else null
            if (pretty == null) return -1
            replace(from, end, pretty)
            return end
        }

        /**
         * Whether the JSON value `bytes[from, to)` is worth laying out: an object, or an array holding an object or
         * array. An array of numbers or words only, such as the `[0]` of `index [0] out of range`, is more likely
         * part of the message than a payload.
         */
        private fun isPayload(from: Int, to: Int): Boolean {
            if (bytes[from] == Json.OPEN_OBJECT) return true
            Json.forEachElement(bytes, from, to) { elementFrom, _ ->
                val first = bytes[elementFrom]
                if (first == Json.OPEN_OBJECT || first == Json.OPEN_ARRAY) return true
            }
            return false
        }

        private fun replace(from: Int, to: Int, pretty: ByteArray) {
            out.write(bytes, written, from - written)
            out.write(pretty)
            written = to
        }
    }
}
