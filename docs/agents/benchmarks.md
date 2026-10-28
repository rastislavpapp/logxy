# Benchmarks: a slow or noisy row, or a new one

The baseline check (`./gradlew -q :tools:benchmark`) and `tools parse|filter|merge` run the same JMH benchmarks. Each row runs in JVMs of its own. The check compares the median of each JVM's best run with `tools/benchmark-baseline.properties`.

## Tell a slow row from a slow JVM

Measure the row alone in many JVMs; the names are the ones the check prints:

```sh
./gradlew -q :tools:run --args="filter build/benchmark/main.log --forks 16 --only \"filter message regex\""
```

`JVM bests` lists each JVM's best in ms. When all JVMs are slower, the code got slower. When a few JVMs sit apart from the rest for the whole run, the JIT settled differently in them: compare one of those with a fast one (below). #55 is such a case.

## Compare a slow and a fast JVM

Run JMH directly, so each fork can get its own JVM arguments:

```sh
./gradlew -q :tools:installDist
java -Xmx8g --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED \
  -cp 'tools/build/install/tools/lib/*' org.openjdk.jmh.Main 'FilterBenchmark.run' \
  -p file=build/benchmark/main.log -p "filter=message regex" -bm ss -wi 3 -i 5 -f 16 -tu ms \
  -jvmArgsAppend "-XX:StartFlightRecording=settings=profile,filename=/tmp/jfr/%p.jfr"
```

- **Benchmark names**: the classes in `tools/src/main/kotlin/.../tools/*Benchmark.kt`; the `-p` names are their `@Param` fields.
- **JFR, one recording per fork**: `%p` is the pid, so the recordings sort in fork order. JMH also starts short helper JVMs whose recordings last 0 s; skip those. Compare `jfr view hot-methods <file>` of a slow and a fast fork. `jfr print --json --events jdk.ExecutionSample` gives each frame's type (`Inlined`, `JIT compiled`), which shows the compilation a hot loop ended up in.
- **`-XX:+PrintCompilation`**: its lines interleave with JMH's `Iteration` lines. Split the output at `# Fork:` and take each fork's last `-i` values matching `ms/op`.
- **Testing a JIT hypothesis**: add `-XX:CompileCommand=quiet -XX:CompileCommand=dontinline,<class>::<method>` (or another compile command) to `-jvmArgsAppend`, then count the slow forks again.

## Recording the baseline

`-PbaselineRecord` adds the rows the baseline lacks. When `SETUP` or the JVMs per row (`-PbenchmarkForks`) changed, it records every row again.

## Adding a row

1. **The benchmark.** A `@Benchmark` method in a `tools/.../*Benchmark.kt` class, a `BenchmarkRow` naming it, and its params; then a group for it in `measureAll` in `BaselineTest.kt`.
2. **Its data.** A log it needs and the others lack gets a `dataFile` of its own: a new name and seed, generated into `build/benchmark` once. Leave `SETUP` as it is. It describes what the existing rows measure, and changing it records every row again on every machine.
3. **Measure it alone**: `./gradlew -q :tools:benchmark -PbenchmarkOnly="<row name>"`, with the name the check prints. On a machine with a local baseline, a row without a time joins it.
4. **The committed baseline.** It belongs to the machine its `cpu` and `cores` name; others use `tools/benchmark-baseline.local.properties`, which is git-ignored. A row the committed baseline lacks fails the check on that machine. Record it there with `-PbaselineRecord`, and commit the file, in the same branch when that machine is at hand, else say so in the PR.
5. **Renaming a row** leaves its time under the old name in each baseline. Rename the key in both files, by hand in the local one.
