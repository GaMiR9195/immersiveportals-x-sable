package ipl.sable.transit;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import qouteall.imm_ptl.core.commands.PortalCommand;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Atlas M6: {@code /iplsable_portal anchor|unanchor|list} — glue the portal the
 * player is looking at to the sub-level under its origin (and release it).
 * See {@link IplShipPortalAnchor}.
 */
public final class IplShipPortalCommand {

    private IplShipPortalCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("iplsable_portal")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("anchor").executes(context -> {
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
            .then(Commands.literal("unanchor").executes(context -> {
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
                    () -> Component.literal(IplShipPortalAnchor.count() + " anchored portal(s)"),
                false);
                return 1;
            }))
            .then(Commands.literal("volumetricenteringdetectionvisualization")
                .then(Commands.literal("on").executes(context -> setVolumeMode(context.getSource(),
                    IplEnteringVolumeVisualization.Mode.ON)))
                .then(Commands.literal("off").executes(context -> setVolumeMode(context.getSource(),
                    IplEnteringVolumeVisualization.Mode.OFF)))));
    }

    private static int setVolumeMode(CommandSourceStack source, IplEnteringVolumeVisualization.Mode mode) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("this command requires a player"));
            return 0;
        }
        if (!IplEnteringVolumeVisualization.setMode(source.getServer(), player, mode)) {
            source.sendFailure(Component.literal("look directly at a sub-level block first"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
            "volumetric entering detection visualization: " + mode.name().toLowerCase()
                + " (swept volume: blue=no hit, green=entry, yellow=exit; aperture: green=source, yellow=destination)"), false);
        return 1;
    }
}
