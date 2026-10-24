package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.SableSubLevelDimension;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lock state stays keyed by its real physics owner; its display is a parent view. */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler", remap = false)
public abstract class IplHostedStaffLocksMixin {
    @Shadow @Final private Map<ResourceKey<Level>, List<UUID>> locks;

    @ModifyReturnValue(method = "getLocks", at = @At("RETURN"), require = 1)
    private List<UUID> ipl$visibleLocks(List<UUID> original, Level view) {
        if (IplDimAgnostic.isHostingLevel(view)) return original == null ? List.of() : original;
        List<UUID> hostedLocks = locks.get(SableSubLevelDimension.SUBLEVELS);
        if (hostedLocks == null || hostedLocks.isEmpty()) return original == null ? List.of() : original;
        SubLevelContainer hosting = IplDimAgnostic.getHostingContainerFor(view);
        if (hosting == null) return original == null ? List.of() : original;
        List<UUID> out = original == null ? new ArrayList<>() : new ArrayList<>(original);
        for (UUID id : hostedLocks) {
            var sub = hosting.getSubLevel(id);
            if (sub == null || sub.isRemoved() || IplDimAgnostic.getParentLevel(sub) != view) continue;
            if (!out.contains(id)) out.add(id);
        }
        return out;
    }
}