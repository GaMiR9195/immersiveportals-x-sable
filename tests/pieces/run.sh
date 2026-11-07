#!/usr/bin/env bash
# fastutil and JOML come from the Minecraft dependencies in the Gradle cache.
set -euo pipefail
cd "$(dirname "$0")/../.."
out="$(mktemp -d)"; trap 'rm -rf "$out"' EXIT
if [[ -n "${JAVA_HOME:-}" ]]; then JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"; else JAVAC=javac; JAVA=java; fi
cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches"
fastutil="${FASTUTIL_JAR:-$(find "$cache" -name 'fastutil-8.5.*.jar' -print -quit 2>/dev/null || true)}"
joml="${JOML_JAR:-$(find "$cache" -name 'joml-1.10.*.jar' -print -quit 2>/dev/null || true)}"
if [[ -z "$fastutil" || -z "$joml" ]]; then
  echo "fastutil/JOML not found under $cache (build the mod once or set GRADLE_USER_HOME)" >&2; exit 1
fi
mapfile -t fixtures < <(find tests/pieces/fixtures -name '*.java')
"$JAVAC" --release 21 -cp "$fastutil:$joml" -d "$out" "${fixtures[@]}" src/main/java/ipl/sable/transit/IplPieceMap.java src/main/java/ipl/sable/transit/IplApertureSweep.java tests/pieces/PieceMapTest.java
"$JAVA" -cp "$out:$fastutil:$joml" ipl.sable.transit.PieceMapTest
