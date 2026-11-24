package ipl.sable.transit;

import ipl.sable.compat.IplCreateContraptions;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Kinematic structures as rigid geometry of the bodies they are mounted on, for every registered
 * {@link IplContraptionKind}; inert while none is registered (Create's registers itself).
 */
public final class IplContraptions {

    public static final boolean CREATE = ModList.get() != null && ModList.get().isLoaded("create");

    /** Copy-on-write: read for every rendered entity, written once per kind at startup. */
    private static volatile IplContraptionKind[] kinds = new IplContraptionKind[0];

    static {
        if (CREATE) register(IplCreateContraptions.KIND);
    }

    private IplContraptions() {}

    public static synchronized void register(IplContraptionKind kind) {
        IplContraptionKind[] grown = Arrays.copyOf(kinds, kinds.length + 1);
        grown[kinds.length] = kind;
        kinds = grown;
    }

    /** Whether any kind is registered: without one, no body carries structures. */
    public static boolean any() {
        return kinds.length > 0;
    }

    @Nullable
    private static IplContraptionKind kindOf(Entity entity) {
        for (IplContraptionKind kind : kinds) if (kind.owns(entity)) return kind;
        return null;
    }

    public static boolean isContraption(Entity entity) {
        return kindOf(entity) != null;
    }

    /** See {@link IplContraptionKind#isHollow}. */
    public static boolean isHollow(Entity entity) {
        IplContraptionKind kind = kindOf(entity);
        return kind != null && kind.isHollow(entity);
    }

    /** See {@link IplContraptionKind#shape}. */
    @Nullable
    public static IplContraptionShape shape(Entity entity, float partialTick) {
        IplContraptionKind kind = kindOf(entity);
        return kind == null ? null : kind.shape(entity, partialTick);
    }

    /** See {@link IplContraptionKind#reach}. */
    @Nullable
    public static double[] reach(Entity entity) {
        IplContraptionKind kind = kindOf(entity);
        return kind == null ? null : kind.reach(entity);
    }

    /** See {@link IplContraptionKind#collideEntities}. */
    public static void collideEntities(Entity entity) {
        IplContraptionKind kind = kindOf(entity);
        if (kind != null) kind.collideEntities(entity);
    }

    /** See {@link IplContraptionKind#shiftController}. */
    public static boolean shiftController(Entity entity, int dx, int dz) {
        IplContraptionKind kind = kindOf(entity);
        return kind != null && kind.shiftController(entity, dx, dz);
    }

    /** See {@link IplContraptionKind#attachController}. */
    public static boolean attachController(Entity entity) {
        IplContraptionKind kind = kindOf(entity);
        return kind != null && kind.attachController(entity);
    }
}
