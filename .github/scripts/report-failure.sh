#!/usr/bin/env bash
# Re-emits the interesting lines of a build log as GitHub Actions annotations.
#
# The raw log of a failed step is only downloadable from the Actions UI; annotations are visible
# in `gh run view` and in the checks API, so a failure is diagnosable without them.
#
#   report-failure.sh <log file> [title]
set -uo pipefail

LOG="${1:-}"
TITLE="${2:-Build failed}"

if [ -z "$LOG" ] || [ ! -f "$LOG" ]; then
  echo "::error::$TITLE (no log file to analyse)"
  exit 0
fi

echo "::error::$TITLE — the first errors Gradle reported are listed below"

# Gradle/Kotlin/AGP error shapes, in the order they normally matter.
grep -E "^e: |^error: |^FAILURE:|^\* What went wrong:|^> |^Execution failed for task|Caused by:|requires Java|Unresolved reference|Syntax error|Cannot resolve" "$LOG" \
  | sed 's/\r$//' \
  | awk 'length($0) < 800' \
  | head -n 40 \
  | while IFS= read -r line; do
      # `::error::` must stay on one line and cannot contain raw % or newlines.
      safe="${line//%/%25}"
      echo "::error::${safe}"
    done

echo "::error::Full log tail (Gradle):"
tail -n 25 "$LOG" | sed 's/\r$//' | awk 'length($0) < 800' | while IFS= read -r line; do
  safe="${line//%/%25}"
  echo "::error::${safe}"
done
