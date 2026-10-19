#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
out="$(mktemp -d)"; trap 'rm -rf "$out"' EXIT
if [[ -n "${JAVA_HOME:-}" ]]; then JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"; else JAVAC=javac; JAVA=java; fi
mapfile -t fixtures < <(find tests/gl/fixtures -name '*.java')
"$JAVAC" --release 21 -d "$out" "${fixtures[@]}" src/main/java/ipl/sable/duck/IplSubLevelClipShader.java src/main/java/ipl/sable/render/IplProgramRegistry.java src/main/java/ipl/sable/render/IplProgramBindHook.java tests/gl/ProgramBindTest.java
"$JAVA" -cp "$out" ipl.sable.render.ProgramBindTest
