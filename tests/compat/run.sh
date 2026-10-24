#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
JOML_JAR="${JOML_JAR:-$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches" -name 'joml-1.10.5.jar' -print -quit 2>/dev/null || true)}"
[[ -f "$JOML_JAR" ]] || { echo 'Set JOML_JAR to JOML 1.10.5' >&2; exit 1; }
out="$(mktemp -d)"; trap 'rm -rf "$out"' EXIT
if [[ -n "${JAVA_HOME:-}" ]]; then JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"; else JAVAC=javac; JAVA=java; fi
mapfile -t fixtures < <(find tests/compat/fixtures -name '*.java')
"$JAVAC" --release 21 -cp "$JOML_JAR" -d "$out" "${fixtures[@]}" src/main/java/ipl/sable/dim/IplEnvironmentFrame.java src/main/java/ipl/sable/client/IplClientEnvironmentBridge.java tests/compat/EnvironmentFrameTest.java tests/compat/EnvironmentServerLinkTest.java
"$JAVA" -cp "$out:$JOML_JAR" ipl.sable.dim.EnvironmentFrameTest

rm "$out/ipl/sable/client/IplClientEnvironmentBridge.class" "$out/ipl/sable/dim/IplClientWorldFrameContext.class"
"$JAVA" -cp "$out:$JOML_JAR" ipl.sable.dim.EnvironmentServerLinkTest
