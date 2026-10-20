#!/usr/bin/env bash
# Prints log lines with ANSI colours, some on stderr, and echoes what is typed into the console.
for i in $(seq 1 300); do
  ts=$(date '+%Y-%m-%d %H:%M:%S.%3N')
  if (( i % 4 == 0 )); then
    printf '%s [sh] \e[31mWARN \e[0m demo.sh - step %d slow, see /etc/hostname\n' "$ts" "$i" >&2
  else
    printf '%s [sh] \e[32mINFO \e[0m demo.sh - step %d done\n' "$ts" "$i"
  fi
  if read -r -t 0.3 line; then
    printf '%s [sh] INFO  demo.sh - typed: %s\n' "$(date '+%Y-%m-%d %H:%M:%S.%3N')" "$line"
  fi
done
