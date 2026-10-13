# Checking the app in the GUI sandbox

`tools/gui-sandbox/gui.sh` runs the app on a virtual X display in a podman or docker container, so it never takes focus on the user's desktop. Run it without arguments for its commands. It renders with Linux fonts and look-and-feel: Windows- or macOS-specific behaviour still needs a check on that OS.

## Steps

1. **Build the jar.** The sandbox runs `app/build/libs/logxy.jar`: `./gradlew -q :app:shadowJar`. Rebuild and `start` again after every code change; a running sandbox keeps the old jar.
2. **Pick the smallest log the check needs.** A few MB for UI behaviour; large files only when the change is about scale, as files are read through a container mount, slower than native disk. Look for fitting logs first (`testdata/`, and earlier ones in `tools/gui-sandbox/out/`), else generate one into `tools/gui-sandbox/out/` with `./gradlew -q :tools:run --args="generate <file> 5m"` (run `tools:run` without arguments for the formats and the `scenario` and `simulate` variants).
3. **Start** with `gui.sh start <paths>`, and keep one sandbox running across checks. Each start begins with default settings.
4. **Drive it as a loop:** take a `shot`, read the image, choose one action (`click`, `rclick`, `key`, `type`) from what it shows, repeat. Wait by taking another shot rather than sleeping. Chain actions in one command only when the screen between them is already known.
5. **Check the log** with `gui.sh logs | grep -A8 Uncaught` after the scenario. An exception on the UI thread is logged but often leaves the screen merely stale (old rows, an old count), so a screenshot alone can look plausible.
6. **Stop** with `gui.sh stop` when done.

## Reference

- **Coordinates** come from the latest screenshot only. Dialogs and popups open at varying places, so take a shot after opening one before clicking inside it.
- **Context menus**: `rclick X Y` opens the table's menu on a cell (copy value, filter by it).
- **Keys** are xdotool names: `Page_Down`, `Return`, `Escape`, `ctrl+f`, `ctrl+space` (toggles the selection of the focused row).
- **Shot names**: reuse a few names (`a1`, `a2`, …) per scenario; `out/` is git-ignored and grows with every name.
