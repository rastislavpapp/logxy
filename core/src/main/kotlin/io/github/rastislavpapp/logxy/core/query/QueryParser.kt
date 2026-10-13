package io.github.rastislavpapp.logxy.core.query

/**
 * Parses the text form of a [Query], e.g.
 * `level >= WARN AND (message == "*timeout*" OR NOT thread =~ "^worker")`.
 * AND binds tighter than OR; keywords ignore case. Strings are in double quotes, where only `\"`
 * is an escape (backslashes stay, for wildcard and regex escapes); field names with spaces go in
 * backticks.
 */
object QueryParser {

    private enum class Kind { WORD, STRING, FIELD, OP, OPEN, CLOSE, END }

    private class Token(val kind: Kind, val text: String, val offset: Int, val length: Int)

    /** The query [text] says; [Query.EMPTY] for blank text. Throws [QueryException] with the position of the first error. */
    fun parse(text: String): Query.Group {
        val parser = Parser(tokenize(text))
        if (parser.peek().kind == Kind.END) return Query.EMPTY
        val query = parser.or()
        val rest = parser.peek()
        if (rest.kind != Kind.END) throw unexpected(rest, if (rest.kind == Kind.CLOSE) "No “(” matches this “)”" else "Expected AND or OR")
        return query as? Query.Group ?: Query.Group(all = true, items = listOf(query))
    }

    private class Parser(private val tokens: List<Token>) {
        private var position = 0

        fun peek() = tokens[position]
        private fun next() = tokens[position++]
        private fun keyword(word: String) = peek().kind == Kind.WORD && peek().text.equals(word, ignoreCase = true)

        fun or(): Query {
            val items = mutableListOf(and())
            while (keyword("OR")) {
                next()
                items += and()
            }
            return if (items.size == 1) items[0] else Query.Group(all = false, items = items)
        }

        private fun and(): Query {
            val items = mutableListOf(unary())
            while (keyword("AND")) {
                next()
                items += unary()
            }
            return if (items.size == 1) items[0] else Query.Group(all = true, items = items)
        }

        private fun unary(): Query {
            if (keyword("NOT")) {
                next()
                return when (val inner = unary()) {
                    is Query.Group -> inner.copy(negated = !inner.negated)
                    is Query.Rule -> inner.copy(negated = !inner.negated)
                }
            }
            if (peek().kind == Kind.OPEN) {
                val open = next()
                if (peek().kind == Kind.CLOSE) throw unexpected(peek(), "Empty parentheses")
                val inner = or()
                if (peek().kind != Kind.CLOSE) throw QueryException("Missing “)” for this “(”", open.offset, 1)
                next()
                // Parentheses always make a group, so the visual editor shows them as one.
                return inner as? Query.Group ?: Query.Group(all = true, items = listOf(inner))
            }
            return rule()
        }

        private fun rule(): Query.Rule {
            val field = next()
            if (field.kind != Kind.WORD && field.kind != Kind.FIELD) throw unexpected(field, "Expected a field name")
            if (field.kind == Kind.WORD && field.text.uppercase() in KEYWORDS) throw unexpected(field, "Expected a field name, not ${field.text}")
            val op = next()
            if (op.kind != Kind.OP) throw unexpected(op, "Expected an operator after ${field.text}, e.g. == or >=")
            val value = next()
            if (value.kind != Kind.WORD && value.kind != Kind.STRING) throw unexpected(value, "Expected a value after ${op.text}")
            return Query.Rule(field.text, OPS.getValue(op.text), value.text, bare = value.kind == Kind.WORD)
        }
    }

    private fun unexpected(token: Token, message: String) =
        QueryException(if (token.kind == Kind.END) "$message, but the text ends" else message, token.offset, maxOf(token.length, 1))

    private val OPS = QueryOp.entries.associateBy { it.symbol }

    // Longest first, so "===" is not read as "==" followed by "=".
    private val OP_SYMBOLS = QueryOp.entries.map { it.symbol }.sortedByDescending { it.length }

    private val KEYWORDS = setOf("AND", "OR", "NOT")

    private const val OPERATOR_CHARS = "=!<>~"

    private fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> tokens += Token(Kind.OPEN, "(", i++, 1)
                c == ')' -> tokens += Token(Kind.CLOSE, ")", i++, 1)
                c == '"' -> {
                    val value = StringBuilder()
                    var j = i + 1
                    while (j < text.length && text[j] != '"') {
                        if (text[j] == '\\' && j + 1 < text.length) {
                            if (text[j + 1] == '"') value.append('"') else value.append('\\').append(text[j + 1])
                            j += 2
                        } else {
                            value.append(text[j++])
                        }
                    }
                    if (j >= text.length) throw QueryException("Missing closing quote", i, text.length - i)
                    tokens += Token(Kind.STRING, value.toString(), i, j + 1 - i)
                    i = j + 1
                }
                c == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close < 0) throw QueryException("Missing closing backtick", i, text.length - i)
                    tokens += Token(Kind.FIELD, text.substring(i + 1, close), i, close + 1 - i)
                    i = close + 1
                }
                c in OPERATOR_CHARS -> {
                    val symbol = OP_SYMBOLS.firstOrNull { text.startsWith(it, i) }
                        ?: throw QueryException(if (c == '=') "Use == to compare" else "Unknown operator", i, 1)
                    tokens += Token(Kind.OP, symbol, i, symbol.length)
                    i += symbol.length
                }
                else -> {
                    var j = i
                    while (j < text.length && !text[j].isWhitespace() && text[j] !in "()\"`" && text[j] !in OPERATOR_CHARS) j++
                    tokens += Token(Kind.WORD, text.substring(i, j), i, j - i)
                    i = j
                }
            }
        }
        tokens += Token(Kind.END, "", text.length, 0)
        return tokens
    }

    /** Whether [text] reads back as one word without quotes or backticks: no spaces, quotes, parentheses or operators, and no keyword. */
    fun isBareWord(text: String) = text.isNotEmpty() &&
        text.none { it.isWhitespace() || it in "()\"`" || it in OPERATOR_CHARS } && text.uppercase() !in KEYWORDS
}

/** Writes a [Query] as text that [QueryParser] reads back to the same query. */
object QueryPrinter {

    /** [query] as text, quoting fields and values only where needed. */
    fun print(query: Query): String = when (query) {
        is Query.Group -> if (query.negated) "NOT (${items(query)})" else items(query)
        is Query.Rule -> (if (query.negated) "NOT " else "") + rule(query)
    }

    private fun items(group: Query.Group): String = group.items.joinToString(if (group.all) " AND " else " OR ") { item ->
        when {
            item is Query.Group && !item.negated -> "(${items(item)})"
            else -> print(item)
        }
    }

    private fun rule(rule: Query.Rule): String {
        val field = if (QueryParser.isBareWord(rule.field)) rule.field else "`${rule.field}`"
        val value = if (rule.bare && QueryParser.isBareWord(rule.value)) rule.value else "\"" + rule.value.replace("\"", "\\\"") + "\""
        return "$field ${rule.op.symbol} $value"
    }
}
