package foundry.veil.mixin.shader_recompile.accessor;

import com.mojang.blaze3d.shaders.Program;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Program.class)
public interface ShaderRecompileProgramAccessor {

    @Accessor
    int getId();

    @Accessor
    void setId(int id);
}
