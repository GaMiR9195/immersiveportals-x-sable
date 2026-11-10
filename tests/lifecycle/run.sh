#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
out="$(mktemp -d)"; trap 'rm -rf "$out"' EXIT
if [[ -n "${JAVA_HOME:-}" ]]; then JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"; else JAVAC=javac; JAVA=java; fi
cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches"
fastutil="${FASTUTIL_JAR:-$(find "$cache" -name 'fastutil-8.5.*.jar' -print -quit 2>/dev/null || true)}"
joml="${JOML_JAR:-$(find "$cache" -name 'joml-1.10.*.jar' -print -quit 2>/dev/null || true)}"
if [[ -z "$fastutil" || -z "$joml" ]]; then echo 'Build once or provide FASTUTIL_JAR and JOML_JAR' >&2; exit 1; fi
python3 tests/lifecycle/generate-harness.py "$out/generated"
mapfile -t fixtures < <(find tests/lifecycle/fixtures -name '*.java' ! -path '*/ipl/sable/transit/IplStraddleSessionSync.java'; find tests/pieces/fixtures/net/minecraft/nbt -name '*.java'; printf '%s\n' tests/pieces/fixtures/net/minecraft/core/SectionPos.java tests/pieces/fixtures/org/jetbrains/annotations/Nullable.java)
"$JAVAC" --release 21 -cp "$fastutil:$joml" -d "$out/classes" "${fixtures[@]}" "$out/generated/"*.java \
src/main/java/ipl/sable/transit/{IplPieceMap,IplApertureSweep,IplTransitSettlement,IplMaterialSnapshot,IplSessionLifecycle,IplTransitPath,IplGroupTransitContext,IplTransitConnections,StraddleKey}.java tests/lifecycle/LifecycleTest.java
"$JAVA" -cp "$out/classes:$fastutil:$joml" ipl.sable.transit.LifecycleTest
