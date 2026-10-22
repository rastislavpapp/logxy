package io.github.rastislavpapp.logxy.plugin

import kotlin.test.Test
import kotlin.test.assertEquals

class AnsiStripperTest {

    private val stripper = AnsiStripper()

    @Test
    fun `colour codes are removed`() {
        assertEquals("WARN step 4\n", stripper.strip("\u001B[31mWARN \u001B[0mstep 4\n"))
    }

    @Test
    fun `a code split across chunks is removed`() {
        val first = stripper.strip("INFO \u001B[3")
        val second = stripper.strip("2mdone\n")

        assertEquals("INFO done\n", first + second)
    }

    @Test
    fun `a charset selection is removed`() {
        assertEquals("done\n", stripper.strip("\u001B(Bdone\n"))
    }

    @Test
    fun `a hyperlink keeps its text`() {
        val first = stripper.strip("see \u001B]8;;file:///etc/hosts\u001B")
        val second = stripper.strip("\\hosts\u001B]8;;\u0007\n")

        assertEquals("see hosts\n", first + second)
    }
}
