package ipl.sable.atlas;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import ipl.sable.mixin.IplRapierPipelineAccess;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

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
        return ipl.sable.natives.IplRapierNatives.isAvailable();
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
        IplRapierPipelineAccess access = (IplRapierPipelineAccess) pipeline;
        return access.ipl$scene() == null ? 0 : access.ipl$sceneHandle();
    }

    /** The level whose container currently owns sub-level {@code id}, or null. */
    @Nullable
    public static ServerLevel ownerLevel(@Nullable net.minecraft.server.MinecraftServer server, @Nullable java.util.UUID id) {
        if (server == null || id == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null && container.getSubLevel(id) instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel sub
                && !sub.isRemoved()) return level;
        }
        return null;
    }

    /** The live server sub-level {@code id} in whichever level owns it, or null. */
    @Nullable
    public static dev.ryanhcode.sable.sublevel.ServerSubLevel findSubLevel(
        @Nullable net.minecraft.server.MinecraftServer server, @Nullable java.util.UUID id
    ) {
        if (server == null || id == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null && container.getSubLevel(id) instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel sub
                && !sub.isRemoved()) return sub;
        }
        return null;
    }

    /**
     * The live ship whose plot contains block {@code pos}: {@code context}'s own grid first,
     * then every other level. Plot slots are allocated server-wide unique
     * ({@code IplUnionPlotAllocationMixin}), so the fallback is unambiguous; it is how a
     * PROJECTED ship's plot (owned by another dimension) is found.
     */
    @Nullable
    public static dev.ryanhcode.sable.sublevel.ServerSubLevel plotOwner(
        @Nullable ServerLevel context, net.minecraft.core.BlockPos pos
    ) {
        if (context == null) return null;
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        dev.ryanhcode.sable.sublevel.ServerSubLevel own = plotSub(context, chunkX, chunkZ);
        if (own != null || context.getServer() == null) return own;
        for (ServerLevel level : context.getServer().getAllLevels()) {
            if (level == context) continue;
            dev.ryanhcode.sable.sublevel.ServerSubLevel found = plotSub(level, chunkX, chunkZ);
            if (found != null) return found;
        }
        return null;
    }

    @Nullable
    private static dev.ryanhcode.sable.sublevel.ServerSubLevel plotSub(ServerLevel level, int chunkX, int chunkZ) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || !container.inBounds(chunkX, chunkZ)) return null;
        dev.ryanhcode.sable.sublevel.plot.LevelPlot plot = container.getPlot(chunkX, chunkZ);
        return plot != null && plot.getSubLevel() instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel sub
            && !sub.isRemoved() ? sub : null;
    }
}
