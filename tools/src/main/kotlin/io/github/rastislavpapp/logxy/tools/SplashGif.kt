package io.github.rastislavpapp.logxy.tools

import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.view.ViewBox
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBuffer
import java.awt.image.IndexColorModel
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.ceil

private const val WIDTH = 480
private const val HEIGHT = 300
private const val HEADER = 51
private const val ROW = 22
private const val FEED_TOP = HEADER + 14
private const val FEED_ROWS = 10
private const val INSET = 18

/** Hundredths of a second per frame; the feed moves up one line per frame. */
private const val DELAY = 40

/** Scales the JVM looks for next to `splash.gif` on a scaled screen, as `splash@150pct.gif`, `splash@2x.gif`. */
private val SCALES = listOf(1.0 to "", 1.25 to "@125pct", 1.5 to "@150pct", 1.75 to "@175pct", 2.0 to "@2x")

private val BACKGROUND = Color(0x2B2D30)
private val HEADER_BACKGROUND = Color(0x3C3F41)
private val BORDER = Color(0x43454A)
private val TITLE = Color(0xDFE1E5)
private val TIME = Color(0x8C8F96)
private val TEXT = Color(0xBCBEC4)

private class Line(val time: String, val level: String, val levelColor: Color, val message: String)

private val INFO = Color(0x5FD38A)
private val DEBUG = Color(0x9DA0A8)
private val WARN = Color(0xFFC24B)

/**
 * The feed's lines, repeated: after one round the frame matches the first, so the loop has no seam. They are an
 * imaginary web service's log, so nobody reads them as logxy reporting its own startup.
 */
private val LINES = listOf(
    Line("09:14:02.118", "INFO", INFO, "GET /api/orders 200 12ms"),
    Line("09:14:02.131", "DEBUG", DEBUG, "Cache hit user:4821"),
    Line("09:14:02.164", "INFO", INFO, "POST /api/cart 201 31ms"),
    Line("09:14:02.177", "DEBUG", DEBUG, "Pool 8/20 connections busy"),
    Line("09:14:02.201", "INFO", INFO, "GET /api/products 200 9ms"),
    Line("09:14:02.219", "WARN", WARN, "Slow query orders_by_day 840ms"),
    Line("09:14:02.239", "INFO", INFO, "GET /health 200 1ms"),
    Line("09:14:02.276", "INFO", INFO, "Payment 7f3a settled"),
)

/**
 * Renders the splash screen the launcher shows until the main window opens: a log tailing upwards, as an
 * animated GIF, which the JVM plays natively while it starts. One file per screen scale; run after changing it.
 */
fun generateSplash(logo: Path, outDir: Path) {
    val document = SVGLoader().load(logo.toUri().toURL(), LoaderContext.createDefault())
        ?: error("cannot parse $logo")
    val fonts = Fonts()
    Files.createDirectories(outDir)
    for ((scale, suffix) in SCALES) {
        val frames = LINES.indices.map { offset ->
            render(scale) { g ->
                drawChrome(g, fonts) { size -> document.render(null as Component?, g, size) }
                drawFeed(g, fonts, offset)
            }
        }
        val file = outDir.resolve("splash$suffix.gif")
        writeGif(file, frames)
        println("Wrote $file (${Files.size(file) / 1024} KB)")
    }
}

private class Fonts {
    val title: Font = load("/com/formdev/flatlaf/fonts/inter/Inter-SemiBold.otf").deriveFont(16f)
    val mono: Font = load("/com/formdev/flatlaf/fonts/jetbrains_mono/JetBrainsMono-Regular.ttf").deriveFont(12f)

    private fun load(resource: String): Font =
        Fonts::class.java.getResourceAsStream(resource).use { Font.createFont(Font.TRUETYPE_FONT, it) }
}

private fun render(scale: Double, draw: (Graphics2D) -> Unit): BufferedImage {
    val image = BufferedImage(scaled(WIDTH, scale), scaled(HEIGHT, scale), BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    // Greyscale, not subpixel, text: fewer distinct colors to fit the GIF's 256.
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    g.scale(scale, scale)
    draw(g)
    g.dispose()
    return image
}

private fun scaled(length: Int, scale: Double) = ceil(length * scale).toInt()

/** The window: background, border and the title bar with the logo. */
private fun drawChrome(g: Graphics2D, fonts: Fonts, logo: (ViewBox) -> Unit) {
    g.color = BACKGROUND
    g.fillRect(0, 0, WIDTH, HEIGHT)
    g.color = HEADER_BACKGROUND
    g.fillRect(0, 0, WIDTH, HEADER)
    g.color = BORDER
    g.fillRect(0, HEADER - 1, WIDTH, 1)
    g.drawRect(0, 0, WIDTH - 1, HEIGHT - 1)
    logo(ViewBox(INSET.toFloat(), 14f, 22f, 22f))
    g.font = fonts.title
    g.color = TITLE
    g.drawString("logxy", INSET + 22 + 10f, centredBaseline(g, 14f, 22f))
}

/** The feed, [offset] lines up. */
private fun drawFeed(g: Graphics2D, fonts: Fonts, offset: Int) {
    g.font = fonts.mono
    val charWidth = g.fontMetrics.charWidth('0')
    for (row in 0 until FEED_ROWS) {
        val line = LINES[(offset + row) % LINES.size]
        val baseline = centredBaseline(g, (FEED_TOP + row * ROW).toFloat(), ROW.toFloat())
        var x = INSET.toFloat()
        g.color = TIME
        g.drawString(line.time, x, baseline)
        x += line.time.length * charWidth + 10
        g.color = line.levelColor
        g.drawString(line.level, x, baseline)
        x += 40 + 10
        g.color = TEXT
        g.drawString(line.message, x, baseline)
    }
}

private fun centredBaseline(g: Graphics2D, top: Float, height: Float): Float {
    val metrics = g.fontMetrics
    return top + (height - metrics.ascent - metrics.descent) / 2 + metrics.ascent
}

/**
 * Writes [frames] as a looping GIF, all sharing one palette, so a color does not shift between frames. Every frame
 * is whole: image editors show a partial frame on its own, with the rest of the canvas black, and the few KB a
 * partial frame saves are not worth that.
 */
private fun writeGif(file: Path, frames: List<BufferedImage>) {
    val palette = palette(frames)
    val writer = ImageIO.getImageWritersByFormatName("gif").next()
    Files.deleteIfExists(file)
    ImageIO.createImageOutputStream(file.toFile()).use { out ->
        writer.output = out
        writer.prepareWriteSequence(null)
        frames.forEachIndexed { i, frame ->
            val image = indexed(frame, palette)
            val param = writer.defaultWriteParam
            val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param)
            val format = metadata.nativeMetadataFormatName
            val root = metadata.getAsTree(format) as IIOMetadataNode
            root.child("GraphicControlExtension").apply {
                setAttribute("disposalMethod", "doNotDispose")
                setAttribute("userInputFlag", "FALSE")
                setAttribute("transparentColorFlag", "FALSE")
                setAttribute("transparentColorIndex", "0")
                setAttribute("delayTime", DELAY.toString())
            }
            if (i == 0) {
                // Loops forever: NETSCAPE2.0 with a loop count of 0.
                val loop = IIOMetadataNode("ApplicationExtension")
                loop.setAttribute("applicationID", "NETSCAPE")
                loop.setAttribute("authenticationCode", "2.0")
                loop.userObject = byteArrayOf(1, 0, 0)
                root.child("ApplicationExtensions").appendChild(loop)
            }
            metadata.setFromTree(format, root)
            writer.writeToSequence(IIOImage(image, null, metadata), param)
        }
        writer.endWriteSequence()
    }
    writer.dispose()
}

private fun IIOMetadataNode.child(name: String): IIOMetadataNode =
    (0 until length).map { item(it) as IIOMetadataNode }.firstOrNull { it.nodeName == name }
        ?: IIOMetadataNode(name).also(::appendChild)

/** The design's flat colors, logo included; by pixel count alone, a short yellow WARN would not make the palette. */
private val FLAT_COLORS = listOf(BACKGROUND, HEADER_BACKGROUND, BORDER, TITLE, TIME, TEXT, INFO, DEBUG, WARN)
    .map { it.rgb and 0xFFFFFF } + listOf(0x2F6FE4, 0xFF6B6B, 0xFFFFFF)

/**
 * [FLAT_COLORS], then the colors used most across [images], 256 in all. The rest are antialiased edges, which the
 * nearest of these stands in for well.
 */
private fun palette(images: List<BufferedImage>): IntArray {
    val counts = HashMap<Int, Int>()
    for (image in images) {
        for (pixel in image.getRGB(0, 0, image.width, image.height, null, 0, image.width)) {
            counts.merge(pixel and 0xFFFFFF, 1, Int::plus)
        }
    }
    val common = counts.entries.sortedByDescending { it.value }.map { it.key }
    return (FLAT_COLORS + common).distinct().take(256).toIntArray()
}

private fun indexed(image: BufferedImage, palette: IntArray): BufferedImage {
    val model = IndexColorModel(8, palette.size, palette, 0, false, -1, DataBuffer.TYPE_BYTE)
    val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_INDEXED, model)
    val raster = out.raster
    val nearest = HashMap<Int, Int>()
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val rgb = image.getRGB(x, y) and 0xFFFFFF
            raster.setSample(x, y, 0, nearest.getOrPut(rgb) { nearestIndex(rgb, palette) })
        }
    }
    return out
}

private fun nearestIndex(rgb: Int, palette: IntArray): Int = palette.indices.minBy { i ->
    val c = palette[i]
    val dr = (rgb shr 16 and 0xFF) - (c shr 16 and 0xFF)
    val dg = (rgb shr 8 and 0xFF) - (c shr 8 and 0xFF)
    val db = (rgb and 0xFF) - (c and 0xFF)
    dr * dr + dg * dg + db * db
}
