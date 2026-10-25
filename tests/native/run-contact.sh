#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
out="$(mktemp -d)";trap 'rm -rf "$out"' EXIT
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" --release 21 -d "$out" src/main/java/ipl/sable/natives/IplRapierNatives.java tests/native/NativeContactSmoke.java $(find tests/native/fixtures -name '*.java')
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$out" NativeContactSmoke "$1"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$out" NativeContactSmoke "$1" atlas
