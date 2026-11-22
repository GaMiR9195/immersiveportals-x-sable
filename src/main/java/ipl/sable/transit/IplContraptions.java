package ipl.sable.transit;

import ipl.sable.compat.IplCreateContraptions;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/** Create contraptions as rigid geometry of the bodies they are mounted on; inert without Create. */
public final class IplContraptions {

    public static final boolean CREATE = ModList.get() != null && ModList.get().isLoaded("create");

    private IplContraptions() {}

    public static boolean isContraption(Entity entity) {
        return CREATE && IplCreateContraptions.isContraption(entity);
    }

    /** A contraption entity whose structure never arrived (Create then has nothing to draw). */
    public static boolean isHollow(Entity entity) {
        return CREATE && IplCreateContraptions.isHollow(entity);
    }

    /** See {@link IplCreateContraptions#shape}. */
    @Nullable
    public static IplContraptionShape shape(Entity entity, float partialTick) {
        return CREATE ? IplCreateContraptions.shape(entity, partialTick) : null;
    }

    /** Runs Create's entity collision of {@code contraption} (per piece, see {@link IplContraptionCollision}). */
    public static void collideEntities(Entity contraption) {
        if (CREATE) IplCreateContraptions.collideEntities(contraption);
    }

    /** See {@link IplCreateContraptions#followPlot}; whether a controller drives it now. */
    public static boolean followPlot(Entity entity, int dx, int dz) {
        return CREATE && IplCreateContraptions.followPlot(entity, dx, dz);
    }
}
