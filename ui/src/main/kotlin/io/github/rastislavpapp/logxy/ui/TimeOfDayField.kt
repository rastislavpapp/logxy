package io.github.rastislavpapp.logxy.ui

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.time.LocalTime
import java.time.format.DateTimeParseException
import javax.swing.AbstractAction
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter

private const val SECONDS_PER_DAY = 86_400

/** Seconds in one of each part, from the hours on. */
private val UNITS = intArrayOf(3_600, 60, 1)

/** The largest value of each part. */
private val MAXES = intArrayOf(23, 59, 59)

/** Characters a part takes in the text, with the colon after it. */
private const val PART_WIDTH = 3

/**
 * A time of day as `HH:mm:ss`, edited a part at a time as a system's date fields are: the part being edited is
 * selected, digits typed overwrite it and move on once it is full, Up and Down (or the mouse wheel) step it and carry
 * into the larger parts, Left, Right and Tab move between the parts, Backspace sets it to 0. Stepping over midnight
 * calls [onNextDay] with 1, or -1 back.
 */
internal class TimeOfDayField(secondOfDay: Int, private val onNextDay: (days: Int) -> Unit) : JTextField(6) {

    /** The time shown, in seconds since midnight. */
    var secondOfDay = secondOfDay
        private set

    /** The part being edited: 0 the hours, 1 the minutes, 2 the seconds. */
    private var part = 0

    /** The first digit typed into [part], while a second one may still follow it. */
    private var pending: Int? = null

    /** Only [showTime] changes the text. */
    private var showing = false

    init {
        horizontalAlignment = CENTER
        toolTipText = "Type the digits, or step a part with Up and Down; Left, Right and Tab move between the parts"
        focusTraversalKeysEnabled = false
        (document as AbstractDocument).documentFilter = object : DocumentFilter() {
            override fun insertString(bypass: FilterBypass, offset: Int, text: String, attributes: AttributeSet?) {
                if (showing) super.insertString(bypass, offset, text, attributes)
            }

            override fun remove(bypass: FilterBypass, offset: Int, length: Int) {
                if (showing) super.remove(bypass, offset, length)
            }

            override fun replace(
                bypass: FilterBypass,
                offset: Int,
                length: Int,
                text: String?,
                attributes: AttributeSet?,
            ) {
                if (showing) super.replace(bypass, offset, length, text, attributes)
            }
        }
        bind("UP", "logxyTimeUp") { step(up = true) }
        bind("DOWN", "logxyTimeDown") { step(up = false) }
        bind("LEFT", "logxyTimePartBefore") { movePart(-1) }
        bind("RIGHT", "logxyTimePartAfter") { movePart(1) }
        bind("HOME", "logxyTimeFirstPart") { movePart(-part) }
        bind("END", "logxyTimeLastPart") { movePart(UNITS.size) }
        bind("BACK_SPACE", "logxyTimeClearPart") { clearPart() }
        bind("DELETE", "logxyTimeClearPartAfter") { clearPart() }
        bind("TAB", "logxyTimeNextPart") { if (part < UNITS.lastIndex) movePart(1) else transferFocus() }
        bind("shift TAB", "logxyTimePreviousPart") { if (part > 0) movePart(-1) else transferFocusBackward() }
        addKeyListener(object : KeyAdapter() {
            // Digits overwrite the part, a colon moves on; nothing else typed gets into the text.
            override fun keyTyped(e: KeyEvent) {
                val char = e.keyChar
                if (char in '0'..'9') typeDigit(char.digitToInt())
                if (char == ':') movePart(1)
                e.consume()
            }
        })
        val mouse = object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) = editPartAt(caretPosition)

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                editPartAt(viewToModel2D(e.point))
                step(up = e.wheelRotation < 0)
            }
        }
        addMouseListener(mouse)
        addMouseWheelListener(mouse)
        // After the look's own focus handling, which may select all of it.
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = SwingUtilities.invokeLater(::showTime)
        })
        showTime()
    }

    private fun bind(key: String, name: String, action: () -> Unit) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name)
        actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = action()
        })
    }

    /** Types [digit] into the part being edited. */
    fun typeDigit(digit: Int) {
        val first = pending
        pending = null
        if (first != null && first * 10 + digit <= MAXES[part]) {
            setPart(first * 10 + digit)
            nextPart()
        } else {
            setPart(digit)
            // A digit that no second one can follow fills the part.
            if (digit * 10 <= MAXES[part]) pending = digit else nextPart()
        }
        showTime()
    }

    /** Steps the part being edited by one, up or down, carrying into the larger parts and over midnight. */
    fun step(up: Boolean) {
        val moved = secondOfDay + if (up) UNITS[part] else -UNITS[part]
        val days = Math.floorDiv(moved, SECONDS_PER_DAY)
        secondOfDay = Math.floorMod(moved, SECONDS_PER_DAY)
        pending = null
        showTime()
        if (days != 0) onNextDay(days)
    }

    /** Edits the part [by] parts after the one being edited, before it if negative, kept within the parts. */
    fun movePart(by: Int) {
        part = (part + by).coerceIn(0, UNITS.lastIndex)
        pending = null
        showTime()
    }

    /** Sets the part being edited to 0. */
    fun clearPart() {
        setPart(0)
        pending = null
        showTime()
    }

    // A time pasted, such as 14:32 or 14:32:05, replaces the whole time.
    override fun paste() {
        val text = try {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
        } catch (_: Exception) {
            return
        }
        try {
            secondOfDay = LocalTime.parse(text.trim()).toSecondOfDay()
        } catch (_: DateTimeParseException) {
            return
        }
        pending = null
        showTime()
    }

    override fun cut() = copy()

    private fun editPartAt(offset: Int) {
        if (offset < 0) return
        part = minOf(offset / PART_WIDTH, UNITS.lastIndex)
        pending = null
        showTime()
    }

    private fun nextPart() {
        if (part < UNITS.lastIndex) part++
    }

    private fun setPart(value: Int) {
        val old = secondOfDay / UNITS[part] % (MAXES[part] + 1)
        secondOfDay += (value - old) * UNITS[part]
    }

    private fun showTime() {
        val time = LocalTime.ofSecondOfDay(secondOfDay.toLong())
        showing = true
        try {
            text = "%02d:%02d:%02d".format(time.hour, time.minute, time.second)
        } finally {
            showing = false
        }
        select(part * PART_WIDTH, part * PART_WIDTH + 2)
    }
}
