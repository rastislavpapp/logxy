# <img src="app/src/main/resources/logxy.svg" width="40" height="40" align="top" alt=""> logxy

**A fast desktop viewer for large log files.** It opens a 10 GB log in under two seconds and filters it in under a second, and it can merge several files by time and follow them as they grow.

[![Build](https://github.com/rastislavpapp/logxy/actions/workflows/build.yml/badge.svg)](https://github.com/rastislavpapp/logxy/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Support on Ko-fi](https://img.shields.io/badge/Ko--fi-support%20logxy-FF5E5B?logo=ko-fi&logoColor=white)](https://ko-fi.com/rastislavpapp)

![logxy showing a log filtered to messages containing "timeout"](docs/images/filter.png)

## Features

<!-- The IntelliJ plugin's overview in logxy-intellij-plugin/src/main/resources/META-INF/plugin.xml describes the shared features too; keep the two in step. -->

- **Large files.** Multi-GB logs open in seconds and scroll smoothly.
- **Structured parsing.** Log4j and logback patterns, Spring Boot's default log output, Python's logging, JSON lines (logstash and ECS style), logfmt, CSV files with a header row (`.csv`, comma or semicolon separated), MDC maps (one column per key) and raw text. logxy picks the right parser automatically, and you can define your own with a pattern, a regex or a JSON key mapping.
- **Filtering.** Combine conditions on any column with AND, OR and NOT, either in a visual editor or as a text expression such as `level >= WARN AND thread == "main*"`. Wildcards, regular expressions, case-sensitive matching, level and time ranges are supported. Save filters for later.
- **Merging.** Open several files together in one session, interleaved by timestamp, even when each needs a different parser. A Source column tells the files apart.
- **Following.** Live files are tailed as they grow, including rotation and truncation.
- **Time histogram.** See where the errors and warnings are, and where your search matches fall, then jump there.
- **Compressed files and folders.** Open `.zip`, `.gz` and `.zst` files, or a whole folder of logs.
- **Themes.** Light and dark, with a choice of FlatLaf and IntelliJ themes.

![The Open Files dialog merging a log4j-style file with a JSON-lines file](docs/images/open-files.png)

## IntelliJ plugin

The plugin turns the Run console of IntelliJ IDEA and other IntelliJ-based IDEs into a log viewer. A run's output shows as parsed columns that you can filter, search and follow live, with the time histogram and templates beside them. The format is detected from the first lines, and a Console | logxy switch in the run tab's toolbar keeps the IDE's own console one click away.

![A Spring Boot run in the plugin's logxy view, filtered to warnings and errors outside one logger](docs/images/plugin/run-view.png)

To install it, in an IDE from 2025.3 on:

1. Download `logxy-intellij-plugin-<version>.zip` from the [Releases page](https://github.com/rastislavpapp/logxy/releases).
2. In *Settings › Plugins*, click the gear icon, then *Install Plugin from Disk…*, and pick the zip.
3. Run something: the run tab's toolbar has the Console | logxy switch.

## Download

Packages with a bundled Java runtime for Windows and Linux are on the [Releases page](https://github.com/rastislavpapp/logxy/releases): unpack and run. The release also contains a plain `logxy.jar` for any system with JDK 25 or newer.

macOS is untested and unsupported. logxy is a Java application, so it may well work there when run from the jar.

## Build and run

```sh
./gradlew :app:run
```

Needs JDK 25 with GUI support; see [CONTRIBUTING.md](CONTRIBUTING.md) for the setup, tests and benchmarks.

## Contributing

Issues and pull requests are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) first.

## Support

logxy is free and developed in spare time. If it saves you time, you can support its development on [Ko-fi](https://ko-fi.com/rastislavpapp) or through [GitHub Sponsors](https://github.com/sponsors/rastislavpapp). Bug reports and stars help too.

A bug report is easier to follow with logxy's log. The app writes it to `logs/logxy.log` in its settings folder (`%APPDATA%\logxy` on Windows, `~/.config/logxy` elsewhere); for more detail, such as why a file opened as Raw text, add `<Logger name="io.github.rastislavpapp.logxy" level="debug"/>` under `<Loggers>` in `log4j2.xml` there. The IntelliJ plugin logs to the IDE's `idea.log` (Help › Show Log in Explorer, Finder or Files); turn on more detail with Help › Diagnostic Tools › Debug Log Settings and the line `io.github.rastislavpapp.logxy`.

## AI disclosure

This project is developed with the help of AI coding tools, primarily [Claude Code](https://claude.com/claude-code). The files for AI agents (`AGENTS.md` and `docs/agents/`) are kept in the repository.

## License

[MIT](LICENSE) © Rastislav Papp. The libraries and fonts the app and the plugin bundle, and their licences, are listed in [THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md).
