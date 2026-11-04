#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"
else
  JAVAC=javac; JAVA=java
fi
f=tests/portal/fixtures
"$JAVAC" --release 21 -d "$out" \
  "$f/org/jetbrains/annotations/Nullable.java" "$f/net/minecraft/world/phys/Vec3.java" \
  "$f/net/minecraft/world/level/Level.java" "$f/net/minecraft/resources/ResourceKey.java" \
  "$f/qouteall/imm_ptl/core/portal/Portal.java" \
  src/main/java/ipl/sable/transit/PortalSweepMath.java \
  src/main/java/ipl/sable/transit/PortalPrism.java \
  src/main/java/ipl/sable/transit/IplCollisionRegion.java \
  tests/portal/PortalSweepMathTest.java tests/portal/PortalPrismTest.java tests/portal/CollisionRegionTest.java
"$JAVA" -cp "$out" ipl.sable.transit.PortalSweepMathTest
"$JAVA" -cp "$out" ipl.sable.transit.PortalPrismTest
"$JAVA" -cp "$out" ipl.sable.transit.CollisionRegionTest
