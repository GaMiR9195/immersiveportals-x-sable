package ipl.sable.mixin.client;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(ClientLevel.class)
public interface IplClientSoundInvoker {
    @Invoker("playSound") void ipl$playSound(double x,double y,double z,SoundEvent sound,SoundSource source,float volume,float pitch,boolean delay,long seed);
}
