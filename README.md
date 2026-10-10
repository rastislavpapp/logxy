# <img src="app/src/main/resources/logxy.svg" width="40" height="40" align="top" alt=""> logxy

Fast desktop viewer for large log files.

## Build and run

Requires JDK 25. Running the app needs a full JDK with GUI support (e.g. Temurin), not a headless-only runtime such as Fedora's `java-25-openjdk-headless`; point `JAVA_HOME` at it.

```sh
./mvnw verify                       # build and test (Windows: mvnw.cmd)
java -jar app/target/logxy.jar      # run the fat jar
./mvnw -q install -DskipTests && ./mvnw -q -pl app exec:java   # run during development
```

Test data and benchmarks:

```sh
./mvnw -q -pl tools exec:java -Dexec.args="generate /path/to/test.log 2g"
./mvnw -q -pl tools exec:java -Dexec.args="benchmark /path/to/test.log"
./mvnw -q -pl tools exec:java -Dexec.args="parse /path/to/test.log"
./mvnw -q -pl tools exec:java -Dexec.args="filter /path/to/test.log"
./mvnw -q -pl tools exec:java -Dexec.args="simulate /path/to/live.log --rate 50 --rotate-at 1m"
```

## AI disclosure

This project is developed with the help of AI coding tools, primarily [Claude Code](https://claude.com/claude-code).
