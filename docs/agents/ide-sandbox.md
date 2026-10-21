# Checking the IntelliJ plugin in the GUI sandbox

`plugin/ide-sandbox.sh` runs an IntelliJ IDEA with the plugin in the GUI sandbox's container (see `gui-sandbox.md`), on the demo project `plugin/demo`, so it never takes focus on the user's desktop. Do not use `runIde`: it opens the IDE on the host. The container takes the GUI sandbox's name, so `tools/gui-sandbox/gui.sh` drives it as it drives the app.

## Steps

1. **Build the plugin**: `./gradlew -q :plugin:buildPlugin`. Rebuild and start again after every code change.
2. **Pick the IDE.** The script takes an unpacked IDEA as its argument; check against the oldest supported version and the newest.
   - Gradle unpacks the version the plugin compiles against, and those `verifyPlugin` checks, into `.intellijPlatform/ides/IU-<version>`; pass it as an absolute path. `./gradlew -q :plugin:verifyPlugin` fetches the latest release too, `-PverifyEap` the latest EAP.
   - A Toolbox install works too (`~/.local/share/JetBrains/Toolbox/apps/intellij-idea` on Linux). It is mounted read-only.
3. **Start** with `plugin/ide-sandbox.sh IDE_HOME`. Each start begins with fresh IDE settings. The IDE opens in free mode, without consent, tips or trust dialogs, after about 20 s.
4. **Run** one of the demo's run configurations: "JVM app" (`src/Demo.java`) or "Shell script" (`demo.sh`). Both print log lines, a stack trace or ANSI colours now and then, and echo what is typed into the console.
5. **Drive it as a loop** with `gui.sh shot`, `click`, `key` and `type`, as in `gui-sandbox.md`: a full shot to learn the layout, then crops.
6. **Check the log**: `grep -A20 SEVERE tools/gui-sandbox/out/ide-log/idea.log`. The IDE names the plugin it blames. The plugin's own lines start with `logxy:`. The log appends across starts, so move `ide-log/` aside before a restart.
7. **Stop** with `gui.sh stop`.

## Reference

- **Regions** at the default 1600x1000 screen: the Run button is about `1098 61`, the run configuration chooser `1025 61`. The Run tool window opens short at the bottom, from about y 640. To make it taller, drag its top edge: `gui.sh exec xdotool mousemove 800 639 mousedown 1 mousemove 800 120 mouseup 1`.
- **Popups**: items in the configuration chooser need the mouse moved onto them before a click registers (`gui.sh move X Y`, then `click`).
- **Keys**: `ctrl+shift+apostrophe` maximizes whichever tool window has focus, not necessarily the Run one.
- **Console links**: a running console scrolls on, so stop the process before clicking a link in it.
- **Inside the container**: the IDE's system directory is `/tmp/ide/system`, so the run spool files are in `/tmp/ide/system/logxy/runs`. The demo project is a copy at `/tmp/demo`. Look with `gui.sh exec ls ...`.
- **No Python**: neither IDE bundles the Python plugin, so the shell script stands in for a non-JVM run.
