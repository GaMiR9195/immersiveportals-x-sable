package ipl.sable.mixin;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import com.llamalad7.mixinextras.sugar.Local;
import ipl.sable.dim.IplWorldFrameContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
/** Network identity follows the actual parent; block/entity ownership remains storage. */
@Pseudo
@Mixin(value = RedstoneLinkNetworkHandler.class, remap = false)
public abstract class IplCreateLinkNetworkViewMixin {
    @ModifyVariable(method = {"getNetworkOf", "addToNetwork", "removeFromNetwork", "updateNetworkOf"},
        at = @At("HEAD"), argsOnly = true, require = 4)
    private LevelAccessor ipl$networkWorld(LevelAccessor world, @Local(argsOnly = true) IRedstoneLinkable actor) {
        if (world instanceof ServerLevel level) {
            var parent = IplWorldFrameContext.resolveParentForPlotInteraction(level, actor.getLocation());
            if (parent != null) return parent;
        }
        return world;
    }
}
