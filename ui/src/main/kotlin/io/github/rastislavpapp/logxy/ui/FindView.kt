package io.github.rastislavpapp.logxy.ui

import java.text.NumberFormat
import javax.swing.JComponent

/**
 * Where the user types the text to find in the records, without filtering them: the app's [FindBar], or the IDE's own
 * search bar. It reports what to find and asks to go to a match; the [LogTab] tells it how many records match.
 */
interface FindView {

    val component: JComponent

    /** Floats over the top right corner of the rows, rather than sitting above them as a bar of its own. */
    val floats: Boolean

    val isOpen: Boolean

    /** Opens the view and selects its text for typing over; with [text], looks for that instead. */
    fun open(text: String? = null)

    /**
     * Shows how many records match: [total]. While searching, which [progress] (from 0 to 1) says, or null [total]
     * does, [total] is how many were found so far, null if not known. [current] is the position among them of the
     * selected record, from 1, or 0 if it is none (or, while searching, not final yet).
     */
    fun showCount(current: Int, total: Int?, progress: Double? = null)

    /** Marks the text as wrong, e.g. an invalid regex, or shows it is fine again if [message] is null. */
    fun showError(message: String?)
}

/** What a [FindView] says for [FindView.showCount]; empty while there is no [text] to find. */
fun findCountText(text: String, current: Int, total: Int?, progress: Double?): String {
    val numbers = NumberFormat.getIntegerInstance()
    return when {
        text.isEmpty() -> ""
        total == null || progress != null -> searchingText(current, total, progress, numbers)
        total == 0 -> "No matches"
        current > 0 -> "${numbers.format(current)} of ${numbers.format(total)}"
        total == 1 -> "1 match"
        else -> "${numbers.format(total)} matches"
    }
}

/** What the count says while searching: [total] is what was found so far, null if unknown. */
private fun searchingText(current: Int, total: Int?, progress: Double?, numbers: NumberFormat): String {
    val percent = if (progress != null && progress > 0) "${(progress * 100).toInt()} %" else null
    return when {
        total == null || total == 0 -> if (percent != null) "Searching $percent" else "Searching…"
        current > 0 -> "${numbers.format(current)} of ${numbers.format(total)}+"
        percent != null -> "${numbers.format(total)} so far · $percent"
        else -> "${numbers.format(total)} so far"
    }
}
