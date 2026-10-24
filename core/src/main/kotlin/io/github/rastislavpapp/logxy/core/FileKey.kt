package io.github.rastislavpapp.logxy.core

import java.nio.file.Path

/**
 * A key that is equal for every path to the same file: links are resolved, and on Windows so is letter case.
 * A path that cannot be resolved, e.g. of a file that does not exist, gives its absolute, normalized form.
 */
fun fileKey(path: Path): Path = runCatching { path.toRealPath() }.getOrDefault(path.toAbsolutePath().normalize())
