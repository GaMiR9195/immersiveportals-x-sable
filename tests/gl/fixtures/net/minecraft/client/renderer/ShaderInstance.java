package net.minecraft.client.renderer;

import com.mojang.blaze3d.shaders.Uniform;
import ipl.sable.duck.IplSubLevelClipShader;
import qouteall.imm_ptl.core.ducks.IEShader;

public class ShaderInstance implements IplSubLevelClipShader, IEShader {
    public int id;
    public final Uniform[] sub = new Uniform[6];
    public final Uniform portal = new Uniform();

    public ShaderInstance() {
        for (int i = 0; i < sub.length; i++) sub[i] = new Uniform();
    }

    public int getId() { return id; }
    public Uniform ipl$getSubLevelClipUniform(int slot) { return sub[slot]; }
    public Uniform ip_getClippingEquationUniform() { return portal; }
}
