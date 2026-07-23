package dev.simulated_team.simulated.util.hold_interaction;

import com.simibubi.create.CreateClient;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.simulated_team.simulated.content.blocks.behaviour.HoldTipBehaviour;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders hold tips
 */
public class HoldTipManager {

    public static void tick() {
        final Minecraft mc = Minecraft.getInstance();
        final HitResult target = mc.hitResult;
        if (target == null || !(target instanceof final BlockHitResult result))
            return;

        final ClientLevel world = mc.level;
        final BlockPos pos = result.getBlockPos();

        if (!(world.getBlockEntity(pos) instanceof final SmartBlockEntity sbe))
            return;

        for (final BlockEntityBehaviour blockEntityBehaviour : sbe.getAllBehaviours()) {
            if (!(blockEntityBehaviour instanceof final HoldTipBehaviour behaviour))
                continue;
            final MutableComponent hoverTip = behaviour.getHoverTip(mc.player, pos, sbe.getBlockState());
            if (hoverTip != null) {
                final List<MutableComponent> tip = new ArrayList<>();
                tip.add(Component.literal(""));
                tip.add(hoverTip);
                CreateClient.VALUE_SETTINGS_HANDLER.showHoverTip(tip);
            }
        }
    }
}
