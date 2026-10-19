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
"$JAVAC" --release 21 -d "$out" src/main/java/ipl/sable/transit/PortalSweepMath.java tests/portal/PortalSweepMathTest.java
"$JAVA" -cp "$out" ipl.sable.transit.PortalSweepMathTest
