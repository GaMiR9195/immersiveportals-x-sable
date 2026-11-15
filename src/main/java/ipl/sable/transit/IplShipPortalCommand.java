package ipl.sable.transit;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import qouteall.imm_ptl.core.commands.PortalCommand;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Atlas M6: {@code /ipxsable link_portal_to_nearest_sublevel true|false|list} — glue the
 * portal the player is looking at to the sub-level under its origin ({@code true}), release it
 * ({@code false}) or count the linked portals ({@code list}). {@code /ipxsable} is the root of the
 * mod's commands.
 * See {@link IplShipPortalAnchor}.
 */
public final class IplShipPortalCommand {

    private IplShipPortalCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ipxsable")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("link_portal_to_nearest_sublevel")
            .then(Commands.literal("true").executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                Portal portal = PortalCommand.getPlayerPointingPortal(player, false);
                if (portal == null) {
                    context.getSource().sendFailure(Component.literal("no portal targeted"));
                    return 0;
                }
                String result = IplShipPortalAnchor.anchor(portal);
                context.getSource().sendSuccess(() -> Component.literal(result), false);
                return 1;
            }))
            .then(Commands.literal("false").executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                Portal portal = PortalCommand.getPlayerPointingPortal(player, false);
                if (portal == null) {
                    context.getSource().sendFailure(Component.literal("no portal targeted"));
                    return 0;
                }
                String result = IplShipPortalAnchor.unanchor(portal);
                context.getSource().sendSuccess(() -> Component.literal(result), false);
                return 1;
            }))
            .then(Commands.literal("list").executes(context -> {
                context.getSource().sendSuccess(
                    () -> Component.literal(IplShipPortalAnchor.count() + " portal(s) linked to a sub-level"),
                false);
                return 1;
            }))));
    }
}
