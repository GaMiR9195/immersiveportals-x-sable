package ipl.sable.atlas;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import ipl.sable.mixin.RapierPipelineAccessor;
import ipl.sable.natives.IplRapierNatives;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Per-level Atlas chart lookup for the native Sable model.
 *
 * <p>Every sub-level lives in its own level and its body is simulated in that level's
 * chart of the fused Atlas world. Portal projections are extra collider images of the
 * SAME body registered into the destination level's chart.
 */
public final class IplScenes {
    private IplScenes() {}

    /** Projections need the IPL Rapier natives (image colliders, clip regions). */
    public static boolean isEnabled() {
        return IplRapierNatives.isAvailable();
    }

    @Nullable
    public static RapierPhysicsPipeline pipelineOf(@Nullable ServerLevel level) {
        if (level == null) return null;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        return container.physicsSystem().getPipeline() instanceof RapierPhysicsPipeline rapier
            ? rapier : null;
    }

    /** Native scene (chart) handle of {@code level}, or 0 when it has no live Rapier scene. */
    public static long sceneHandle(@Nullable ServerLevel level) {
        RapierPhysicsPipeline pipeline = pipelineOf(level);
        if (pipeline == null) return 0;
        RapierPipelineAccessor access = (RapierPipelineAccessor) pipeline;
        return access.ipl$scene() == null ? 0 : access.ipl$sceneHandle();
    }

    /** The level whose container currently owns sub-level {@code id}, or null. */
    @Nullable
    public static ServerLevel ownerLevel(@Nullable MinecraftServer server, @Nullable UUID id) {
        if (server == null || id == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null && container.getSubLevel(id) instanceof ServerSubLevel sub
                && !sub.isRemoved()) return level;
        }
        return null;
    }

    /** The live server sub-level {@code id} in whichever level owns it, or null. */
    @Nullable
    public static ServerSubLevel findSubLevel(
        @Nullable MinecraftServer server, @Nullable UUID id
    ) {
        if (server == null || id == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null && container.getSubLevel(id) instanceof ServerSubLevel sub
                && !sub.isRemoved()) return sub;
        }
        return null;
    }

    /**
     * The live ship whose plot contains block {@code pos}: {@code context}'s own grid first,
     * then every other level. Plot slots are allocated server-wide unique
     * ({@code SubLevelContainerMixin}), so the fallback is unambiguous; it is how a
     * PROJECTED ship's plot (owned by another dimension) is found.
     */
    @Nullable
    public static ServerSubLevel plotOwner(
        @Nullable ServerLevel context, BlockPos pos
    ) {
        if (context == null) return null;
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        ServerSubLevel own = plotSub(context, chunkX, chunkZ);
        if (own != null || context.getServer() == null) return own;
        for (ServerLevel level : context.getServer().getAllLevels()) {
            if (level == context) continue;
            ServerSubLevel found = plotSub(level, chunkX, chunkZ);
            if (found != null) return found;
        }
        return null;
    }

    @Nullable
    private static ServerSubLevel plotSub(ServerLevel level, int chunkX, int chunkZ) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || !container.inBounds(chunkX, chunkZ)) return null;
        LevelPlot plot = container.getPlot(chunkX, chunkZ);
        return plot != null && plot.getSubLevel() instanceof ServerSubLevel sub
            && !sub.isRemoved() ? sub : null;
    }
}
