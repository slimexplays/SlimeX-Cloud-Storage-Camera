#!/usr/bin/env bash
# Re-emits the important part of a failed Gradle log as GitHub Actions annotations.
#
# Raw step logs are only accessible from the Actions UI (the log download goes through a storage
# host that is not always reachable), while annotations are available from the checks API and in
# `gh run view`. So a failure is turned into a short, prioritised annotation digest:
#
#   1. Kotlin / Java compiler errors (`e: …`, `error: …`)
#   2. the "FAILURE: Build failed with an exception." block, which is where a Gradle configuration
#      error (an `app/build.gradle.kts` script error included) states what is actually wrong
#   3. the last lines of the log
#
#   report-failure.sh <log file> [title]
set -uo pipefail

LOG="${1:-}"
TITLE="${2:-Build failed}"

if [ -z "$LOG" ] || [ ! -f "$LOG" ]; then
  echo "::error::$TITLE (no log file to analyse)"
  exit 0
fi

# Strip carriage returns and ANSI escapes so annotations stay readable.
CLEAN="$(mktemp)"
sed -e 's/\r$//' -e 's/\x1b\[[0-9;]*[A-Za-z]//g' "$LOG" > "$CLEAN"

emit() {
  # GitHub annotation data cannot contain raw % or newlines.
  local line="$1"
  line="${line//%/%25}"
  line="${line//$'\n'/ }"
  echo "::error::${line:0:900}"
}

echo "::error::$TITLE — digest below (first compiler errors, then Gradle's failure block)"

# 1. Failing unit tests (their names never appear in the Gradle failure block).
mapfile -t TESTFAILS < <(grep -E "^(\S+ )?[A-Za-z0-9_.]+(Test|Tests) > .*FAILED$|^[A-Za-z0-9_.]+ > .*FAILED$" "$CLEAN" | head -n 10)
for line in "${TESTFAILS[@]:-}"; do
  [ -n "$line" ] && emit "$line"
done

# 2. R8 / resource shrinking problems (release builds only).
mapfile -t R8LINES < <(grep -E "Missing class|ERROR: R8|^R8: |Missing classes detected|Compilation failed to complete" "$CLEAN" | head -n 14)
for line in "${R8LINES[@]:-}"; do
  [ -n "$line" ] && emit "$line"
done

# 3. Compiler errors: these are what usually break the build.
mapfile -t COMPILER < <(grep -E '^(e: |error: |.*error: )' "$CLEAN" | head -n 12)
if [ "${#COMPILER[@]}" -gt 0 ]; then
  for line in "${COMPILER[@]}"; do emit "$line"; done
else
  emit "No Kotlin/Java compiler errors were printed before the failure."
fi

# 4. Gradle's own explanation, from "FAILURE:" until the stack trace starts.
awk '/^FAILURE: Build failed/{flag=1} flag' "$CLEAN" \
  | awk '/^\* Exception is:/{exit} {print}' \
  | head -n 22 \
  | while IFS= read -r line; do
      [ -n "$line" ] && emit "$line"
    done

# 5. Same digest in the job summary, which is readable in the run page.
{
  echo "### $TITLE"
  echo
  echo '```'
  grep -E '^(e: |error: )' "$CLEAN" | head -n 8
  echo
  grep -E "^[A-Za-z0-9_.]+(Test|Tests)? > .*FAILED$" "$CLEAN" | head -n 10
  echo
  grep -E "Missing class|ERROR: R8" "$CLEAN" | head -n 14
  echo
  awk '/^FAILURE: Build failed/{flag=1} flag' "$CLEAN" | awk '/^\* Exception is:/{exit} {print}' | head -n 22
  echo '```'
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"

rm -f "$CLEAN"
exit 0
