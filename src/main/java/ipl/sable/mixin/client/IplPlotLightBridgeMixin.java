package ipl.sable.mixin.client;

import ipl.sable.client.IplClientHostedLookup;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.WritableLevelData;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Supplier;

/**
 * THE PLOT LIGHT BRIDGE — extend the plot-grid "universal address space" doctrine
 * ({@code IplPlotBridgeMixin}) to LIGHT reads.
 *
 * <p>Sable's client chunk-cache mixins + the plot bridge make plot-coordinate BLOCK reads
 * work from any dimension, but light queries go through the level's OWN
 * {@code LevelLightEngine}, which has no data for plot sections outside the hosting
 * dimension. With the client identity fix ({@code IplHostedBeLevelIdentityMixin}) a hosted
 * BE's {@code getLevel()} is its parent level, so vanilla's
 * {@code BlockEntityRenderDispatcher} computes
 * {@code LevelRenderer.getLightColor(parentLevel, plotPos)} — without this bridge that
 * reads an empty engine and every hosted chest/sign/spring would render black.
 *
 * <p>Merged overrides (the proven router pattern): plot-range positions on a non-hosting
 * {@code ClientLevel} resolve through the hosting level's light engine — the same plot-local
 * light (void skylight + ship block light) that stock Sable's same-level plots get. All
 * other positions fall through to the stock path.
 */
@Mixin(value = ClientLevel.class, priority = 1200)
public abstract class IplPlotLightBridgeMixin extends Level {

    protected IplPlotLightBridgeMixin(
        WritableLevelData levelData, ResourceKey<Level> dimension, RegistryAccess registryAccess,
        Holder<DimensionType> dimensionTypeRegistration, Supplier<ProfilerFiller> profiler,
        boolean isClientSide, boolean isDebug, long biomeZoomSeed, int maxChainedNeighborUpdates
    ) {
        super(levelData, dimension, registryAccess, dimensionTypeRegistration, profiler,
            isClientSide, isDebug, biomeZoomSeed, maxChainedNeighborUpdates);
    }

    @Unique
    @Nullable
    private ClientLevel ipl$plotLightTarget(BlockPos pos) {
        // Plot chunks live at |coord| >= 1,000,000; everything else is native.
        if (Math.abs(pos.getX()) < 1_000_000 && Math.abs(pos.getZ()) < 1_000_000) return null;
        if (IplDimAgnostic.isHostingLevel(this)) return null;
        ClientLevel hosting = IplClientHostedLookup.getHostingClientLevelOrNull();
        return hosting == null || hosting == (Object) this ? null : hosting;
    }

    @Override
    public int getBrightness(LightLayer lightType, BlockPos pos) {
        ClientLevel hosting = ipl$plotLightTarget(pos);
        // Stock body replicated (BlockAndTintGetter default) — avoids an invokespecial
        // into an interface default through the mixin superclass.
        return hosting != null
            ? hosting.getBrightness(lightType, pos)
            : this.getLightEngine().getLayerListener(lightType).getLightValue(pos);
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        ClientLevel hosting = ipl$plotLightTarget(pos);
        return hosting != null
            ? hosting.getRawBrightness(pos, amount)
            : this.getLightEngine().getRawBrightness(pos, amount);
    }
}
