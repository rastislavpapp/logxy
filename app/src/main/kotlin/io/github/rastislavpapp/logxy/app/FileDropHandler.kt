package io.github.rastislavpapp.logxy.app

import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.TransferHandler

// Linux file managers often offer only a URI list, not a Java file list.
private val URI_LIST_FLAVOR = DataFlavor("text/uri-list;class=java.lang.String")

/**
 * Accepts dropped files and passes them to [open]. Export (copy, drag out) goes to [delegate],
 * so installing this on a table keeps its normal copy behavior.
 */
class FileDropHandler(
    private val open: (List<Path>) -> Unit,
    private val delegate: TransferHandler? = null,
) : TransferHandler() {

    override fun canImport(support: TransferSupport) =
        support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || support.isDataFlavorSupported(URI_LIST_FLAVOR)

    override fun importData(support: TransferSupport): Boolean {
        if (!canImport(support)) return false
        val paths = try {
            droppedPaths(support.transferable)
        } catch (_: Exception) {
            return false
        }
        if (paths.isEmpty()) return false
        open(paths)
        return true
    }

    private fun droppedPaths(transferable: Transferable): List<Path> {
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).map { (it as File).toPath() }
        }
        val uris = transferable.getTransferData(URI_LIST_FLAVOR) as String
        return uris.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("file:") }
            .map { Path.of(URI(it)) }
            .toList()
    }

    override fun getSourceActions(c: JComponent) = delegate?.getSourceActions(c) ?: NONE

    override fun exportAsDrag(comp: JComponent, e: java.awt.event.InputEvent, action: Int) {
        delegate?.exportAsDrag(comp, e, action)
    }

    override fun exportToClipboard(comp: JComponent, clip: Clipboard, action: Int) {
        delegate?.exportToClipboard(comp, clip, action)
    }
}
