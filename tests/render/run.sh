#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
out="$(mktemp -d)"; trap 'rm -rf "$out"' EXIT
if [[ -n "${JAVA_HOME:-}" ]]; then JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"; else JAVAC=javac; JAVA=java; fi
mapfile -t fixtures < <(find tests/render/fixtures -name '*.java')
"$JAVAC" --release 21 -d "$out" "${fixtures[@]}"   src/main/java/ipl/sable/client/IplStraddleRenderCache.java tests/render/RenderCacheTest.java
"$JAVA" -cp "$out" ipl.sable.client.RenderCacheTest

"$JAVAC" --release 21 -d "$out" src/main/java/ipl/sable/transit/PortalSectionMath.java src/main/java/ipl/sable/render/IplRenderCullMath.java tests/render/GeometryTest.java
"$JAVA" -cp "$out" ipl.sable.render.GeometryTest

"$JAVAC" --release 21 -cp "$out" -d "$out" src/main/java/ipl/sable/render/IplSubLevelBlockEntityPresence.java tests/render/BlockEntityPresenceTest.java
"$JAVA" -cp "$out" ipl.sable.render.BlockEntityPresenceTest
