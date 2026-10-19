package io.github.rastislavpapp.logxy.core.index

import org.junit.jupiter.api.Tag
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/** Run by `testWithoutVectorModule` in a JVM without the Vector API module, as `java -jar logxy.jar` starts. */
@Tag("without-vector-module")
class NewlineScannerWithoutVectorModuleTest {

    @Test
    fun `falls back to SWAR and still finds newlines`() {
        assertEquals("SWAR", NewlineScanner.implementation)
        val buffer = ByteBuffer.wrap("one\ntwo\n\nfour".toByteArray())
        val starts = LongChunkList()
        NewlineScanner.scan(buffer, 0, buffer.limit(), 100, starts)
        assertEquals(listOf(104L, 108L, 109L), List(starts.size) { starts[it] })
        assertEquals(7, NewlineScanner.indexOf(buffer, 4, buffer.limit()))
    }
}
