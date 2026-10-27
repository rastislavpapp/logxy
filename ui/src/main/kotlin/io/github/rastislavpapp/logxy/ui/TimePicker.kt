package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.query.QueryException
import io.github.rastislavpapp.logxy.core.query.TimeLiteral
import net.miginfocom.swing.MigLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager

private const val NANOS_PER_SECOND = 1_000_000_000L
private const val DAYS_PER_WEEK = 7
private const val WEEKS_SHOWN = 6

/** What a time picker opens on and marks, taken from the shown log as it opens. */
class TimePickerLog(
    /** The time to open on while the field holds no time: the selected row's, else the first shown record's. */
    val fallback: Long?,
    /** The days the shown log has records on, in the zone the filter uses; they are marked. */
    val days: Set<LocalDate>,
)

/**
 * A button for the end of [field] that opens a [TimePicker] under it. Picking writes the time into [field] as
 * [TimeLiteral.format] has it, and changes nothing else: Enter in the field then does what it does with typed text.
 * [log] gives what the picker opens on; null when there is nothing to pick from.
 */
fun timePickerButton(field: JTextField, log: () -> TimePickerLog?) =
    toolBarButton(CalendarIcon, "Pick a date and time") { TimePicker.open(field, log()) }

/**
 * A popup under a time field: a month calendar and hour, minute and second spinners; finer parts are kept from the
 * time it opened on. Times are in the zone the filter uses. For a log whose timestamps carry no date, which
 * [TimeLiteral] puts on 1970-01-01, only the time shows. Arrows move the day, Page Up and Page Down the month, Enter
 * picks, Esc closes; so does clicking elsewhere.
 */
internal class TimePicker private constructor(
    owner: Window,
    private val field: JTextField,
    start: ZonedDateTime,
    private val days: Set<LocalDate>,
) : JDialog(owner) {

    private val zone = start.zone
    private val fraction = start.nano
    private val dateless = start.toLocalDate() == LocalDate.EPOCH
    private var selected: LocalDate = start.toLocalDate()
    private var month: YearMonth = YearMonth.from(selected)
    private val firstDay: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    private val title = JLabel("", SwingConstants.CENTER)
    private val grid = DayGrid()
    private val hour = timeSpinner(start.hour, 23)
    private val minute = timeSpinner(start.minute, 59)
    private val second = timeSpinner(start.second, 59)

    init {
        isUndecorated = true
        // A look that draws window decorations itself, such as FlatLaf's, would still give it a title bar.
        rootPane.windowDecorationStyle = JRootPane.NONE
        val content = JPanel(MigLayout("insets 8, gap 4, hidemode 3, wrap 1", "[fill]"))
        content.border = BorderFactory.createLineBorder(look.border)
        val header = JPanel(MigLayout("insets 0, gap 0", "[][grow, fill][]")).apply {
            isOpaque = false
            add(toolBarButton(Icons.get("general/chevronLeft"), "Previous month (Page Up)") { moveMonths(-1) })
            add(title)
            add(toolBarButton(Icons.get("general/chevronRight"), "Next month (Page Down)") { moveMonths(1) })
            isVisible = !dateless
        }
        grid.isVisible = !dateless
        val time = JPanel(MigLayout("insets 0, gap 2")).apply {
            isOpaque = false
            add(JLabel("Time"), "gapright 6")
            add(hour)
            add(JLabel(":"))
            add(minute)
            add(JLabel(":"))
            add(second)
        }
        val pick = JButton("Insert").apply { addActionListener { pick() } }
        content.add(header)
        content.add(grid)
        content.add(time, "split 2")
        content.add(pick, "gapleft push")
        contentPane = content
        rootPane.defaultButton = pick
        bind(rootPane, "ESCAPE", "logxyClosePicker", JComponent.WHEN_IN_FOCUSED_WINDOW) { dispose() }
        // Clicking elsewhere closes it, as a popup does.
        addWindowListener(object : WindowAdapter() {
            override fun windowDeactivated(e: WindowEvent) = dispose()
        })
        showMonth()
        pack()
    }

    private fun timeSpinner(value: Int, max: Int) = JSpinner(SpinnerNumberModel(value, 0, max, 1)).apply {
        editor = JSpinner.NumberEditor(this, "00")
        val text = (editor as JSpinner.DefaultEditor).textField
        text.columns = 2
        // The spinner's own field would keep Enter to commit its text.
        bind(text, "ENTER", "logxyPickTime", JComponent.WHEN_FOCUSED) {
            text.commitEdit()
            pick()
        }
    }

    private fun bind(component: JComponent, key: String, name: String, condition: Int, action: () -> Unit) {
        component.getInputMap(condition).put(KeyStroke.getKeyStroke(key), name)
        component.actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = action()
        })
    }

    private fun moveMonths(months: Long) {
        month = month.plusMonths(months)
        selected = month.atDay(minOf(selected.dayOfMonth, month.lengthOfMonth()))
        showMonth()
    }

    private fun moveDays(days: Long) {
        selected = selected.plusDays(days)
        month = YearMonth.from(selected)
        showMonth()
    }

    private fun showMonth() {
        title.text = "${month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())} ${month.year}"
        grid.repaint()
    }

    /** Writes the picked time into the field, and closes. */
    private fun pick() {
        val time = LocalTime.of(hour.value as Int, minute.value as Int, second.value as Int, fraction)
        val instant = LocalDateTime.of(selected, time).atZone(zone).toInstant()
        field.text = TimeLiteral.format(instant.epochSecond * NANOS_PER_SECOND + instant.nano, zone)
        dispose()
        field.requestFocusInWindow()
    }

    /** The days of [month] in weeks; a click selects a day, a double click picks it. */
    private inner class DayGrid : JComponent() {
        private val cell get() = look.scale(28)

        init {
            isFocusable = true
            bind(this, "LEFT", "logxyDayBefore", WHEN_FOCUSED) { moveDays(-1) }
            bind(this, "RIGHT", "logxyDayAfter", WHEN_FOCUSED) { moveDays(1) }
            bind(this, "UP", "logxyWeekBefore", WHEN_FOCUSED) { moveDays(-DAYS_PER_WEEK.toLong()) }
            bind(this, "DOWN", "logxyWeekAfter", WHEN_FOCUSED) { moveDays(DAYS_PER_WEEK.toLong()) }
            bind(this, "PAGE_UP", "logxyMonthBefore", WHEN_FOCUSED) { moveMonths(-1) }
            bind(this, "PAGE_DOWN", "logxyMonthAfter", WHEN_FOCUSED) { moveMonths(1) }
            bind(this, "ENTER", "logxyPickDay", WHEN_FOCUSED) { pick() }
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                    val day = dayAt(e.x, e.y) ?: return
                    selected = day
                    repaint()
                    if (e.clickCount == 2) pick()
                }
            })
        }

        override fun getPreferredSize() = Dimension(cell * DAYS_PER_WEEK, cell * (WEEKS_SHOWN + 1))

        /** The first day shown: the start of the week [month] begins in. */
        private fun firstShown(): LocalDate {
            val first = month.atDay(1)
            val back = (first.dayOfWeek.value - firstDay.value + DAYS_PER_WEEK) % DAYS_PER_WEEK
            return first.minusDays(back.toLong())
        }

        /** The day of [month] at [x], [y]; null for the weekday names, or a day of another month. */
        private fun dayAt(x: Int, y: Int): LocalDate? {
            val row = y / cell - 1
            val column = x / cell
            if (row !in 0..<WEEKS_SHOWN || column !in 0..<DAYS_PER_WEEK) return null
            val day = firstShown().plusDays((row * DAYS_PER_WEEK + column).toLong())
            return if (YearMonth.from(day) == month) day else null
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.font = font
                val metrics = g2.fontMetrics
                val foreground = UIManager.getColor("Label.foreground")
                val dim = UIManager.getColor("Label.disabledForeground")
                fun center(text: String, x: Int, y: Int) {
                    val baseline = y + (cell + metrics.ascent - metrics.descent) / 2
                    g2.drawString(text, x + (cell - metrics.stringWidth(text)) / 2, baseline)
                }
                g2.color = dim
                for (column in 0..<DAYS_PER_WEEK) {
                    val weekday = firstDay.plus(column.toLong())
                    val name = weekday.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault()).take(2)
                    center(name, column * cell, 0)
                }
                val first = firstShown()
                for (i in 0..<WEEKS_SHOWN * DAYS_PER_WEEK) {
                    val day = first.plusDays(i.toLong())
                    if (YearMonth.from(day) != month) continue
                    val bounds = Rectangle(i % DAYS_PER_WEEK * cell, (i / DAYS_PER_WEEK + 1) * cell, cell, cell)
                    val isSelected = day == selected
                    if (isSelected) {
                        g2.color = look.accent
                        val inset = look.scale(2)
                        val size = cell - 2 * inset
                        g2.fillRoundRect(bounds.x + inset, bounds.y + inset, size, size, inset * 3, inset * 3)
                    }
                    val text = if (isSelected) UIManager.getColor("List.selectionForeground") else foreground
                    g2.color = text
                    center("${day.dayOfMonth}", bounds.x, bounds.y)
                    // A dot under the days the log has records on.
                    if (day in days) {
                        g2.color = if (isSelected) text else look.accent
                        val dot = look.scale(4)
                        g2.fillOval(bounds.x + (cell - dot) / 2, bounds.y + cell - dot - look.scale(3), dot, dot)
                    }
                }
                if (isFocusOwner) {
                    g2.color = look.accent
                    g2.drawRect(0, cell, width - 1, height - cell - 1)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        /** Where a picker opens on [field] whose text is a date and time, as [TimeLiteral] reads it. */
        private val DATED = Regex("""\s*\d{4}-.*""")

        /**
         * Opens a picker under [field], on the time it holds if that is an absolute time, else on [log]'s fallback
         * time; does nothing if there is neither.
         */
        fun open(field: JTextField, log: TimePickerLog?) {
            val owner = SwingUtilities.getWindowAncestor(field) ?: return
            val zone = ZoneId.systemDefault()
            val fallback = log?.fallback
            // A time alone is absolute only in a log whose times carry no date either, which are all on 1970-01-01.
            val undatedLog = fallback != null && atZone(fallback, zone).toLocalDate() == LocalDate.EPOCH
            val typed = try {
                if (DATED.matches(field.text) || undatedLog) TimeLiteral.parse(field.text, zone) else null
            } catch (_: QueryException) {
                null
            }
            val start = typed ?: fallback ?: return
            val picker = TimePicker(owner, field, atZone(start, zone), log?.days.orEmpty())
            picker.place(field)
            picker.isVisible = true
            if (picker.dateless) picker.hour.requestFocusInWindow() else picker.grid.requestFocusInWindow()
        }

        private fun atZone(nanos: Long, zone: ZoneId): ZonedDateTime =
            Instant.ofEpochSecond(Math.floorDiv(nanos, NANOS_PER_SECOND), Math.floorMod(nanos, NANOS_PER_SECOND))
                .atZone(zone)
    }

    /** Under [field], its left edge on the field's, kept on the screen. */
    private fun place(field: JComponent) {
        val at = field.locationOnScreen
        val screen = field.graphicsConfiguration?.bounds
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val x = at.x.coerceIn(screen.x, maxOf(screen.x, screen.x + screen.width - width))
        val below = at.y + field.height
        val y = if (below + height <= screen.y + screen.height) below else maxOf(screen.y, at.y - height)
        setLocation(x, y)
    }
}
