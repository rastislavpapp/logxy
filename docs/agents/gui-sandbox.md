# Checking the app in the GUI sandbox

`tools/gui-sandbox/gui.sh` runs the app on a virtual X display in a podman or docker container, so it never takes focus on the user's desktop. Run it without arguments for its commands. It renders with Linux fonts and look-and-feel: Windows- or macOS-specific behaviour still needs a check on that OS.

## Steps

1. **Build the jar.** The sandbox runs `app/build/libs/logxy.jar`: `./gradlew -q :app:shadowJar`. Rebuild and `start` again after every code change; a running sandbox keeps the old jar.
2. **Pick the smallest log the check needs.** A few MB for UI behaviour; large files only when the change is about scale, as files are read through a container mount, slower than native disk. For how rows show their records (expanding, wrapping, cut values, marks), use `tools/gui-sandbox/fixtures/expand.log`: 17 records that all fit on one screen, with long one-line records, stack traces short and long, a long first line with later lines, tabs, and CJK text, plus `NEEDLE-PAST-CUT` near the end of a long line for finding past its "...". The sandbox has no CJK font, so CJK draws as boxes of the right width. Look for other fitting logs first (`.test-data/`, and earlier ones in `tools/gui-sandbox/out/`), else generate one into `tools/gui-sandbox/out/` with `./gradlew -q :tools:run --args="generate <file> 5m"` (run `tools:run` without arguments for the formats and the `scenario` and `simulate` variants).
3. **Start** with `gui.sh start <paths>`, and keep one sandbox running across checks. Each start begins with default settings.
4. **Drive it as a loop:** take a `shot`, read the image, choose one action (`click`, `rclick`, `key`, `type`) from what it shows, repeat. Wait by taking another shot rather than sleeping. Chain actions in one command only when the screen between them is already known.
   Every image read costs by its size, so take a full shot only to learn the layout (after start, after opening a dialog or menu), then **crop** to the region the next question needs: `shot NAME X Y W H`, e.g. the status bar, one dialog, the first rows of the table.
5. **Check the log** with `gui.sh logs | grep -E -A8 'Uncaught|Failed in the tab'` after the scenario; the second is what a tab's own jobs threw. `grep 'Load'` shows each load with its parser and record count, `grep Reloading` each reload with why. An exception on the UI thread is logged but often leaves the screen merely stale (old rows, an old count), so a screenshot alone can look plausible.
6. **Stop** with `gui.sh stop` when done.

## Reference

- **Coordinates** are screen coordinates from the latest full shot. In a crop, add its X and Y to a position read off it. Dialogs and popups open at varying places, so take a shot after opening one before clicking inside it.
- **Regions** at the default 1600x1000 screen with the main window unmoved: the status bar is about `200 875 1200 25`, the table's first rows `200 340 1200 200`. Confirm against a full shot once per session; the layout changes with the file's columns.
- **Context menus**: `rclick X Y` opens the table's menu on a cell (copy value, filter by it).
- **Dialogs**: progress dialogs open after 300 ms, so a shot right after the action is too early. While a modal dialog is open, input to the main window is swallowed. `windows` lists the open dialogs. To catch a short-lived one, chain the action and shots in one `exec` (`xdotool key ctrl+c; sleep 0.4; import -window root /out/x.png`).
- **Clipboard**: `clip` shows what a copy put there (byte count, first and last line). Pasting a large copy into a field takes seconds.
- **Keys** are xdotool names: `Page_Down`, `Return`, `Escape`, `ctrl+f`, `ctrl+space` (toggles the selection of the focused row).
- **Shot names**: reuse a few names (`a1`, `a2`, …) per scenario; `out/` is git-ignored and grows with every name.
