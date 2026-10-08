# logxy

Fast desktop viewer for large log files.

## Build and run

Requires JDK 25. Running the app needs a full JDK with GUI support (e.g. Temurin), not a headless-only runtime such as Fedora's `java-25-openjdk-headless`; point `JAVA_HOME` at it.

```sh
./mvnw verify                       # build and test (Windows: mvnw.cmd)
java -jar app/target/logxy.jar      # run the fat jar
./mvnw -q install -DskipTests && ./mvnw -q -pl app exec:java   # run during development
```

## AI disclosure

This project is developed with the help of AI coding tools, primarily [Claude Code](https://claude.com/claude-code).
