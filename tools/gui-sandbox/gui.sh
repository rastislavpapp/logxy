#!/usr/bin/env bash
# Runs the app on a virtual X display inside a container so it never touches the host desktop.
# Usage: gui.sh COMMAND
#   start [path...]    (re)start the app on files or folders anywhere on the host; builds the image on first use
#   shot [name [X Y W H]]  screenshot to out/NAME.png (default: shot), cropped to the W×H region at X,Y
#   click X Y          left click at screen coordinates read off a screenshot; also dclick, rclick, move
#   type TEXT          type into the focused component
#   key KEYS...        xdotool key names, one press each: ctrl+f Return Escape Page_Down shift+Tab
#   clip               the clipboard's size in bytes, its first line and its last
#   windows            visible windows with position and size
#   wait TITLE [SECS]  wait up to SECS (default 60) for a visible window whose title matches the regex TITLE
#   pause SECS         wait SECS, e.g. for a dialog or a run to settle; the host shell may refuse a plain sleep
#   logs               the app's stdout and stderr
#   exec CMD...        run a command inside the container
#   build | stop       build the image | stop and remove the sandbox
# Environment: GUI_ENGINE (podman or docker), GUI_SIZE (default 1600x1000), GUI_OUT (default out/),
# JAVA_TOOL_OPTIONS (passed to the app, e.g. -Xmx64m).
set -euo pipefail

name=logxy-gui
image=logxy-gui-sandbox
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
out=${GUI_OUT:-$here/out}
size=${GUI_SIZE:-1600x1000}
engine=${GUI_ENGINE:-$(command -v podman >/dev/null && echo podman || echo docker)}

# Git Bash would otherwise rewrite container paths such as /work into Windows paths.
export MSYS_NO_PATHCONV=1

host_path() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else echo "$1"; fi; }
in_box() { "$engine" exec "$name" "$@"; }

run_opts=()
if [[ $(uname -s) == Linux ]]; then
  # Screenshots must be owned by the host user, and SELinux would block the bind mounts.
  if [[ $engine == podman ]]; then run_opts+=(--userns=keep-id); else run_opts+=(--user "$(id -u):$(id -g)"); fi
  run_opts+=(--security-opt label=disable)
fi

case "${1:-}" in
  build)
    "$engine" build -t "$image" -f "$here/Containerfile" "$here"
    ;;
  start)
    shift
    [[ -f $root/app/build/libs/logxy.jar ]] || { echo "app/build/libs/logxy.jar missing; run ./gradlew -q :app:shadowJar" >&2; exit 1; }
    "$engine" image inspect "$image" >/dev/null 2>&1 || "$0" build
    # Each file's or folder's parent is mounted separately, so they can live anywhere on the host.
    mounts=() args=() i=0
    for f in "$@"; do
      [[ -e $f ]] || { echo "no such file or folder: $f" >&2; exit 1; }
      mounts+=(-v "$(host_path "$(cd "$(dirname "$f")" && pwd)"):/data/$i:ro")
      args+=("/data/$i/$(basename "$f")")
      i=$((i + 1))
    done
    "$engine" rm -f "$name" >/dev/null 2>&1 || true
    mkdir -p "$out"
    # JAVA_TOOL_OPTIONS reaches the app, e.g. -Xmx64m to see how it copes with little memory.
    "$engine" run -d --name "$name" "${run_opts[@]}" -e HOME=/tmp -e JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-}" \
      -v "$(host_path "$root/app/build/libs"):/app:ro" -v "$(host_path "$out"):/out" "${mounts[@]}" \
      "$image" bash -c "
        Xvfb :99 -screen 0 ${size}x24 -nolisten tcp &
        until xdpyinfo >/dev/null 2>&1; do sleep 0.1; done
        openbox &
        exec java --add-modules jdk.incubator.vector -jar /app/logxy.jar ${args[*]@Q} > /out/app.log 2>&1" >/dev/null
    for _ in $(seq 1 100); do
      in_box xdotool search --onlyvisible --name . >/dev/null 2>&1 && break
      sleep 0.2
    done
    echo "started with $engine; output in $out"
    ;;
  shot)
    f="${2:-shot}.png"
    if [[ $# -ge 6 ]]; then
      in_box import -window root -crop "${5}x${6}+${3}+${4}" +repage "/out/$f"
    else
      in_box import -window root "/out/$f"
    fi
    echo "$out/$f"
    ;;
  click)  in_box xdotool mousemove "$2" "$3" click 1 ;;
  dclick) in_box xdotool mousemove "$2" "$3" click --repeat 2 1 ;;
  rclick) in_box xdotool mousemove "$2" "$3" click 3 ;;
  move)   in_box xdotool mousemove "$2" "$3" ;;
  type)   in_box xdotool type --delay 30 "$2" ;;
  key)    shift; in_box xdotool key --delay 50 "$@" ;;
  clip)   in_box bash -c 'timeout 60 xclip -o -selection clipboard > /tmp/clip || exit 1
            echo "$(wc -c < /tmp/clip) bytes"; head -1 /tmp/clip | cut -c1-200; tail -1 /tmp/clip | cut -c1-200' ;;
  windows) in_box bash -c 'for w in $(xdotool search --onlyvisible --name .); do echo "$w $(xdotool getwindowname $w)"; xdotool getwindowgeometry $w | tail -2; done' ;;
  wait)   in_box timeout "${3:-60}" bash -c 'until xdotool search --onlyvisible --name "$1" >/dev/null 2>&1; do sleep 0.5; done' _ "$2" \
            || { echo "no window matching '$2' after ${3:-60} s" >&2; exit 1; } ;;
  pause)  in_box sleep "$2" ;;
  exec)   shift; in_box "$@" ;;
  logs)   cat "$out/app.log" ;;
  stop)   "$engine" rm -f "$name" >/dev/null && echo stopped ;;
  *) sed -n '3,17p' "$0" | cut -c3-; exit 1 ;;
esac
