package ipl.sable.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.platform_specific.IPConfig;

/**
 * Client side of {@code /ipxsable}: rendering settings of this client.
 * {@code /ipxsable portal_shadow purple_glass|black|false} chooses what portals cast into shader
 * pack sun shadows. Client commands are parsed before the server's and this root would shadow
 * the server's subcommands, so anything else is forwarded to the server unchanged.
 */
public final class IplClientCommand {
    private IplClientCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> portalShadow = Commands.literal("portal_shadow")
            .executes(context -> report(context.getSource()));
        for (IPGlobal.PortalShadow mode : IPGlobal.PortalShadow.values()) {
            portalShadow.then(Commands.literal(literalOf(mode))
                .executes(context -> setPortalShadow(context.getSource(), mode)));
        }

        dispatcher.register(Commands.literal("ipxsable")
            .then(portalShadow)
            .then(Commands.argument("server_command", StringArgumentType.greedyString())
                .executes(context -> forwardToServer(StringArgumentType.getString(context, "server_command")))));
    }

    private static String literalOf(IPGlobal.PortalShadow mode) {
        return mode == IPGlobal.PortalShadow.none ? "false" : mode.name();
    }

    private static int report(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("portal_shadow: " + literalOf(IPGlobal.portalShadow)), false);
        return 1;
    }

    private static int setPortalShadow(CommandSourceStack source, IPGlobal.PortalShadow mode) {
        IPConfig config = IPConfig.getConfig();
        config.portalShadow = mode;
        config.saveConfigFile();
        IPGlobal.portalShadow = mode;
        return report(source);
    }

    private static int forwardToServer(String arguments) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) return 0;
        // Sent as a packet: ClientPacketListener.sendCommand would run the client dispatcher again.
        connection.send(new ServerboundChatCommandPacket("ipxsable " + arguments));
        return 1;
    }
}
