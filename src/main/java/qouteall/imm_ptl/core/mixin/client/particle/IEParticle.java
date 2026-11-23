package qouteall.imm_ptl.core.mixin.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Particle.class)
public interface IEParticle {
    @Accessor("level")
    ClientLevel portal_getWorld();

    @Mutable
    @Accessor("level")
    void ip_setWorld(ClientLevel level);

    @Accessor("xo")
    double ip_getXo();

    @Accessor("yo")
    double ip_getYo();

    @Accessor("zo")
    double ip_getZo();

    @Accessor("xo")
    void ip_setXo(double value);

    @Accessor("yo")
    void ip_setYo(double value);

    @Accessor("zo")
    void ip_setZo(double value);

    @Accessor("xd")
    double ip_getXd();

    @Accessor("yd")
    double ip_getYd();

    @Accessor("zd")
    double ip_getZd();
}
