#!/usr/bin/env bash
# Runs an IntelliJ-based IDE with the plugin in the GUI sandbox, on its demo project, off the host desktop.
# Usage: ide-sandbox.sh IDE_HOME
#   IDE_HOME: an unpacked Linux IntelliJ IDEA or PyCharm, e.g. one Gradle downloaded for the plugin, or a Toolbox
#   install. PyCharm opens demo/python, other IDEs demo/java.
# Build the plugin first (./gradlew -q :logxy-intellij-plugin:buildPlugin). The container takes the GUI sandbox's name, so
# tools/gui-sandbox/gui.sh shot, click, key, type and stop drive it; the IDE's log goes to out/ide-log/idea.log.
set -euo pipefail

ide=$(cd "${1:?IDE_HOME}" && pwd)
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
gui=$root/tools/gui-sandbox
out=${GUI_OUT:-$gui/out}
size=${GUI_SIZE:-1600x1000}
engine=${GUI_ENGINE:-$(command -v podman >/dev/null && echo podman || echo docker)}
# The newest build; signPlugin's -signed copy is the same plugin.
zip=$(ls -t "$here"/build/distributions/logxy-intellij-plugin-*.zip | grep -v -- -signed | head -1)

# The first value of a key in product-info.json; the first launch entry is the Linux one in a Linux IDE.
product() { grep -o "\"$1\": *\"[^\"]*\"" "$ide/product-info.json" | head -1 | sed 's/.*: *"//; s/"$//'; }
launcher=$(product launcherPath)
vmoptions=$(product vmOptionsFilePath)
# The launcher reads its options from e.g. PYCHARM_VM_OPTIONS.
vmoptions_var=$(product envVarBaseName)_VM_OPTIONS
case $(product productCode) in
  PY | PC) demo=python ;;
  *) demo=java ;;
esac

# Git Bash would otherwise rewrite container paths such as /ide into Windows paths.
export MSYS_NO_PATHCONV=1

host_path() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else echo "$1"; fi; }

run_opts=()
if [[ $(uname -s) == Linux ]]; then
  # The IDE's log must be owned by the host user, and SELinux would block the bind mounts.
  if [[ $engine == podman ]]; then run_opts+=(--userns=keep-id); else run_opts+=(--user "$(id -u):$(id -g)"); fi
  run_opts+=(--security-opt label=disable)
fi

"$engine" image inspect logxy-gui-sandbox >/dev/null 2>&1 || "$gui/gui.sh" build
"$engine" rm -f logxy-gui >/dev/null 2>&1 || true
mkdir -p "$out"
"$engine" run -d --name logxy-gui "${run_opts[@]}" -e HOME=/tmp --shm-size=1g \
  -v "$(host_path "$ide"):/ide:ro" -v "$(host_path "$zip"):/plugin.zip:ro" -v "$(host_path "$here/demo/$demo"):/demo:ro" \
  -v "$(host_path "$out"):/out" \
  logxy-gui-sandbox bash -c "
    set -e
    mkdir -p /tmp/ide/config/options /tmp/ide/plugins
    cp -r /demo /tmp/demo
    cp /demo/jdk.table.xml /tmp/ide/config/options/
    cd /tmp/ide/plugins && jar xf /plugin.zip
    # The IDE's own options, then: no consent, tips or trust dialogs, and the sandbox's own directories.
    { cat /ide/$vmoptions; printf '%s\n' \
        -Didea.config.path=/tmp/ide/config -Didea.system.path=/tmp/ide/system -Didea.plugins.path=/tmp/ide/plugins \
        -Didea.log.path=/out/ide-log -Djb.consents.confirmation.enabled=false '-Djb.privacy.policy.text=<!--999.999-->' \
        -Didea.trust.all.projects=true -Dide.show.tips.on.startup.default.value=false -Didea.initially.ask.config=never \
        -Dide.newUsersOnboarding=false -Dide.experimental.ui=true; } > /tmp/ide/idea.vmoptions
    Xvfb :99 -screen 0 ${size}x24 -nolisten tcp &
    until xdpyinfo >/dev/null 2>&1; do sleep 0.1; done
    openbox &
    $vmoptions_var=/tmp/ide/idea.vmoptions exec /ide/$launcher /tmp/demo > /out/ide.out 2>&1" >/dev/null
echo "started $(cat "$ide/build.txt") on demo/$demo with $engine; screenshots via $gui/gui.sh shot"
