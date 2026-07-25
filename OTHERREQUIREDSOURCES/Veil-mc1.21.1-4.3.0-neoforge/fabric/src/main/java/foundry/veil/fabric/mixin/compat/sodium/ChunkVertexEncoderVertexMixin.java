package foundry.veil.fabric.mixin.compat.sodium;

import foundry.veil.ext.sodium.ChunkVertexEncoderVertexExtension;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkVertexEncoder.Vertex.class)
public class ChunkVertexEncoderVertexMixin implements ChunkVertexEncoderVertexExtension {

    @Unique
    private int veil$packedNormal;

    @Override
    public int veil$getPackedNormal() {
        return this.veil$packedNormal;
    }

    @Override
    public void veil$setNormal(int packedNormal) {
        this.veil$packedNormal = packedNormal;
    }

    @Inject(method = "copyVertexTo", at = @At("TAIL"))
    private static void copyVertexTo(ChunkVertexEncoder.Vertex from, ChunkVertexEncoder.Vertex _to, CallbackInfo ci) {
        ((ChunkVertexEncoderVertexExtension) _to).veil$setNormal(((ChunkVertexEncoderVertexExtension) from).veil$getPackedNormal());
    }
}
