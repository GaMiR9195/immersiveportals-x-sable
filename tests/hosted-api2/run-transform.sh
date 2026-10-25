#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
cpfile="${1:-build/hosted-api2-classpath.txt}"
[[ -f "$cpfile" ]] || { echo 'First: ./gradlew assemble writeHostedApi2Classpath -I tests/hosted-api2/write-classpath.gradle' >&2; exit 1; }
cp="$(cat "$cpfile")"
# Optional compiled PYM classes from the provided sources, not repackaged upstream code.
if [[ -n "${2:-}" ]]; then cp="$2:$cp"; fi
out="$(mktemp -d)"; trap 'rm -rf "$out"' EXIT
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"; JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
cp -r tests/hosted-api2/harness-resources/* "$out/"
"$JAVAC" -proc:none -cp "build/classes/java/main:$cp" -d "$out" tests/hosted-api2/harness/*.java tests/hosted-api2/TargetAudit.java
"$JAVA" -cp "$out:build/classes/java/main:$cp" TargetAudit
"$JAVA" -cp "$out:build/classes/java/main:src/main/resources:$cp" harness.ApplyMixins \
 dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage \
 net.minecraft.world.level.BlockGetter net.minecraft.world.level.Level net.minecraft.server.network.ServerGamePacketListenerImpl net.minecraft.world.entity.Entity net.minecraft.world.level.entity.PersistentEntitySectionManager net.minecraft.world.level.entity.TransientEntitySectionManager net.minecraft.client.Minecraft \
 dev.ryanhcode.sable.physics.impl.rapier.Rapier3D \
 dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem \
 dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline \
 com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler \
 dev.ryanhcode.sable.api.sublevel.SubLevelContainer dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap dev.ryanhcode.sable.ActiveSableCompanion net.minecraft.server.MinecraftServer \
 net.minecraft.server.players.PlayerList net.minecraft.server.level.ServerLevel \
 net.minecraft.client.multiplayer.ClientLevel \
 dev.engine_room.flywheel.lib.visualization.VisualizationHelper \
 dev.engine_room.flywheel.impl.visualization.VisualizationEventHandler \
 dev.ryanhcode.sable.neoforge.compatibility.flywheel.FlywheelCompatNeoForge \
 dev.engine_room.flywheel.impl.visualization.storage.Storage \
 net.minecraft.world.level.chunk.LevelChunk net.minecraft.world.level.block.entity.BlockEntity \
 net.minecraft.client.renderer.LevelRenderer net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer dev.engine_room.flywheel.api.visualization.VisualizationManager \
 dev.ryanhcode.sable.util.SubLevelInclusiveLevelEntityGetter dev.ryanhcode.sable.sublevel.ServerSubLevel dev.ryanhcode.sable.sublevel.ClientSubLevel \
 dev.ryanhcode.sable.neoforge.platform.SableEventPlatformImpl net.minecraft.client.sounds.SoundEngine \
 dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl net.minecraft.server.level.ChunkMap

"$JAVA" -cp "$out:build/classes/java/main:$cp" harness.OwnershipAudit
