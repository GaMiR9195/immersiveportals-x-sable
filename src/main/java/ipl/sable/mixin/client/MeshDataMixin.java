package ipl.sable.mixin.client;

import com.mojang.blaze3d.vertex.MeshData;
import ipl.sable.render.IplSectionRanges;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries a section mesh's per-block ranges from the compiler to its buffer. */
@Mixin(MeshData.class)
public abstract class MeshDataMixin implements IplSectionRanges.Holder {

    @Unique
    @Nullable
    private IplSectionRanges.Ranges ipl$ranges;

    @Override
    public IplSectionRanges.@Nullable Ranges ipl$ranges() {
        return ipl$ranges;
    }

    @Override
    public void ipl$setRanges(IplSectionRanges.@Nullable Ranges ranges) {
        this.ipl$ranges = ranges;
    }
}
