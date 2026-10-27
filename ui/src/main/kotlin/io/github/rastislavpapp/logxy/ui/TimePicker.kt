package io.github.rastislavpapp.logxy.ui

import io.github.rastislavpapp.logxy.core.query.QueryException
import io.github.rastislavpapp.logxy.core.query.TimeLiteral
import net.miginfocom.swing.MigLayout
import java.awt.AWTEvent
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.RoundRectangle2D
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.JSeparator
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private const val NANOS_PER_SECOND = 1_000_000_000L
private const val NANOS_PER_MILLI = 1_000_000
private const val DAYS_PER_WEEK = 7
private const val WEEKS_SHOWN = 6
private const val ARC = 12

/** The client property of a field that holds the picker open on it. */
private const val OPEN_PICKER = "logxyTimePicker"

/** What a time picker opens on and marks, taken from the shown log as it opens. */
class TimePickerLog(
    /** The time to open on while the field holds no time: the selected row's, else the first shown record's. */
    val fallback: Long?,
    /** Whether the log has a record, shown or not, from one time to before another; the days that have are marked. */
    val hasRecords: (from: Long, to: Long) -> Boolean,
)

/** A time a picker offers next to the calendar, worked out from now: [at]. */
internal enum class TimeShortcut(
    val label: String,
    private val from: (now: ZonedDateTime, firstDay: DayOfWeek) -> ZonedDateTime,
) {
    NOW("Now", { now, _ -> now }),
    MINUTES_15("15 minutes ago", { now, _ -> now.minusMinutes(15) }),
    HOUR_1("1 hour ago", { now, _ -> now.minusHours(1) }),
    HOURS_24("24 hours ago", { now, _ -> now.minusHours(24) }),
    TODAY("Today", { now, _ -> now.truncatedTo(ChronoUnit.DAYS) }),
    YESTERDAY("Yesterday", { now, _ -> now.truncatedTo(ChronoUnit.DAYS).minusDays(1) }),
    THIS_WEEK("This week", { now, firstDay ->
        now.truncatedTo(ChronoUnit.DAYS).with(TemporalAdjusters.previousOrSame(firstDay))
    }),
    DAYS_7("7 days ago", { now, _ -> now.minusDays(7) }),
    ;

    /** The time from [now], to the second, with weeks starting on [firstDay]. */
    fun at(now: ZonedDateTime, firstDay: DayOfWeek): ZonedDateTime = from(now.withNano(0), firstDay)
}

/**
 * A button for the end of [field] that opens a [TimePicker] under it, or closes the one open. [log] gives what the
 * picker opens on; null when there is nothing to pick from.
 */
fun timePickerButton(field: JTextField, log: () -> TimePickerLog?) =
    toolBarButton(CalendarIcon, "Pick a date and time") { TimePicker.toggle(field, log()) }

/** Makes a click in this field open a [TimePicker] under it while [active], with what [log] gives. */
fun JTextField.picksTimeOnClick(log: () -> TimePickerLog?, active: () -> Boolean) {
    val field = this
    addMouseListener(object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            if (SwingUtilities.isLeftMouseButton(e) && active() && field.isEnabled) TimePicker.open(field, log())
        }
    })
}

/**
 * A popup under a time field: a month calendar with the days that have records marked, a [TimeOfDayField], and
 * [TimeShortcut]s. The field keeps the focus and can still be typed in: the picker follows a time typed there, and
 * writes each day or time picked into it as [TimeLiteral.format] has it, changing nothing else, so that Enter in the
 * field then does what it does with typed text. Finer parts than seconds are kept from a typed time. Times are in the
 * zone the filter uses; for a log whose timestamps carry no date, which [TimeLiteral] puts on 1970-01-01, only the
 * time shows.
 *
 * Down in the field, or a click on the time, goes into the picker: there arrows move the day, Page Up and Page Down
 * the month, Enter and a double click on a day close it, as a shortcut does at once. Esc closes it, and so does
 * clicking elsewhere.
 */
internal class TimePicker private constructor(
    owner: Window,
    private val field: JTextField,
    start: ZonedDateTime,
    /** The day a time alone typed in the field falls on. */
    private val referenceDay: LocalDate,
    private val log: TimePickerLog?,
) : JDialog(owner) {

    private val zone = start.zone
    private var fraction = start.nano
    private val dateless = start.toLocalDate() == LocalDate.EPOCH
    private var selected: LocalDate = start.toLocalDate()
    private var month: YearMonth = YearMonth.from(selected)
    private val firstDay: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    private val today = LocalDate.now(zone)

    /** Whether each day asked about has records. */
    private val recordDays = HashMap<LocalDate, Boolean>()

    private val title = JLabel("", SwingConstants.CENTER)
    private val grid = DayGrid()
    private val time = TimeOfDayField(start.toLocalTime().toSecondOfDay(), ::timeCarried, ::write)

    /** What the time keeps of a typed time below a second. */
    private val kept = JLabel().apply {
        foreground = UIManager.getColor("Label.disabledForeground")
        toolTipText = "Kept from the time typed"
    }

    /** Set while the picker writes into the field, so that it does not follow its own text. */
    private var writing = false

    /** Undoes what the picker hooked into the field, its window and the toolkit. */
    private val unhooks = ArrayList<() -> Unit>()

    init {
        isUndecorated = true
        // A look that draws window decorations itself, such as FlatLaf's, would still give it a title bar.
        rootPane.windowDecorationStyle = JRootPane.NONE
        // The field keeps the focus, to be typed in, until the user goes into the picker.
        focusableWindowState = false
        val content = RoundedPanel(MigLayout("insets 10 12 10 8, gap 0, hidemode 3", "[]12[]8[]"))
        val header = JPanel(MigLayout("insets 0, gap 0", "[][grow, fill][]")).apply {
            isOpaque = false
            add(toolBarButton(Icons.get("general/chevronLeft"), "Previous month") { showMonth(month.minusMonths(1)) })
            add(title)
            add(toolBarButton(Icons.get("general/chevronRight"), "Next month") { showMonth(month.plusMonths(1)) })
        }
        val timeRow = JPanel(MigLayout("insets 0, gap 0", "[]6[]2[]")).apply {
            isOpaque = false
            add(JLabel("Time"))
            add(time)
            add(kept)
        }
        val calendar = JPanel(MigLayout("insets 0, gap 0, wrap 1, hidemode 3", "[fill]")).apply {
            isOpaque = false
            add(header, "gapbottom 4")
            add(grid)
            add(timeRow, "gaptop 8, align left")
        }
        header.isVisible = !dateless
        grid.isVisible = !dateless
        content.add(calendar, "top")
        val separator = JSeparator(SwingConstants.VERTICAL)
        content.add(separator, "growy")
        val shortcuts = shortcutList()
        content.add(shortcuts, "top")
        separator.isVisible = !dateless
        shortcuts.isVisible = !dateless
        contentPane = content
        val inside = JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT
        bind(content, "ESCAPE", "logxyClosePicker", inside) { closeToField() }
        bind(content, "ENTER", "logxyPickTime", inside) {
            write()
            closeToField()
        }
        time.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = enter(time)
        })
        showFraction()
        showMonth(month)
        pack()
        roundCorners()
    }

    private fun shortcutList() = JPanel(MigLayout("insets 0, gap 0, wrap 1", "[fill, grow]")).apply {
        isOpaque = false
        for (shortcut in TimeShortcut.entries) {
            val button = toolBarButton(null, "Insert this time, worked out from now", shortcut.label) {
                show(shortcut.at(ZonedDateTime.now(zone), firstDay))
                write()
                closeToField()
            }
            button.horizontalAlignment = SwingConstants.LEADING
            button.margin = Insets(4, 8, 4, 16)
            add(button, "wmin pref")
        }
    }

    /** Clips the window to the panel's rounded corners, where the screen can show windows of any shape. */
    private fun roundCorners() {
        val device = graphicsConfiguration?.device ?: return
        if (!device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT)) return
        val arc = look.scale(ARC).toDouble()
        shape = RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), arc, arc)
    }

    private fun bind(component: JComponent, key: String, name: String, condition: Int, action: () -> Unit) {
        component.getInputMap(condition).put(KeyStroke.getKeyStroke(key), name)
        component.actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = action()
        })
    }

    /** Hooks the picker to the field and its window, so that it follows the text and closes when it should. */
    private fun hook(owner: Window) {
        val text = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = follow()
            override fun removeUpdate(e: DocumentEvent) = follow()
            override fun changedUpdate(e: DocumentEvent) = follow()
        }
        field.document.addDocumentListener(text)
        unhooks += { field.document.removeDocumentListener(text) }

        // Ahead of the field's own key bindings: Esc closes only the picker, Down goes into it.
        val keys = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ESCAPE -> dispose()
                    KeyEvent.VK_DOWN -> enter(if (dateless) time else grid)
                    KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> return dispose()
                    else -> return
                }
                e.consume()
            }
        }
        field.addKeyListener(keys)
        unhooks += { field.removeKeyListener(keys) }

        val focus = object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                if (!e.isTemporary && !inPicker(e.oppositeComponent)) dispose()
            }
        }
        field.addFocusListener(focus)
        unhooks += { field.removeFocusListener(focus) }

        val hierarchy = HierarchyListener { e ->
            val showingChanged = e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L
            if (showingChanged && !field.isShowing) dispose()
        }
        field.addHierarchyListener(hierarchy)
        unhooks += { field.removeHierarchyListener(hierarchy) }

        // A click anywhere but in the picker or the field, its buttons included, closes it.
        val clicks = AWTEventListener { event ->
            val press = event as? MouseEvent
            if (press?.id == MouseEvent.MOUSE_PRESSED) {
                val source = press.component
                if (!inPicker(source) && !SwingUtilities.isDescendingFrom(source, field)) dispose()
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(clicks, AWTEvent.MOUSE_EVENT_MASK)
        unhooks += { Toolkit.getDefaultToolkit().removeAWTEventListener(clicks) }

        val moves = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = dispose()
            override fun componentResized(e: ComponentEvent) = dispose()
        }
        owner.addComponentListener(moves)
        unhooks += { owner.removeComponentListener(moves) }
        val ownerWindow = object : WindowAdapter() {
            override fun windowIconified(e: WindowEvent) = dispose()
            override fun windowDeactivated(e: WindowEvent) {
                if (e.oppositeWindow !== this@TimePicker) dispose()
            }
        }
        owner.addWindowListener(ownerWindow)
        unhooks += { owner.removeWindowListener(ownerWindow) }

        // Back in the field's window, typing goes there again, as a click elsewhere in it closes the picker; anywhere
        // else the picker closes.
        addWindowFocusListener(object : WindowAdapter() {
            override fun windowLostFocus(e: WindowEvent) {
                if (e.oppositeWindow === owner) focusableWindowState = false else dispose()
            }
        })
    }

    private fun inPicker(component: Component?) = component != null && SwingUtilities.isDescendingFrom(component, this)

    override fun dispose() {
        unhooks.forEach { it() }
        unhooks.clear()
        if (field.getClientProperty(OPEN_PICKER) === this) field.putClientProperty(OPEN_PICKER, null)
        super.dispose()
    }

    /** Takes the focus from the field into the picker, to [component]. */
    private fun enter(component: JComponent) {
        if (!focusableWindowState) {
            focusableWindowState = true
            toFront()
        }
        component.requestFocus()
    }

    private fun closeToField() {
        dispose()
        field.requestFocusInWindow()
    }

    /** Shows the time typed in the field, if it is one. */
    private fun follow() {
        if (writing) return
        show(parse(field.text, zone, referenceDay) ?: return)
    }

    /** Shows [time] in the calendar and the time field. */
    private fun show(time: ZonedDateTime) {
        val local = time.withZoneSameInstant(zone)
        if (!dateless) selected = local.toLocalDate()
        month = YearMonth.from(selected)
        this.time.showSecondOfDay(local.toLocalTime().toSecondOfDay())
        fraction = local.nano
        showFraction()
        showMonth(month)
    }

    private fun showFraction() {
        kept.isVisible = fraction != 0
        kept.text = if (fraction % NANOS_PER_MILLI == 0) {
            ".%03d".format(fraction / NANOS_PER_MILLI)
        } else {
            ".%09d".format(fraction)
        }
    }

    /** Writes the day and time picked into the field. */
    private fun write() {
        val timeOfDay = LocalTime.ofSecondOfDay(time.secondOfDay.toLong()).withNano(fraction)
        val instant = LocalDateTime.of(selected, timeOfDay).atZone(zone).toInstant()
        writing = true
        try {
            field.text = TimeLiteral.format(instant.epochSecond * NANOS_PER_SECOND + instant.nano, zone)
        } finally {
            writing = false
        }
    }

    // A dateless log has one day only: its time wraps around midnight.
    private fun timeCarried(days: Int) {
        if (!dateless) select(selected.plusDays(days.toLong()))
    }

    /** Selects [day], showing its month. */
    private fun select(day: LocalDate) {
        selected = day
        showMonth(YearMonth.from(day))
    }

    private fun showMonth(shown: YearMonth) {
        month = shown
        title.text = "${month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())} ${month.year}"
        grid.repaint()
    }

    private fun hasRecords(day: LocalDate): Boolean {
        val hasRecords = log?.hasRecords ?: return false
        return recordDays.getOrPut(day) {
            val from = day.atStartOfDay(zone).toInstant()
            val to = day.plusDays(1).atStartOfDay(zone).toInstant()
            hasRecords(from.epochSecond * NANOS_PER_SECOND, to.epochSecond * NANOS_PER_SECOND)
        }
    }

    /**
     * Six weeks from the one [month] begins in, days of the months around it dimmed; a click selects a day and writes
     * it into the field, a double click also closes the picker.
     */
    private inner class DayGrid : JComponent() {
        private val cell get() = look.scale(36)
        private var hovered: LocalDate? = null

        init {
            isFocusable = true
            fun move(key: String, name: String, days: Long) = bind(this, key, name, WHEN_FOCUSED) {
                select(selected.plusDays(days))
                write()
            }
            move("LEFT", "logxyDayBefore", -1)
            move("RIGHT", "logxyDayAfter", 1)
            move("UP", "logxyWeekBefore", -DAYS_PER_WEEK.toLong())
            move("DOWN", "logxyWeekAfter", DAYS_PER_WEEK.toLong())
            bind(this, "PAGE_UP", "logxyMonthBefore", WHEN_FOCUSED) {
                select(selected.minusMonths(1))
                write()
            }
            bind(this, "PAGE_DOWN", "logxyMonthAfter", WHEN_FOCUSED) {
                select(selected.plusMonths(1))
                write()
            }
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val day = dayAt(e.x, e.y) ?: return
                    select(day)
                    write()
                    if (e.clickCount == 2) closeToField()
                }

                override fun mouseMoved(e: MouseEvent) = hover(dayAt(e.x, e.y))
                override fun mouseExited(e: MouseEvent) = hover(null)
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
            addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) = repaint()
                override fun focusLost(e: FocusEvent) = repaint()
            })
        }

        private fun hover(day: LocalDate?) {
            if (day == hovered) return
            hovered = day
            repaint()
        }

        override fun getPreferredSize() = Dimension(cell * DAYS_PER_WEEK, cell * (WEEKS_SHOWN + 1))

        /** The first day shown: the start of the week [month] begins in. */
        private fun firstShown(): LocalDate {
            val first = month.atDay(1)
            val back = (first.dayOfWeek.value - firstDay.value + DAYS_PER_WEEK) % DAYS_PER_WEEK
            return first.minusDays(back.toLong())
        }

        /** The day at [x], [y]; null for the weekday names. */
        private fun dayAt(x: Int, y: Int): LocalDate? {
            val row = y / cell - 1
            val column = x / cell
            if (row !in 0..<WEEKS_SHOWN || column !in 0..<DAYS_PER_WEEK) return null
            return firstShown().plusDays((row * DAYS_PER_WEEK + column).toLong())
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.font = font
                paintWeekdays(g2)
                val first = firstShown()
                for (i in 0..<WEEKS_SHOWN * DAYS_PER_WEEK) {
                    val bounds = Rectangle(i % DAYS_PER_WEEK * cell, (i / DAYS_PER_WEEK + 1) * cell, cell, cell)
                    paintDay(g2, first.plusDays(i.toLong()), bounds)
                }
            } finally {
                g2.dispose()
            }
        }

        private fun paintWeekdays(g2: Graphics2D) {
            g2.color = UIManager.getColor("Label.disabledForeground")
            for (column in 0..<DAYS_PER_WEEK) {
                val weekday = firstDay.plus(column.toLong())
                val name = weekday.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault())
                center(g2, name, Rectangle(column * cell, 0, cell, cell))
            }
        }

        private fun paintDay(g2: Graphics2D, day: LocalDate, bounds: Rectangle) {
            val inset = look.scale(3)
            val circle = Rectangle(bounds.x + inset, bounds.y + inset, cell - 2 * inset, cell - 2 * inset)
            val foreground = UIManager.getColor("Label.foreground")
            val isSelected = day == selected
            when {
                isSelected -> {
                    g2.color = look.accent
                    g2.fillOval(circle.x, circle.y, circle.width, circle.height)
                    if (isFocusOwner) {
                        g2.color = foreground
                        g2.drawOval(circle.x - 1, circle.y - 1, circle.width + 1, circle.height + 1)
                    }
                }
                day == hovered -> {
                    g2.color = Color(foreground.red, foreground.green, foreground.blue, 32)
                    g2.fillOval(circle.x, circle.y, circle.width, circle.height)
                }
            }
            if (day == today && !isSelected) {
                g2.color = look.accent
                g2.drawOval(circle.x, circle.y, circle.width - 1, circle.height - 1)
            }
            val inMonth = YearMonth.from(day) == month
            g2.color = when {
                isSelected -> UIManager.getColor("List.selectionForeground")
                inMonth -> foreground
                else -> UIManager.getColor("Label.disabledForeground")
            }
            center(g2, "${day.dayOfMonth}", bounds)
            // A dot under the days the log has records on.
            if (hasRecords(day)) {
                if (!isSelected) g2.color = look.accent
                val dot = look.scale(4)
                g2.fillOval(bounds.x + (cell - dot) / 2, circle.y + circle.height - dot - look.scale(3), dot, dot)
            }
        }

        private fun center(g2: Graphics2D, text: String, bounds: Rectangle) {
            val metrics = g2.fontMetrics
            val baseline = bounds.y + (bounds.height + metrics.ascent - metrics.descent) / 2
            g2.drawString(text, bounds.x + (bounds.width - metrics.stringWidth(text)) / 2, baseline)
        }
    }

    /** The picker's background, rounded, with a line around it. */
    private class RoundedPanel(layout: MigLayout) : JPanel(layout) {
        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = look.scale(ARC)
                g2.color = UIManager.getColor("Panel.background")
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = look.border
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        /** Opens a picker under [field] unless one is open there, or closes it. */
        fun toggle(field: JTextField, log: TimePickerLog?) {
            val open = field.getClientProperty(OPEN_PICKER) as TimePicker?
            if (open != null) open.dispose() else open(field, log)
        }

        /**
         * Opens a picker under [field], unless one is open there, on the time it holds if that is an absolute time (a
         * time alone on the day of [log]'s fallback time, as Go to reads it), else on the fallback time to the second;
         * does nothing if there is neither.
         */
        fun open(field: JTextField, log: TimePickerLog?) {
            if (field.getClientProperty(OPEN_PICKER) != null) return
            val owner = SwingUtilities.getWindowAncestor(field) ?: return
            val zone = ZoneId.systemDefault()
            val fallback = log?.fallback?.let { atZone(it, zone) }
            val day = fallback?.toLocalDate() ?: LocalDate.EPOCH
            val start = parse(field.text, zone, day) ?: fallback?.withNano(0) ?: return
            val picker = TimePicker(owner, field, start, day, log)
            picker.hook(owner)
            field.putClientProperty(OPEN_PICKER, picker)
            picker.place(field)
            picker.isVisible = true
            field.requestFocusInWindow()
        }

        private fun parse(text: String, zone: ZoneId, day: LocalDate): ZonedDateTime? = try {
            atZone(TimeLiteral.parse(text, zone, day), zone)
        } catch (_: QueryException) {
            null
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
        val below = at.y + field.height + look.scale(2)
        val y = if (below + height <= screen.y + screen.height) below else maxOf(screen.y, at.y - height)
        setLocation(x, y)
    }
}
