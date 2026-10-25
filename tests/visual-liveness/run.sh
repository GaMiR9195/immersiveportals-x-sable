#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
out="$(mktemp -d)";trap 'rm -rf "$out"' EXIT
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" --release 21 -d "$out" $(find tests/visual-liveness/fixtures -name '*.java') src/main/java/ipl/sable/client/IplHostedBlockEntityLiveness.java tests/visual-liveness/VisualLivenessTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$out" ipl.sable.client.VisualLivenessTest
