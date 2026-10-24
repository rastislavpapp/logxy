package io.github.rastislavpapp.logxy.ui

const val RENAME_NEEDS_SAVED_PARSER = "Only the columns of your own saved parsers can be renamed in the settings."

/** At most this many characters of a value shown in a menu item; see [cut]. */
const val MAX_MENU_CHARS = 80

/** This text, or its start and "…" if it is longer than [maxChars], e.g. for a menu item. */
fun String.cut(maxChars: Int): String = if (length > maxChars) take(maxChars - 1) + "…" else this

/** [n] and its [unit], e.g. `1 error`, `1,234 errors`; [plural] for a unit that does not just take an s. */
fun count(n: Int, unit: String, plural: String = "${unit}s"): String = "%,d %s".format(n, if (n == 1) unit else plural)

/** [text] escaped for Swing's HTML, e.g. in a tooltip, so that it shows as written. */
fun escapeHtml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
