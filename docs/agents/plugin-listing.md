# The plugin's listing

What JetBrains requires of the plugin's name, overview, icon and screenshots, from its listing best practices and Marketplace approval guidelines. Read before changing `<name>` or `<description>` in `logxy-intellij-plugin/src/main/resources/META-INF/plugin.xml`, or the icon.

## Name

- At most 30 characters, Latin letters; 1–4 words and up to 20 characters are recommended.
- Free of "Plugin", "IntelliJ", "JetBrains" and the names of JetBrains products and third-party trademarks; "Support" and "Integration" are discouraged, as are generic words like "Tool".
- Title Case is advised; `logxy` stays lowercase as the brand.

## Overview (`<description>`)

- The first 40 characters are an English summary: they show in search results.
- English first, correct spelling and grammar. No length limit.
- Direct wording, without marketing adjectives ("simple", "lightweight", "professional"). A plain statement of what a feature does is fine, e.g. the desktop app being fast and memory-efficient on large logs, which is the project's goal and what the benchmarks measure.
- No donation links, no promotion of unrelated services, no keyword stuffing.
- Every link valid and related to the plugin.
- Most HTML works, inside CDATA. Screenshots belong in the Marketplace Media gallery, uploaded by hand; an `<img>` in the description is discouraged (600–800 px wide if used) and must never break.
- It picks from the README's Features list what matters in a run's output: formats beyond the common ones (JSON lines, logfmt, CSV) go under "and more", and the app's large-file features stay out. What both name, keep in step.
## Icon

- `META-INF/pluginIcon.svg`, SVG only, 40×40, with at least 2 px transparent padding (the artwork within 36×36). Shown at 40 px in lists and 80 px on details pages. Ideally under 2–3 kB.
- `pluginIcon_dark.svg` for dark themes, the same artwork with a lighter tile; with it present, `pluginIcon.svg` serves light themes only, so change the two together.
- No text in the logo, nothing resembling the Plugin Template's default logo or a JetBrains product logo.

## Screenshots

`docs/images/plugin/`, 1200×800 of the IDE alone in the dark theme, from the demo's "Busy app" runs; how to take them is in `ide-sandbox.md`.

## Sources

- https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html
- https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html
- https://plugins.jetbrains.com/docs/intellij/plugin-icon-file.html
- https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html
