package io.github.rastislavpapp.logxy.app

import com.formdev.flatlaf.FlatLaf
import io.github.rastislavpapp.logxy.core.query.Highlighter
import io.github.rastislavpapp.logxy.ui.DetailPane
import io.github.rastislavpapp.logxy.ui.FOUND_COLOR
import io.github.rastislavpapp.logxy.ui.HIGHLIGHT_COLOR
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.Theme
import org.fife.ui.rsyntaxtextarea.Token
import org.fife.ui.rsyntaxtextarea.TokenMakerBase
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory
import java.awt.Font
import javax.swing.UIManager
import javax.swing.text.DefaultHighlighter
import javax.swing.text.JTextComponent
import javax.swing.text.Segment

private const val SYNTAX_STYLE = "text/x-logxy-record"

/** Applies the editor colors matching the light or dark look and the monospaced UI font. */
fun RSyntaxTextArea.applyEditorTheme() {
    val name = if (FlatLaf.isLafDark()) "dark.xml" else "default.xml"
    val theme = RSyntaxTextArea::class.java.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/$name")
    theme?.use { Theme.load(it).apply(this) }
    val base = UIManager.getFont("monospaced.font")
        ?: Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("TextArea.font").size)
    font = Font(base.name, base.style, base.size)
    background = UIManager.getColor("TextArea.background")
}

/** Read-only view of a whole record, its stack trace highlighted. */
class LogDetailArea : RSyntaxTextArea(), DetailPane {

    private var initialized = false
    private val painter = DefaultHighlighter.DefaultHighlightPainter(HIGHLIGHT_COLOR)
    private val foundPainter = DefaultHighlighter.DefaultHighlightPainter(FOUND_COLOR)

    override val component: JTextComponent get() = this

    override var matches: Highlighter = Highlighter.NONE
        set(value) {
            field = value
            mark()
        }

    override var found: Highlighter = Highlighter.NONE
        set(value) {
            field = value
            mark()
        }

    private var foundRanges = IntArray(0)

    override val firstFound: Int get() = foundRanges.firstOrNull() ?: 0

    init {
        (TokenMakerFactory.getDefaultInstance() as AbstractTokenMakerFactory)
            .putMapping(SYNTAX_STYLE, LogRecordTokenMaker::class.java.name, LogRecordTokenMaker::class.java.classLoader)
        syntaxEditingStyle = SYNTAX_STYLE
        isEditable = false
        lineWrap = true
        highlightCurrentLine = false
        isCodeFoldingEnabled = false
        initialized = true
        applyTheme()
    }

    override fun updateUI() {
        super.updateUI()
        // Also called from the superclass constructor, before this class is initialized.
        if (initialized) applyTheme()
    }

    override fun show(value: String) {
        text = value
        mark()
    }

    private fun mark() {
        if (!initialized) return
        highlighter.removeAllHighlights()
        val ranges = matches.ranges(null, text, Highlighter.DETAIL_BUDGET_NANOS)
        for (i in ranges.indices step 2) highlighter.addHighlight(ranges[i], ranges[i + 1], painter)
        foundRanges = found.ranges(null, text, Highlighter.DETAIL_BUDGET_NANOS)
        for (i in foundRanges.indices step 2) highlighter.addHighlight(foundRanges[i], foundRanges[i + 1], foundPainter)
    }

    /** The editor theme, with levels in the same colors as the table's level badges. */
    private fun applyTheme() {
        applyEditorTheme()
        val scheme = syntaxScheme
        fun color(type: Int, key: String) = scheme.getStyle(type).apply {
            foreground = UIManager.getColor(key)
            background = null
            underline = false
        }
        color(Token.ERROR_IDENTIFIER, "Actions.Red")
        color(Token.ANNOTATION, "Actions.Yellow")
        color(Token.PREPROCESSOR, "Actions.Blue")
        syntaxScheme = scheme
    }
}

/** Colors log records: timestamps, levels, exceptions, stack frames and their source locations. */
class LogRecordTokenMaker : TokenMakerBase() {

    override fun getTokenList(text: Segment, initialTokenType: Int, startOffset: Int): Token {
        resetTokenList()
        val array = text.array
        val end = text.offset + text.count
        val shift = startOffset - text.offset
        var i = text.offset
        var lineStart = true
        var inFrame = false
        while (i < end) {
            val start = i
            val c = array[i]
            var type: Int
            when {
                c.isWhitespace() -> {
                    while (i < end && array[i].isWhitespace()) i++
                    type = Token.WHITESPACE
                }
                c == '"' -> {
                    i++
                    while (i < end && array[i] != '"') i++
                    if (i < end) i++
                    type = Token.LITERAL_STRING_DOUBLE_QUOTE
                }
                c == '(' && inFrame -> {
                    // The source location of a stack frame, e.g. (Db.java:42).
                    while (i < end && array[i] != ')') i++
                    if (i < end) i++
                    type = Token.COMMENT_EOL
                }
                c.isDigit() -> {
                    while (i < end && (array[i].isDigit() || array[i] in "-:.,T")) i++
                    if (i < end && array[i].isLetter()) {
                        // Not a number but a word starting with digits, such as a hex id.
                        while (i < end && (array[i].isLetterOrDigit() || array[i] == '-')) i++
                        type = Token.IDENTIFIER
                    } else {
                        type = Token.LITERAL_NUMBER_DECIMAL_INT
                    }
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    while (i < end && (array[i].isLetterOrDigit() || array[i] in "_$.:")) i++
                    // A trailing colon belongs to "Caused by:" or an exception header, not the word.
                    var wordEnd = i
                    if (array[wordEnd - 1] == ':' && wordEnd - 1 > start) wordEnd--
                    i = wordEnd
                    val word = String(array, start, wordEnd - start)
                    type = when {
                        word in ERROR_LEVELS -> Token.ERROR_IDENTIFIER
                        word in WARN_LEVELS -> Token.ANNOTATION
                        word in OTHER_LEVELS -> Token.PREPROCESSOR
                        lineStart && word == "at" -> {
                            inFrame = true
                            Token.RESERVED_WORD
                        }
                        word == "Caused" || word == "Suppressed" || word == "by" -> Token.RESERVED_WORD
                        '.' in word && THROWABLE_SUFFIXES.any { word.endsWith(it) } -> Token.DATA_TYPE
                        inFrame -> Token.FUNCTION
                        else -> Token.IDENTIFIER
                    }
                }
                else -> {
                    i++
                    type = Token.IDENTIFIER
                }
            }
            if (type != Token.WHITESPACE) lineStart = false
            addToken(array, start, i - 1, type, start + shift)
        }
        addNullToken()
        return firstToken
    }

    private companion object {
        val ERROR_LEVELS = setOf("ERROR", "FATAL", "SEVERE", "CRITICAL")
        val WARN_LEVELS = setOf("WARN", "WARNING")
        val OTHER_LEVELS = setOf("INFO", "DEBUG", "TRACE")
        val THROWABLE_SUFFIXES = listOf("Exception", "Error", "Throwable")
    }
}
