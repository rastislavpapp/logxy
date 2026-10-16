package io.github.rastislavpapp.logxy.core.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VmOptionsTest {

    @TempDir
    lateinit var dir: Path

    private val shipped = "-XX:MaxRAMPercentage=50\n"

    /** An app directory as the archive ships it, and a settings directory with nothing in it yet. */
    private fun fresh(): VmOptions {
        val app = Files.createDirectories(dir.resolve("app"))
        Files.writeString(app.resolve(VmOptions.DEFAULT_FILE), shipped)
        Files.writeString(app.resolve(VmOptions.FILE), shipped)
        return VmOptions(app.resolve(VmOptions.FILE), dir.resolve("settings").resolve(VmOptions.FILE))
    }

    @Test
    fun `first start saves the defaults as the user's without changing the launcher's`() {
        val options = fresh()
        assertEquals(VmOptions.Install.UNCHANGED, options.install())
        assertEquals(shipped, Files.readString(options.user))
        assertFalse(options.installFailed)
    }

    @Test
    fun `an upgrade gets the user's options back once`() {
        val options = fresh()
        Files.createDirectories(options.user.parent)
        Files.writeString(options.user, "-Xmx8g\n")
        assertEquals(VmOptions.Install.CHANGED, options.install())
        assertEquals("-Xmx8g\n", Files.readString(options.installed))
        assertEquals(VmOptions.Install.UNCHANGED, options.install())
    }

    @Test
    fun `saving installs the options, and failing to leaves the user's saved`() {
        val options = fresh()
        assertEquals(VmOptions.Install.CHANGED, options.save(listOf("-Xmx4g")))
        assertEquals(listOf("-Xmx4g"), options.current())
        assertEquals("-Xmx4g\n", Files.readString(options.installed))
        assertFalse(options.installFailed)

        // A file where the app directory should be stands in for one the user cannot write to.
        Files.writeString(dir.resolve("read-only"), "")
        val stuck = VmOptions(dir.resolve("read-only/app/${VmOptions.FILE}"), options.user)
        assertEquals(VmOptions.Install.FAILED, stuck.save(listOf("-Xmx2g")))
        assertEquals(listOf("-Xmx2g"), stuck.current())
        assertTrue(stuck.installFailed)
    }

    @Test
    fun `an edit to the user's file does not count as a failed install`() {
        val options = fresh()
        options.install()
        Files.writeString(options.user, "-Xmx8g\n")
        assertFalse(options.installFailed)
    }

    @Test
    fun `memory limit is read as written`() {
        assertEquals("8g", VmOptions.memoryLimit(listOf("-Xmx8g")))
        assertEquals("6144M", VmOptions.memoryLimit(listOf("-Xmx2g", "-Xmx6144M")))
        assertEquals("8gb", VmOptions.memoryLimit(listOf("-Xmx8gb")))
        assertNull(VmOptions.memoryLimit(listOf("-XX:MaxRAMPercentage=50")))
    }

    @Test
    fun `setting the memory limit keeps the other options`() {
        val defaults = listOf("-XX:MaxRAMPercentage=50", "-Dshipped=1")
        val limited = VmOptions.withMemoryLimit(defaults + "-Dmine=1", "1024m", defaults)
        assertEquals(listOf("-Dshipped=1", "-Dmine=1", "-Xmx1024m"), limited)
        assertEquals(
            listOf("-Dshipped=1", "-Dmine=1", "-XX:MaxRAMPercentage=50"),
            VmOptions.withMemoryLimit(limited, null, defaults),
        )
    }
}
