package io.github.rastislavpapp.logxy.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeOfDayFieldTest {

    private val days = ArrayList<Int>()

    private fun field(time: String): TimeOfDayField {
        val (hours, minutes, seconds) = time.split(':').map(String::toInt)
        return TimeOfDayField(hours * 3600 + minutes * 60 + seconds, onNextDay = { days += it })
    }

    private fun TimeOfDayField.type(digits: String) = digits.forEach { typeDigit(it.digitToInt()) }

    @Test
    fun `digits typed fill the parts from the hours on`() {
        val field = field("00:00:00")
        field.type("143205")
        assertEquals("14:32:05", field.text)
    }

    @Test
    fun `a digit no second digit can follow moves on to the next part`() {
        val field = field("00:00:00")
        // 7 can only be 07 o'clock, 9 only 09 minutes.
        field.type("79")
        assertEquals("07:09:00", field.text)
        // 2 then 5 would be hour 25: the 5 starts the hours again.
        val late = field("00:00:00")
        late.type("25")
        assertEquals("05:00:00", late.text)
    }

    @Test
    fun `the part being edited is selected`() {
        val field = field("08:30:00")
        assertEquals(0 to 2, field.selectionStart to field.selectionEnd)
        field.movePart(1)
        assertEquals(3 to 5, field.selectionStart to field.selectionEnd)
        field.movePart(5)
        assertEquals(6 to 8, field.selectionStart to field.selectionEnd)
    }

    @Test
    fun `stepping a part carries into the larger ones and over midnight`() {
        val field = field("10:59:59")
        field.movePart(2)
        field.step(up = true)
        assertEquals("11:00:00", field.text)

        val late = field("23:59:59")
        late.movePart(2)
        late.step(up = true)
        assertEquals("00:00:00", late.text)
        assertEquals(listOf(1), days)

        val early = field("00:20:00")
        early.step(up = false)
        assertEquals("23:20:00", early.text)
        assertEquals(listOf(1, -1), days)
    }

    @Test
    fun `clearing a part sets it to zero`() {
        val field = field("14:32:05")
        field.movePart(1)
        field.clearPart()
        assertEquals("14:00:05", field.text)
        assertEquals(14 * 3600 + 5, field.secondOfDay)
    }
}
