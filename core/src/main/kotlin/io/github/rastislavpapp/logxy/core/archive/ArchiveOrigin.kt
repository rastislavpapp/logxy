package io.github.rastislavpapp.logxy.core.archive

import java.nio.file.Path

/**
 * Where a file extracted from a zip or decompressed from a .gz or .zst came from. Its `toString` is a text
 * that, opened again as a path, extracts or decompresses the file anew.
 */
interface ArchiveOrigin {
    /** The zip or compressed file. */
    val container: Path

    /**
     * The file's path inside [container], `/`-separated. Empty for a .gz or .zst file, where [container] is
     * the file itself, compressed.
     */
    val entry: String
}
