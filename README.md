# <img src="app/src/main/resources/logxy.svg" width="40" height="40" align="top" alt=""> logxy

Fast desktop viewer for large log files.

## Build and run

Requires JDK 25. Running the app needs a full JDK with GUI support (e.g. Temurin), not a headless-only runtime such as Fedora's `java-25-openjdk-headless`; point `JAVA_HOME` at it.

```sh
./gradlew build                      # build and test (Windows: gradlew.bat)
java -jar app/build/libs/logxy.jar   # run the fat jar
./gradlew :app:run                   # run during development
./gradlew :app:nativeArchive         # app image with bundled runtime, zipped for this OS in app/build/dist
```

## Releasing

Push an `X.Y.Z` tag on a commit in `main`. The release workflow builds and tests on Windows and Linux, stamps that version on the packages (the code stays at `-SNAPSHOT`; the release passes `-Pversion`) and creates a GitHub release with the jar, the Windows zip and the Linux tarball. Running the workflow by hand does a dry run that publishes nothing.

```sh
git tag 0.1.0 && git push origin 0.1.0
```

Test data and benchmarks:

```sh
./gradlew -q :tools:run --args="generate /path/to/test.log 2g"
./gradlew -q :tools:run --args="generate /path/to/mdc.log 50m --mdc"
./gradlew -q :tools:run --args="generate /path/to/app.json 50m --json"
./gradlew -q :tools:run --args="generate /path/to/rabbit.json 50m --rabbitmq"
./gradlew -q :tools:run --args="benchmark /path/to/test.log"
./gradlew -q :tools:run --args="parse /path/to/test.log"
./gradlew -q :tools:run --args="filter /path/to/test.log"
./gradlew -q :tools:run --args="simulate /path/to/live.log --rate 50 --rotate-at 1m"
./gradlew :tools:benchmark                     # JUnit test: fails if parsing, filtering or merging got slower than saved
```

The benchmark is not part of `./gradlew test`. `-PbaselineTolerance=N` sets the allowed slowdown in percent (default 25). The baseline (`tools/benchmark-baseline.properties`) is machine-specific; its times come from fixed-seed files generated into `build/benchmark` on first use (about 1.5 GB).

The logo's source is `app/src/main/resources/logxy.svg`. The packaging icons in `app/src/packaging` are generated from it and committed; after changing the logo, regenerate them from the project root:

```sh
./gradlew -q :tools:run --args="icons"
```

## AI disclosure

This project is developed with the help of AI coding tools, primarily [Claude Code](https://claude.com/claude-code).
