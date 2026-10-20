package io.github.rastislavpapp.logxy.ui

import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.SwingUtilities
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
        // After the drop ends: opening may ask how in a modal dialog, and while the drop lasts the drag source
        // (a file manager, through XWayland) holds the input, so the dialog would never get any (#74).
        SwingUtilities.invokeLater { open(paths) }
        return true
    }

    private fun droppedPaths(transferable: Transferable): List<Path> {
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>
            return files.map { (it as File).toPath() }
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
