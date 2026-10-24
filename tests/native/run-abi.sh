#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
[[ $# == 1 ]] || { echo 'Usage: run-abi.sh /absolute/path/to/native-library' >&2; exit 1; }
out="$(mktemp -d)"; trap 'rm -rf "$out"' EXIT
if [[ -n "${JAVA_HOME:-}" ]]; then JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"; else JAVAC=javac; JAVA=java; fi
mapfile -t fixtures < <(find tests/native/fixtures -name '*.java')
"$JAVAC" --release 21 -d "$out" src/main/java/ipl/sable/natives/IplRapierNatives.java tests/native/NativeAbiSmoke.java "${fixtures[@]}"
"$JAVA" -cp "$out" NativeAbiSmoke "$1"
