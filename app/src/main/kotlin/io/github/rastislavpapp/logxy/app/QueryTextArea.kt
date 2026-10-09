package io.github.rastislavpapp.logxy.app

import io.github.rastislavpapp.logxy.core.log.Level
import io.github.rastislavpapp.logxy.core.query.QueryException
import io.github.rastislavpapp.logxy.core.query.QueryOp
import io.github.rastislavpapp.logxy.core.query.QueryParser
import org.fife.ui.autocomplete.AutoCompletion
import org.fife.ui.autocomplete.BasicCompletion
import org.fife.ui.autocomplete.DefaultCompletionProvider
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.Token
import org.fife.ui.rsyntaxtextarea.TokenMakerBase
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory
import org.fife.ui.rsyntaxtextarea.parser.AbstractParser
import org.fife.ui.rsyntaxtextarea.parser.DefaultParseResult
import org.fife.ui.rsyntaxtextarea.parser.DefaultParserNotice
import org.fife.ui.rsyntaxtextarea.parser.ParseResult
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.KeyStroke
import javax.swing.text.Segment

private const val SYNTAX_STYLE = "text/x-logxy-query"

/** Editor for the text form of a filter: highlighting, completion, and syntax errors underlined as you type. */
class QueryTextArea(private val onApply: () -> Unit) : RSyntaxTextArea(3, 60) {

    private val completions = DefaultCompletionProvider()

    init {
        (TokenMakerFactory.getDefaultInstance() as AbstractTokenMakerFactory)
            .putMapping(SYNTAX_STYLE, QueryTokenMaker::class.java.name, QueryTokenMaker::class.java.classLoader)
        syntaxEditingStyle = SYNTAX_STYLE
        lineWrap = true
        isCodeFoldingEnabled = false
        highlightCurrentLine = false
        isBracketMatchingEnabled = true
        addParser(SyntaxChecker())
        parserDelay = 300

        completions.setAutoActivationRules(true, null)
        AutoCompletion(completions).apply {
            isAutoActivationEnabled = true
            autoActivationDelay = 400
            install(this@QueryTextArea)
        }
        setFields(emptyList())

        // Enter starts a new line, as in any editor; Ctrl+Enter applies.
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), "logxyApply")
        actionMap.put("logxyApply", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = onApply()
        })
        applyTheme()
    }

    /** Field names offered by completion, besides keywords, operators and levels. */
    fun setFields(fields: List<String>) {
        completions.clear()
        val words = fields.map { if (QueryParser.isBareWord(it)) it else "`$it`" } +
            listOf("AND", "OR", "NOT") + Level.entries.map { it.name } + QueryOp.entries.map { it.symbol }
        words.distinct().forEach { completions.addCompletion(BasicCompletion(completions, it)) }
    }

    override fun updateUI() {
        super.updateUI()
        applyTheme()
    }

    // Also called from the constructor of RSyntaxTextArea, before this class is initialized.
    @Suppress("SENSELESS_COMPARISON")
    private fun applyTheme() {
        if (completions == null) return
        applyEditorTheme()
    }

    /** Underlines the first syntax error; field names are checked when the filter is applied. */
    private inner class SyntaxChecker : AbstractParser() {
        override fun parse(doc: RSyntaxDocument, style: String?): ParseResult {
            val result = DefaultParseResult(this)
            val text = doc.getText(0, doc.length)
            try {
                QueryParser.parse(text)
            } catch (e: QueryException) {
                val offset = e.offset.coerceIn(0, maxOf(0, text.length - 1))
                val line = doc.defaultRootElement.getElementIndex(offset)
                result.addNotice(DefaultParserNotice(this, e.message, line, offset, maxOf(1, e.length)))
            }
            return result
        }
    }
}

/** Colors the query language: keywords, operators, strings, field names and values. */
class QueryTokenMaker : TokenMakerBase() {

    override fun getTokenList(text: Segment, initialTokenType: Int, startOffset: Int): Token {
        resetTokenList()
        val array = text.array
        val end = text.offset + text.count
        val shift = startOffset - text.offset
        var afterOperator = false
        var i = text.offset
        while (i < end) {
            val start = i
            val c = array[i]
            val type: Int
            when {
                c.isWhitespace() -> {
                    while (i < end && array[i].isWhitespace()) i++
                    type = Token.WHITESPACE
                }
                c == '(' || c == ')' -> {
                    i++
                    type = Token.SEPARATOR
                }
                c == '"' -> {
                    i++
                    while (i < end && array[i] != '"') i += if (array[i] == '\\' && i + 1 < end) 2 else 1
                    if (i < end) i++
                    type = Token.LITERAL_STRING_DOUBLE_QUOTE
                    afterOperator = false
                }
                c == '`' -> {
                    i++
                    while (i < end && array[i] != '`') i++
                    if (i < end) i++
                    type = Token.VARIABLE
                }
                c in "=!<>~" -> {
                    while (i < end && array[i] in "=!<>~") i++
                    type = Token.OPERATOR
                    afterOperator = true
                }
                else -> {
                    while (i < end && !array[i].isWhitespace() && array[i] !in "()\"`=!<>~") i++
                    val word = String(array, start, i - start)
                    type = when {
                        word.uppercase() in KEYWORDS -> Token.RESERVED_WORD
                        afterOperator -> Token.LITERAL_CHAR
                        else -> Token.VARIABLE
                    }
                    afterOperator = false
                }
            }
            addToken(array, start, i - 1, type, start + shift)
        }
        addNullToken()
        return firstToken
    }

    private companion object {
        val KEYWORDS = setOf("AND", "OR", "NOT")
    }
}
