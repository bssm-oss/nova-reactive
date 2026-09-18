#!/usr/bin/env bash
# PostToolUse hook for Edit/Write.
# Runs ./gradlew compileJava only when the edited file is a Java source.
# Output is shown to the agent so compile errors surface immediately.

set -u

input=$(cat)

if command -v jq >/dev/null 2>&1; then
    file=$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty')
else
    file=$(printf '%s' "$input" | sed -nE 's/.*"file_path"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/p' | head -1)
fi

case "$file" in
    *.java)
        cd "$(dirname "$0")/../.."
        ./gradlew compileJava -q 2>&1 | tail -40
        ;;
    *)
        exit 0
        ;;
esac
