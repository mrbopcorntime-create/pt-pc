#!/bin/bash
# ci_run.sh <title> <command...>: runs a CI command with its output in $RUNNER_TEMP/<title>.log (printed as well). On
# failure the log's last lines become an error annotation, which the GitHub API returns with the check run (the raw job
# logs live on a storage host some tools cannot reach).
title="$1"
shift
log="${RUNNER_TEMP:-/tmp}/${title// /_}.log"
set -o pipefail
"$@" 2>&1 | tee "$log"
status=$?
if [ "$status" -ne 0 ]; then
  tr "\r" "\n" < "$log" | grep -v "^Updating files\|^Receiving objects\|^Resolving deltas" | tail -n 80 | cut -c1-300 | sed -e 's/%/%25/g' | awk 'BEGIN { ORS = "%0A" } { print }' > "$log.tail"
  echo "::error title=${title} failed (exit ${status})::$(cat "$log.tail")"
fi
exit "$status"
