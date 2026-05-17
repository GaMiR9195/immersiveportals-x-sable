package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.mixin.iface.IplKinematicSubLevelHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/**
 * Adds the {@link IplKinematicSubLevelHolder} duck-interface field + accessors to every
 * {@code ServerSubLevel} instance, so IPL's mirror code and our physics-system mixins
 * can identify and skip kinematic mirrors.
 *
 * <p>The flag defaults to {@code false}; only mirrors set it to {@code true}, explicitly,
 * after being allocated via {@code SubLevelContainer.allocateSubLevel}.
 */
@Pseudo
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class SableServerSubLevelKinematicMixin implements IplKinematicSubLevelHolder {

    @Unique
    private boolean ipl$isKinematicMirror = false;

    @Override
    public boolean ipl$isKinematicMirror() {
        return this.ipl$isKinematicMirror;
    }

    @Override
    public void ipl$setKinematicMirror(boolean isKinematic) {
        this.ipl$isKinematicMirror = isKinematic;
    }
}
