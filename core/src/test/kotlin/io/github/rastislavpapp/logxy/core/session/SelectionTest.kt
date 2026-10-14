package io.github.rastislavpapp.logxy.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectionTest {

    private fun Selection.ranges() = buildList { rows.forEachRange { start, end -> add(start..<end) } }

    @Test
    fun `extending goes from the anchor to the row, wherever both are`() {
        val selection = Selection()
        // A table window of a million rows starts at 2,000,000; the anchor was set far above it.
        selection.select(10, 10)
        selection.extendTo(2_500_000)
        assertEquals(listOf(10..<2_500_001), selection.ranges())
        assertEquals(10, selection.anchor)
        assertEquals(2_500_000, selection.lead)
        // Back up, past the anchor: the range now ends at it.
        selection.extendTo(5)
        assertEquals(listOf(5..<11), selection.ranges())
    }

    @Test
    fun `an anchor just above the table's window is outside it, and extending still starts there`() {
        val selection = Selection()
        val window = TableWindow(1000, 2000)
        selection.select(999, 999)
        assertEquals(-1, window.indexOf(selection.anchor))
        // Shift-click on the window's row 500, from below the anchor and past the window's end.
        selection.extendTo(window.rowAt(500))
        assertEquals(listOf(999..<1501), selection.ranges())
        assertEquals(0, selection.minIn(window))
        assertEquals(500, selection.maxIn(window))
        selection.extendTo(2500)
        assertEquals(999, selection.maxIn(window))
        assertEquals(-1, selection.minIn(TableWindow(3000, 4000)))
    }

    @Test
    fun `extending while keeping other rows follows the anchor's state`() {
        val selection = Selection()
        selection.select(0, 4)
        selection.add(10, 10)
        // Ctrl+Shift+click from a selected anchor adds the rows up to the click.
        selection.extendKeepingOthers(12)
        assertEquals(listOf(0..<5, 10..<13), selection.ranges())
        // From an anchor that is not selected, it deselects them.
        selection.remove(3, 3)
        selection.extendKeepingOthers(1)
        assertEquals(listOf(0..<1, 4..<5, 10..<13), selection.ranges())
    }

    @Test
    fun `rows shown after select all are not selected`() {
        val selection = Selection()
        selection.selectAll(100)
        selection.insert(100, 50)
        assertEquals(listOf(0..<100), selection.ranges())
    }

    @Test
    fun `extending without an anchor selects the row alone`() {
        val selection = Selection()
        selection.extendTo(7)
        assertEquals(listOf(7..<8), selection.ranges())
        assertEquals(7, selection.anchor)
    }

    @Test
    fun `select all takes the rows there are and keeps anchor and lead`() {
        val selection = Selection()
        selection.select(3, 3)
        selection.selectAll(50_000_000)
        assertEquals(listOf(0..<50_000_000), selection.ranges())
        assertEquals(50_000_000, selection.count)
        assertEquals(3, selection.lead)
        assertEquals(3, selection.anchor)
    }

    @Test
    fun `adding and removing keep other rows and move anchor and lead`() {
        val selection = Selection()
        selection.select(0, 9)
        selection.add(20, 29)
        selection.remove(5, 22)
        assertEquals(listOf(0..<5, 23..<30), selection.ranges())
        assertEquals(5, selection.anchor)
        assertEquals(22, selection.lead)
    }

    @Test
    fun `moving the lead from a selected anchor selects up to it`() {
        val selection = Selection()
        selection.select(10, 20)
        selection.setLead(15)
        assertEquals(listOf(10..<16), selection.ranges())
        // From an anchor that is not selected, the rows up to the new lead are deselected.
        selection.select(0, 30)
        selection.remove(10, 10)
        selection.setLead(5)
        assertEquals(listOf(0..<5, 11..<31), selection.ranges())
    }

    @Test
    fun `deleted rows leave the selection and the rows after them move up`() {
        val selection = Selection()
        selection.select(10, 40)
        selection.delete(30, 50)
        assertEquals(listOf(10..<30), selection.ranges())
        // The lead was deleted: like in Swing, it moves to the row before them.
        assertEquals(29, selection.lead)
        selection.clear()
        assertTrue(selection.isEmpty())
    }
}
