package io.github.rastislavpapp.logxy.app

const val RENAME_NEEDS_SAVED_PARSER = "Only the columns of your own saved parsers can be renamed in the settings."

/** At most this many characters of a value shown in a menu item; see [cut]. */
const val MAX_MENU_CHARS = 80

/** This text, or its start and "…" if it is longer than [maxChars], e.g. for a menu item. */
fun String.cut(maxChars: Int): String = if (length > maxChars) take(maxChars - 1) + "…" else this

/** [text] to put in Swing's HTML, e.g. of a tooltip, as written. */
fun escapeHtml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
