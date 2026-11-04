package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.client.IplClientProjections;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

/**
 * Create: Pocket Sized. Its client scale handshake sees the sub-levels of every client level
 * IP holds, not only those of {@code Minecraft.level}.
 *
 * <p>Every client tick the handshake asks the server for the scale of each sub-level it sees
 * for the first time and forgets the scale of each one it no longer sees. Looking at the
 * current level only, it forgot a dimension's ships whenever the player left it and asked
 * again on every return. The server answers with {@code snapInterpolation}, and Pocket's
 * {@code ClientSubLevel.tick} then resets the interpolation: logical pose = newest buffered
 * snapshot, buffer cleared. A moving ship jumped ahead by the interpolation delay and stood
 * still until Sable's clock caught up, hitbox included, every time the player entered its
 * dimension. The scale of ships seen through a portal was forgotten as well.
 */
@Pseudo
@Mixin(targets = "com.misterblusky9.pym.internal.client.ScaleHandshake", remap = false)
public abstract class PocketScaleHandshakeMixin {

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;getAllSubLevels()Ljava/util/List;"
        )
    )
    private static List<? extends SubLevel> ipl$subLevelsOfAllClientLevels(
        SubLevelContainer current, Operation<List<? extends SubLevel>> original
    ) {
        List<SubLevel> all = new ArrayList<>(original.call(current));
        for (ClientLevel level : IplClientProjections.clientLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null && container != current) all.addAll(container.getAllSubLevels());
        }
        return all;
    }
}
