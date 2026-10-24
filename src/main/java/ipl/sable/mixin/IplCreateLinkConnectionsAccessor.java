package ipl.sable.mixin;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import net.createmod.catnip.data.Couple;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;
import java.util.Set;
@Pseudo
@Mixin(value = RedstoneLinkNetworkHandler.class, remap = false)
public interface IplCreateLinkConnectionsAccessor {
    @Accessor("connections")
    static Map<LevelAccessor,Map<Couple<RedstoneLinkNetworkHandler.Frequency>,Set<IRedstoneLinkable>>> ipl$connections() {
        throw new AssertionError("mixin not applied");
    }
}
