# The newline search uses the incubating Vector API

Indexing and parsing spend much of their time finding newlines. `NewlineScanner` searches a whole SIMD vector of bytes per step with `jdk.incubator.vector` and falls back to SWAR, eight bytes in a `long`. On the baseline machine (Ryzen 7800X3D, 512-bit vectors), building the line index of a 1 GB log takes 0.031 s instead of 0.053 s, and the scan alone runs 3× faster; on a 4-core Xeon the index builds 2.4× faster and a parse takes up to 26% less. The module still incubates and may change between JDK releases, and every JVM that loads it prints `WARNING: Using incubator modules`. Both are accepted for the gain (#73).

The search is chosen once per JVM: vectors when the module is loaded and `ByteVector.SPECIES_PREFERRED` is at least 256 bits wide, SWAR otherwise. `-Dlogxy.newlines=swar` forces SWAR. The rule comes from measurements on the baseline machine, with the JVM held back by flags:

- On an SSE-only CPU (`-XX:UseAVX=0`) the preferred width is 128 bits, yet the JIT does not compile the search to SIMD instructions, and it runs 10× slower than SWAR. On x86, 256 bits means AVX2, where it does. CPUs with AVX1 only lose about 20% to SWAR.
- A species wider than the preferred one also runs as plain Java, 10× slower, so the search always uses the preferred width.
- A JVM without C2 (`-XX:TieredStopAtLevel=1`) still reports 512 bits and runs the search 100× slower. logxy never starts one, so this case is not detected.

Every JVM logxy starts passes `--add-modules jdk.incubator.vector`: the packaged app, the Gradle `run` tasks, the tools and their JMH forks, and the tests. The JVM does not resolve an incubator module unless asked, even when its runtime image holds it. A JVM started without it, such as `java -jar logxy.jar`, searches with SWAR: `NewlineScanner` checks for the module before it loads `VectorNewlineSearch`. The `testWithoutVectorModule` task covers that fallback.

If the API changes incompatibly, delete `VectorNewlineSearch` and the `--add-modules` flags. SWAR remains as the search, and nothing else depends on the module.

## Considered Options

- SWAR only: no incubating dependency or warning, but indexing stays up to 2.4× slower.
- Vectors from 128 bits: faster on AVX1 CPUs, but 10× slower on SSE-only ones. The preferred width does not tell the two apart.
