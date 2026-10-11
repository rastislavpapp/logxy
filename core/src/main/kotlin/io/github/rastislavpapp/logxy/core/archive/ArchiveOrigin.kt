package io.github.rastislavpapp.logxy.core.archive

import java.nio.file.Path

/**
 * Where a file extracted from a zip or decompressed from a .gz or .zst came from. Its `toString` names it
 * so that opening that text again extracts the file anew.
 */
interface ArchiveOrigin {
    /** The zip or compressed file. */
    val container: Path

    /** The file's path inside [container], `/`-separated; empty when the container is the file itself, compressed. */
    val entry: String
}
