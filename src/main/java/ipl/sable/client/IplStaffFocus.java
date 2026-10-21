package ipl.sable.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Actual third-person barrel origin, captured while the held model is rendered. */
public final class IplStaffFocus {
    private record Sample(Player owner, ItemStack stack, Vec3 point, int frame) {}
    private static final Map<UUID, Sample> THIRD_PERSON = new HashMap<>();
    private IplStaffFocus() {}

    public static void capture(ItemStack stack, Vec3 worldPoint) {
        // Portal projections have their own matrices, not the physical owner's frame.
        if (PortalRendering.isRendering() || WorldRenderInfo.isRendering()) return;
        var client = Minecraft.getInstance();
        if (client.level == null) return;
        for (Player player : client.level.players()) {
            if (player.getMainHandItem() == stack || player.getOffhandItem() == stack) {
                THIRD_PERSON.entrySet().removeIf(e -> e.getValue().frame() != RenderStates.frameIndex);
                THIRD_PERSON.put(player.getUUID(), new Sample(player, stack, worldPoint, RenderStates.frameIndex));
                return;
            }
        }
    }

    /** Exact model-tip sample, or {@code null} until this owner's held model rendered. */
    @Nullable
    public static Vec3 capturedThirdPerson(Player owner, boolean mainHand) {
        Sample sample = THIRD_PERSON.get(owner.getUUID());
        ItemStack held = mainHand ? owner.getMainHandItem() : owner.getOffhandItem();
        if (sample != null && sample.owner() == owner && sample.stack() == held
            && sample.frame() == RenderStates.frameIndex) return sample.point();
        return null;
    }

    public static void clear() { THIRD_PERSON.clear(); }
}
