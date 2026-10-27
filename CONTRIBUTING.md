# Contributing to logxy

Thanks for your interest. Issues and pull requests are welcome. For anything bigger than a small fix, please open an issue first so we can agree on the approach before you spend time on it.

## Reporting bugs and requesting features

Use [GitHub Issues](https://github.com/rastislavpapp/logxy/issues). For a bug, include your OS, the Java version, the logxy version (Help › About; for the IntelliJ plugin, the IDE and its version, and the plugin's version from Settings › Plugins), which parser logxy picked for the file and, if you can share it, a few lines of the file that trigger the problem; the bug report form has a Log sample field for them.

## Setting up

Requires JDK 25 with GUI support (for example Temurin), not a headless-only runtime; point `JAVA_HOME` at it.

```sh
./gradlew build                      # build, test and run detekt (Windows: gradlew.bat)
./gradlew :app:run                   # run the app
java -jar app/build/libs/logxy.jar   # run the fat jar
./gradlew :app:nativeArchive         # app image with bundled runtime, zipped for this OS in app/build/dist
./gradlew detekt --auto-correct      # fix formatting findings
```

The code is Kotlin with Swing and FlatLaf: `core` holds the logic, `app` is the UI. The project rules, including the commit rules and the benchmark run after a speed-relevant change, are in [AGENTS.md](AGENTS.md); the readability rules checked in review are in [CODING_STANDARDS.md](CODING_STANDARDS.md), and [GLOSSARY.md](GLOSSARY.md) defines the terms the code and docs use.

## Test data and benchmarks

```sh
./gradlew -q :tools:run --args="generate /path/to/test.log 2g"
./gradlew -q :tools:run --args="generate /path/to/mdc.log 50m --mdc"
./gradlew -q :tools:run --args="generate /path/to/app.json 50m --json"
./gradlew -q :tools:run --args="generate /path/to/rabbit.json 50m --rabbitmq"
./gradlew -q :tools:run --args="benchmark /path/to/test.log"
./gradlew -q :tools:run --args="parse /path/to/test.log"
./gradlew -q :tools:run --args="filter /path/to/test.log"
./gradlew -q :tools:run --args="merge /path/to/a.log /path/to/b.log"
./gradlew -q :tools:run --args="simulate /path/to/live.log --rate 50 --rotate-at 1m"
./gradlew -q :tools:benchmark                  # JUnit test: fails if parsing, filtering or merging got slower than saved
```

The benchmark is not part of `./gradlew test`. `-PbaselineTolerance=N` sets the allowed slowdown in percent (default 25). The baseline (`tools/benchmark-baseline.properties`) is machine-specific: on a machine whose CPU or core count differs, the first run records `tools/benchmark-baseline.local.properties` (gitignored) and later runs compare with that; delete it to record again. The times come from fixed-seed files generated into `build/benchmark` on first use (about 1.5 GB).

## Logo and icons

The logo's source is `app/src/main/resources/logxy.svg`. The packaging icons in `app/src/packaging` are generated from it and committed; after changing the logo, regenerate them from the project root:

```sh
./gradlew -q :tools:run --args="icons"
```

## Pull requests

- Branch from `main` and keep each pull request to one change.
- `./gradlew build` must pass; it runs the tests and detekt, and fails when the app or the plugin bundles a library missing from [THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md).
- Follow the commit rules in [AGENTS.md](AGENTS.md#commits), and its plugin changelog rule in [Rules](AGENTS.md#rules).
- Describe what changed and how you checked it. A screenshot helps for UI changes.

## Releasing (maintainers)

The app and the plugin share one version. Before tagging, turn the plugin changelog's Unreleased section into the version's section, and commit that to `main`:

```sh
./gradlew -q :logxy-intellij-plugin:patchChangelog -Pversion=0.1.0
```

Then push an `X.Y.Z` tag on a commit in `main`. The release workflow builds and tests on Windows and Linux, verifies the IntelliJ plugin against the oldest supported and the latest release of each verified IDE, stamps that version on the packages (the code stays at `-SNAPSHOT`; the release passes `-Pversion`) and creates a GitHub release with the jar, the Windows zip, the Linux tarball and the plugin zip. A new IDE release that breaks the plugin blocks the release too, until the plugin is fixed. Running the workflow by hand does a dry run that publishes nothing.

```sh
git tag 0.1.0 && git push origin 0.1.0
```

A tag without a section in `logxy-intellij-plugin/CHANGELOG.md` fails the release. When the section has entries, the workflow then publishes the plugin, signed, to JetBrains Marketplace; when it has none, the plugin did not change and Marketplace gets no new version, so Marketplace versions may skip numbers. The GitHub release always has the plugin zip.

**Marketplace setup**, once:

1. Upload the first version's zip from the GitHub release by hand on [plugins.jetbrains.com](https://plugins.jetbrains.com/plugin/add) (Marketplace takes a new plugin no other way) and accept the developer agreement. JetBrains reviews it before it is listed.
2. On the plugin's Marketplace page, set the licence link (MIT), the source code and issue tracker URLs, the tags (Logging, Run) and the screenshots from `docs/images/plugin/`.
3. Run `logxy-intellij-plugin/marketplace-secrets.sh`: it makes the signing key and certificate and sets them and the Marketplace token as the workflow's secrets. Until `PUBLISH_TOKEN` is set, releases skip the Marketplace publish. The certificate is valid for a year; run the script again to renew it.

## AI-assisted contributions

This project is itself developed with AI coding tools, so AI-assisted pull requests are fine. You are responsible for what you submit: read it, run it and be able to explain it.

## License

By contributing you agree that your contribution is licensed under the [MIT License](LICENSE).
