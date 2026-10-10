#!/usr/bin/env bash
# Runs the app on a virtual X display inside a container so it never touches the host desktop.
# Usage: gui.sh build | start [file...] | shot [name] | click X Y | dclick X Y | rclick X Y | move X Y
#        | type TEXT | key KEYS... | windows | exec CMD... | logs | stop
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
    [[ -f $root/app/target/logxy.jar ]] || { echo "app/target/logxy.jar missing; run ./mvnw -q install -DskipTests" >&2; exit 1; }
    "$engine" image inspect "$image" >/dev/null 2>&1 || "$0" build
    # Each file's directory is mounted separately, so files can live anywhere on the host.
    mounts=() args=() i=0
    for f in "$@"; do
      [[ -f $f ]] || { echo "no such file: $f" >&2; exit 1; }
      mounts+=(-v "$(host_path "$(cd "$(dirname "$f")" && pwd)"):/data/$i:ro")
      args+=("/data/$i/$(basename "$f")")
      i=$((i + 1))
    done
    "$engine" rm -f "$name" >/dev/null 2>&1 || true
    mkdir -p "$out"
    "$engine" run -d --name "$name" "${run_opts[@]}" -e HOME=/tmp \
      -v "$(host_path "$root/app/target"):/app:ro" -v "$(host_path "$out"):/out" "${mounts[@]}" \
      "$image" bash -c "
        Xvfb :99 -screen 0 ${size}x24 -nolisten tcp &
        until xdpyinfo >/dev/null 2>&1; do sleep 0.1; done
        openbox &
        exec java -jar /app/logxy.jar ${args[*]@Q} > /out/app.log 2>&1" >/dev/null
    for _ in $(seq 1 100); do
      in_box xdotool search --onlyvisible --name . >/dev/null 2>&1 && break
      sleep 0.2
    done
    echo "started with $engine; output in $out"
    ;;
  shot)
    f="${2:-shot}.png"
    in_box import -window root "/out/$f"
    echo "$out/$f"
    ;;
  click)  in_box xdotool mousemove "$2" "$3" click 1 ;;
  dclick) in_box xdotool mousemove "$2" "$3" click --repeat 2 1 ;;
  rclick) in_box xdotool mousemove "$2" "$3" click 3 ;;
  move)   in_box xdotool mousemove "$2" "$3" ;;
  type)   in_box xdotool type --delay 30 "$2" ;;
  key)    shift; in_box xdotool key --delay 50 "$@" ;;
  windows) in_box bash -c 'for w in $(xdotool search --onlyvisible --name .); do echo "$w $(xdotool getwindowname $w)"; xdotool getwindowgeometry $w | tail -2; done' ;;
  exec)   shift; in_box "$@" ;;
  logs)   cat "$out/app.log" ;;
  stop)   "$engine" rm -f "$name" >/dev/null && echo stopped ;;
  *) sed -n '3,4p' "$0"; exit 1 ;;
esac
