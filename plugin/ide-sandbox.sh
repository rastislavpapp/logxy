#!/usr/bin/env bash
# Spike (#86): runs an IntelliJ IDEA with the plugin in the GUI sandbox, on the demo project, off the host desktop.
# Usage: ide-sandbox.sh IDE_HOME
#   IDE_HOME: an unpacked IDEA, e.g. the one Gradle downloaded for the plugin, or a Toolbox install.
# Build the plugin first (./gradlew -q :plugin:buildPlugin). The container takes the GUI sandbox's name, so
# tools/gui-sandbox/gui.sh shot, click, key, type and stop drive it; the IDE's log goes to out/ide-log/idea.log.
set -euo pipefail

ide=$(cd "${1:?IDE_HOME}" && pwd)
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
gui=$root/tools/gui-sandbox
out=${GUI_OUT:-$gui/out}
size=${GUI_SIZE:-1600x1000}
engine=${GUI_ENGINE:-$(command -v podman >/dev/null && echo podman || echo docker)}
zip=$(ls "$here"/build/distributions/*.zip)

run_opts=()
if [[ $engine == podman ]]; then run_opts+=(--userns=keep-id); else run_opts+=(--user "$(id -u):$(id -g)"); fi
run_opts+=(--security-opt label=disable)

"$engine" image inspect logxy-gui-sandbox >/dev/null 2>&1 || "$gui/gui.sh" build
"$engine" rm -f logxy-gui >/dev/null 2>&1 || true
mkdir -p "$out"
"$engine" run -d --name logxy-gui "${run_opts[@]}" -e HOME=/tmp --shm-size=1g \
  -v "$ide:/ide:ro" -v "$zip:/plugin.zip:ro" -v "$here/demo:/demo:ro" -v "$out:/out" \
  logxy-gui-sandbox bash -c "
    set -e
    mkdir -p /tmp/ide/config/options /tmp/ide/plugins
    cp -r /demo /tmp/demo
    cp /demo/jdk.table.xml /tmp/ide/config/options/
    cd /tmp/ide/plugins && jar xf /plugin.zip
    # The IDE's own options, then: no consent, tips or trust dialogs, and the sandbox's own directories.
    { cat /ide/bin/idea64.vmoptions; printf '%s\n' \
        -Didea.config.path=/tmp/ide/config -Didea.system.path=/tmp/ide/system -Didea.plugins.path=/tmp/ide/plugins \
        -Didea.log.path=/out/ide-log -Djb.consents.confirmation.enabled=false '-Djb.privacy.policy.text=<!--999.999-->' \
        -Didea.trust.all.projects=true -Dide.show.tips.on.startup.default.value=false -Didea.initially.ask.config=never \
        -Dide.newUsersOnboarding=false -Dide.experimental.ui=true; } > /tmp/ide/idea.vmoptions
    Xvfb :99 -screen 0 ${size}x24 -nolisten tcp &
    until xdpyinfo >/dev/null 2>&1; do sleep 0.1; done
    openbox &
    IDEA_VM_OPTIONS=/tmp/ide/idea.vmoptions exec /ide/bin/idea /tmp/demo > /out/ide.out 2>&1" >/dev/null
echo "started $(cat "$ide/build.txt") with $engine; screenshots via $gui/gui.sh shot"
