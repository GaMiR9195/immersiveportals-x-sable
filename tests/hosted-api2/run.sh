#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
JOML_JAR="${JOML_JAR:-$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches" -name 'joml-1.10.5.jar' -print -quit)}"
ANNOTATIONS_JAR="${ANNOTATIONS_JAR:-$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2" -path '*/org.jetbrains/annotations/*' -name '*.jar' -print -quit)}"
out="$(mktemp -d)";trap 'rm -rf "$out"' EXIT
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"; JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
mapfile -t fixtures < <(find tests/hosted-api2/fixtures -name '*.java')
"$JAVAC" --release 21 -cp "$JOML_JAR:$ANNOTATIONS_JAR" -d "$out" "${fixtures[@]}" src/main/java/ipl/sable/dim/{IplReadViewScope,IplCollisionCallbackContext,IplHostedSpatialQueries,IplSubLevelReadViews,IplProcessingPass,IplHostedPlotHeight}.java src/main/java/ipl/sable/compat/IplMovingLinkNetworks.java src/main/java/ipl/sable/client/IplClientVisualViewContext.java tests/hosted-api2/{HostedBoundariesTest,ProcessingPassTest,MovingNetworksTest,VisualViewContextTest,HeightProfilesTest}.java
"$JAVA" -cp "$out:$JOML_JAR:$ANNOTATIONS_JAR" ipl.sable.dim.HostedBoundariesTest

"$JAVA" -cp "$out:$JOML_JAR:$ANNOTATIONS_JAR" ipl.sable.dim.ProcessingPassTest

"$JAVA" -cp "$out:$JOML_JAR:$ANNOTATIONS_JAR" ipl.sable.compat.MovingNetworksTest

"$JAVA" -cp "$out:$JOML_JAR:$ANNOTATIONS_JAR" ipl.sable.client.VisualViewContextTest

"$JAVA" -cp "$out:$JOML_JAR:$ANNOTATIONS_JAR" ipl.sable.dim.HeightProfilesTest

# Optional original callback source from the user-provided Sable source tree.
if [[ -n "${SABLE_SOURCES:-}" ]]; then
 "$JAVAC" --release 21 -cp "$out:$JOML_JAR:$ANNOTATIONS_JAR" -d "$out" "$SABLE_SOURCES/common/src/main/java/dev/ryanhcode/sable/physics/callback/FragileBlockCallback.java" tests/hosted-api2/FragileCallbackTest.java
 "$JAVA" -cp "$out:$JOML_JAR:$ANNOTATIONS_JAR" ipl.sable.dim.FragileCallbackTest
fi
