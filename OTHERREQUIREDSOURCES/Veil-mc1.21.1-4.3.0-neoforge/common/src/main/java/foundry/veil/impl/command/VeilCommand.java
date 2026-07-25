package foundry.veil.impl.command;

import com.google.common.collect.Iterables;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import foundry.veil.Veil;
import foundry.veil.api.network.VeilPacketManager;
import foundry.veil.ext.VeilClientSuggestionProvider;
import foundry.veil.impl.network.ClientboundAddPostProcessingPacket;
import foundry.veil.impl.network.ClientboundClearPostProcessingPacket;
import foundry.veil.impl.network.ClientboundRemovePostProcessingPacket;
import foundry.veil.platform.VeilPlatform;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@ApiStatus.Internal
public final class VeilCommand {

    private static final SuggestionProvider<CommandSourceStack> PIPELINE_SUGGESTIONS = SuggestionProviders.register(Veil.veilPath("post_pipelines"),
            (context, builder) -> context.getSource() instanceof VeilClientSuggestionProvider provider ? SharedSuggestionProvider.suggestResource(provider.veil$getPostPipelineNames(), builder) : builder.buildFuture());

    private VeilCommand() {
    }

    private static final Component POST_PROCESSING_ADD_FAIL = Component.translatable("commands." + Veil.MODID + ".post_processing.add.fail");
    private static final Component POST_PROCESSING_REMOVE_FAIL = Component.translatable("commands." + Veil.MODID + ".post_processing.remove.fail");
    private static final Component POST_PROCESSING_CLEAR_FAIL = Component.translatable("commands." + Veil.MODID + ".post_processing.clear.fail");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("veil").
                requires(stack -> stack.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("post_processing")
                        .then(Commands.literal("add").then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("pipeline", ResourceLocationArgument.id()).suggests(PIPELINE_SUGGESTIONS)
                                        .executes(ctx -> {
                                            Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
                                            ResourceLocation pipeline = ResourceLocationArgument.getId(ctx, "pipeline");
                                            return addPipeline(ctx.getSource(), targets, pipeline, 1000);
                                        })
                                        .then(Commands.argument("priority", IntegerArgumentType.integer()).executes(ctx -> {
                                            Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
                                            ResourceLocation pipeline = ResourceLocationArgument.getId(ctx, "pipeline");
                                            int priority = IntegerArgumentType.getInteger(ctx, "priority");
                                            return addPipeline(ctx.getSource(), targets, pipeline, priority);
                                        }))
                                )))
                        .then(Commands.literal("remove").then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("pipeline", ResourceLocationArgument.id()).suggests(PIPELINE_SUGGESTIONS)
                                        .executes(ctx -> {
                                            Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
                                            ResourceLocation pipeline = ResourceLocationArgument.getId(ctx, "pipeline");
                                            return removePipeline(ctx.getSource(), targets, pipeline);
                                        })
                                )))
                        .then(Commands.literal("clear").then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> {
                                    Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
                                    return clearPipelines(ctx.getSource(), targets);
                                })
                        ))
                ));
    }

    private static int addPipeline(CommandSourceStack source, Collection<ServerPlayer> targets, ResourceLocation pipeline, int priority) {
        List<ServerPlayer> sentPlayers = new ArrayList<>();

        VeilPlatform platform = Veil.platform();
        for (ServerPlayer target : targets) {
            if (platform.hasChannel(target.connection, ClientboundAddPostProcessingPacket.TYPE)) {
                sentPlayers.add(target);
                VeilPacketManager.player(target).sendPacket(new ClientboundAddPostProcessingPacket(priority, pipeline));
            }
        }

        if (sentPlayers.isEmpty()) {
            source.sendFailure(POST_PROCESSING_ADD_FAIL);
            return 0;
        }

        if (sentPlayers.size() == 1) {
            source.sendSuccess(() -> Component.translatable("commands." + Veil.MODID + ".post_processing.add.success.single", pipeline.toString(), Iterables.getOnlyElement(sentPlayers).getDisplayName()), true);
        } else {
            source.sendSuccess(() -> Component.translatable("commands." + Veil.MODID + ".post_processing.add.success.multiple", pipeline.toString(), sentPlayers.size()), true);
        }
        return sentPlayers.size();
    }

    private static int removePipeline(CommandSourceStack source, Collection<ServerPlayer> targets, ResourceLocation pipeline) {
        List<ServerPlayer> sentPlayers = new ArrayList<>();

        VeilPlatform platform = Veil.platform();
        for (ServerPlayer target : targets) {
            if (platform.hasChannel(target.connection, ClientboundRemovePostProcessingPacket.TYPE)) {
                sentPlayers.add(target);
                VeilPacketManager.player(target).sendPacket(new ClientboundRemovePostProcessingPacket(pipeline));
            }
        }

        if (sentPlayers.isEmpty()) {
            source.sendFailure(POST_PROCESSING_REMOVE_FAIL);
            return 0;
        }

        if (sentPlayers.size() == 1) {
            source.sendSuccess(() -> Component.translatable("commands." + Veil.MODID + ".post_processing.remove.success.single", pipeline.toString(), Iterables.getOnlyElement(sentPlayers).getDisplayName()), true);
        } else {
            source.sendSuccess(() -> Component.translatable("commands." + Veil.MODID + ".post_processing.remove.success.multiple", pipeline.toString(), sentPlayers.size()), true);
        }
        return sentPlayers.size();
    }

    private static int clearPipelines(CommandSourceStack source, Collection<ServerPlayer> targets) {
        List<ServerPlayer> sentPlayers = new ArrayList<>();

        VeilPlatform platform = Veil.platform();
        for (ServerPlayer target : targets) {
            if (platform.hasChannel(target.connection, ClientboundClearPostProcessingPacket.TYPE)) {
                sentPlayers.add(target);
                VeilPacketManager.player(target).sendPacket(ClientboundClearPostProcessingPacket.INSTANCE);
            }
        }

        if (sentPlayers.isEmpty()) {
            source.sendFailure(POST_PROCESSING_CLEAR_FAIL);
            return 0;
        }

        if (sentPlayers.size() == 1) {
            source.sendSuccess(() -> Component.translatable("commands." + Veil.MODID + ".post_processing.clear.success.single", Iterables.getOnlyElement(sentPlayers).getDisplayName()), true);
        } else {
            source.sendSuccess(() -> Component.translatable("commands." + Veil.MODID + ".post_processing.clear.success.multiple", sentPlayers.size()), true);
        }
        return sentPlayers.size();
    }
}
