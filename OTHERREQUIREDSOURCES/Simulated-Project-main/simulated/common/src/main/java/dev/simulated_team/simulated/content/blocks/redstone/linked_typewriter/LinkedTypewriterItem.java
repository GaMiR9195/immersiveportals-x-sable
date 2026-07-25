package dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter;

import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import com.simibubi.create.foundation.utility.RaycastHelper;
import dev.simulated_team.simulated.content.blocks.redstone.AbstractLinkedReceiverBlockEntity;
import dev.simulated_team.simulated.mixin.accessor.RedstoneLinkBlockEntityAccessor;
import net.createmod.catnip.data.Couple;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

public class LinkedTypewriterItem extends BlockItem {
    public LinkedTypewriterItem(final Block block, final Properties properties) {
        super(block, properties);
    }

    //rclick redstone link -> prompt bind
    @Override
    public InteractionResult useOn(final UseOnContext context) {
        final Level level = context.getLevel();

        final BlockPos clickedPos = context.getClickedPos();

        Couple<RedstoneLinkNetworkHandler.Frequency> frequency = null;
        final BlockEntity be = level.getBlockEntity(clickedPos);
        if (be instanceof final AbstractLinkedReceiverBlockEntity abe) {
            frequency = abe.getFrequency();
        } else if (be instanceof final RedstoneLinkBlockEntity lbe) {
            frequency = ((RedstoneLinkBlockEntityAccessor) lbe).getLink().getNetworkKey();
        }

        //should never be null here, but whatever
        if (frequency != null) {
            if (!level.isClientSide) {
                return InteractionResult.CONSUME;
            }

            //reset if we click on another link
            if (LinkedTypewriterInteractionHandler.getMode() == LinkedTypewriterInteractionHandler.Mode.BINDING_FROM_ITEM) {
                LinkedTypewriterItemBindHandler.reset();
                return InteractionResult.CONSUME;
            }

            //the rest of this needs to be handled on the client...
            LinkedTypewriterInteractionHandler.setMode(LinkedTypewriterInteractionHandler.Mode.BINDING_FROM_ITEM);
            LinkedTypewriterItemBindHandler.setClickedPos(clickedPos);
            return InteractionResult.SUCCESS;
        } else if (level.isClientSide) {
            LinkedTypewriterItemBindHandler.reset();
        }

        return super.useOn(context);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(final Level level, final Player player, final InteractionHand usedHand) {
        final BlockHitResult blockHitResult = RaycastHelper.rayTraceRange(level, player, player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE));
        if (blockHitResult.getType() == HitResult.Type.MISS && level.isClientSide) {
            LinkedTypewriterItemBindHandler.reset();
        }

        return super.use(level, player, usedHand);
    }

    @Override
    public void appendHoverText(final ItemStack stack, final TooltipContext context, final List<Component> tooltipComponents, final TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        if (stack.has(DataComponents.BLOCK_ENTITY_DATA)) {
            final CompoundTag tag = stack.get(DataComponents.BLOCK_ENTITY_DATA).copyTag();
            if (tag.contains("Keys", CompoundTag.TAG_LIST)) {
                final int keyCount = tag.getList("Keys", CompoundTag.TAG_COMPOUND).size();
                tooltipComponents.add(Component.translatable("simulated.linked_typewriter.key_count", keyCount).withStyle(ChatFormatting.GOLD));
            }
        }
    }
}
