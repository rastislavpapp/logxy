package io.github.rastislavpapp.logxy.core

import java.nio.file.Path

/** The same for every path to one file: through links, and in any case on Windows. */
fun fileKey(path: Path): Path = runCatching { path.toRealPath() }.getOrDefault(path.toAbsolutePath().normalize())
