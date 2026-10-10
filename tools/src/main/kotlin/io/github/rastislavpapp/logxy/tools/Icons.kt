package io.github.rastislavpapp.logxy.tools

import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.view.ViewBox
import java.awt.Component
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

private val ICO_SIZES = intArrayOf(256, 128, 64, 48, 32, 24, 16)

/** Renders the app logo into the PNG and ICO that jpackage needs; run after changing the SVG. */
fun generateIcons(svg: Path, outDir: Path) {
    val document = SVGLoader().load(svg.toUri().toURL(), LoaderContext.createDefault())
        ?: error("cannot parse $svg")
    // Each size is rendered from the vector, which keeps small sizes sharper than downscaling.
    val pngs = ICO_SIZES.map { size ->
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        document.render(null as Component?, g, ViewBox(0f, 0f, size.toFloat(), size.toFloat()))
        g.dispose()
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
    Files.createDirectories(outDir)
    Files.write(outDir.resolve("logxy.png"), pngs.first())
    Files.write(outDir.resolve("logxy.ico"), ico(pngs))
    println("Wrote logxy.png and logxy.ico (${ICO_SIZES.joinToString()} px) to $outDir")
}

/** An ICO whose entries are PNGs, which Windows has accepted since Vista. */
private fun ico(pngs: List<ByteArray>): ByteArray {
    val headerSize = 6 + 16 * pngs.size
    val buffer = ByteBuffer.allocate(headerSize + pngs.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putShort(0).putShort(1).putShort(pngs.size.toShort())
    var offset = headerSize
    pngs.forEachIndexed { i, png ->
        val size = ICO_SIZES[i]
        buffer.put((size and 0xFF).toByte()).put((size and 0xFF).toByte()) // 0 means 256
        buffer.put(0).put(0).putShort(1).putShort(32).putInt(png.size).putInt(offset)
        offset += png.size
    }
    pngs.forEach(buffer::put)
    return buffer.array()
}
