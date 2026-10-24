package ipl.sable.mixin.client;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.neoforge.compatibility.flywheel.FlywheelCompatNeoForge;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
/** Global plot IDs belong to storage; a parent frame must not evict their render records. */
@Pseudo
@Mixin(value = FlywheelCompatNeoForge.class, remap = false)
public abstract class IplHostedFlywheelPoseCacheMixin {
    @WrapOperation(method = "preVisualizationFrame", at = @At(value = "INVOKE",
        target = "Ldev/ryanhcode/sable/api/sublevel/ClientSubLevelContainer;getSubLevel(II)Ldev/ryanhcode/sable/sublevel/SubLevel;"), require = 1)
    private static dev.ryanhcode.sable.sublevel.SubLevel ipl$plotOwner(ClientSubLevelContainer container,
        int x, int z, Operation<dev.ryanhcode.sable.sublevel.SubLevel> original) {
        var sub = original.call(container, x, z);
        if (sub != null) return sub;
        var hosting = IplDimAgnostic.getHostingContainerFor(container.getLevel());
        return hosting == null || hosting == container ? null : hosting.getSubLevel(x, z);
    }
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "updateEntry", at = @At("HEAD"), argsOnly = true, require = 1)
    private static ClientSubLevelContainer ipl$lightingOwner(ClientSubLevelContainer container,
        @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) dev.ryanhcode.sable.sublevel.ClientSubLevel sub) {
        return IplDimAgnostic.isHosted(sub) ? SubLevelContainer.getContainer(sub.getLevel()) : container;
    }
}

