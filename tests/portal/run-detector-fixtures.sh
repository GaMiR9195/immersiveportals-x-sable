#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# JOML is supplied by Minecraft/Gradle. Explicit path also supports offline CI.
if [[ -z "${JOML_JAR:-}" ]]; then
  JOML_JAR="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches" -name 'joml-1.10.5.jar' -print -quit 2>/dev/null || true)"
fi
[[ -f "$JOML_JAR" ]] || { echo 'Set JOML_JAR to JOML 1.10.5 (Minecraft dependency).' >&2; exit 1; }
out="$(mktemp -d)"; trap 'rm -rf "$out"' EXIT
if [[ -n "${JAVA_HOME:-}" ]]; then JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"; else JAVAC=javac; JAVA=java; fi
mapfile -t fixtures < <(find tests/portal/fixtures -name '*.java')
"$JAVAC" --release 21 -cp "$JOML_JAR" -d "$out" "${fixtures[@]}" \
  src/main/java/ipl/sable/transit/PortalSweepMath.java \
  src/main/java/ipl/sable/transit/PortalCrossingDetector.java \
  tests/portal/PortalCrossingDetectorFixtureTest.java
"$JAVA" -cp "$out:$JOML_JAR" ipl.sable.transit.PortalCrossingDetectorFixtureTest
