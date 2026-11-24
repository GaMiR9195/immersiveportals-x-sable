package ipl.sable.transit;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * One kind of kinematic structure mounted on bodies (Create's contraptions are one), registered
 * with {@link IplContraptions}. Portals, pieces, collision and rendering only see what a kind
 * reports here, so another mod's structures join by registering a kind.
 */
public interface IplContraptionKind {

    /** Whether {@code entity} is a structure of this kind; the other methods are asked only then. */
    boolean owns(Entity entity);

    /** A structure whose blocks never arrived on this side (nothing to draw or collide with). */
    boolean isHollow(Entity entity);

    /**
     * The structure at {@code partialTick} in its own level's frame (the plot of its body), or null.
     * Build it with {@link IplContraptionShape#of}.
     */
    @Nullable
    IplContraptionShape shape(Entity entity, float partialTick);

    /**
     * Box {@code [minX, minY, minZ, maxX, maxY, maxZ]} in its level's frame around everything the
     * structure covers in any pose it moves to. Asked for every body each tick: keep it cheap.
     */
    @Nullable
    double[] reach(Entity entity);

    /** Runs the structure's own entity collision (per piece, see {@link IplContraptionCollision}). */
    default void collideEntities(Entity entity) {}

    /** Moves the block driving the structure by {@code (dx, 0, dz)} with its plot; whether one was moved. */
    default boolean shiftController(Entity entity, int dx, int dz) {
        return false;
    }

    /** A structure moved into another level is driven by its controller again; whether one drives it now. */
    default boolean attachController(Entity entity) {
        return false;
    }
}
