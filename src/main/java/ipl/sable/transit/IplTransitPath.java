package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.transit.IplStraddlePoseMap.StraddleMapping;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** A body-independent portal path. Piece numbers belong to bodies, not to the group. */
final class IplTransitPath {
    private final List<Portal> portals;
    private final ServerLevel destination;
    private final StraddleMapping mapping;
    private final String orderKey;
    private final String parentKey;

    IplTransitPath(List<Portal> portals, ServerLevel destination, StraddleMapping mapping) {
        this.portals = List.copyOf(portals);
        this.destination = destination;
        this.mapping = mapping;
        orderKey = portals.stream().map(p -> p.getUUID().toString()).collect(Collectors.joining(">"));
        int cut = orderKey.lastIndexOf('>');
        parentKey = cut < 0 ? "" : orderKey.substring(0, cut);
    }

    List<Portal> portals() { return portals; }
    ServerLevel destination() { return destination; }
    StraddleMapping mapping() { return mapping; }
    String orderKey() { return orderKey; }

    int depth() { return portals.size(); }

    String parentKey() { return parentKey; }

    boolean canOpenFor(ServerSubLevel ship) {
        if (ship.isRemoved()) return false;
        ServerLevel level = ship.getLevel();
        for (Portal portal : portals) {
            if (!IplAtlasStraddleSession.supports(level, portal)
                || IplShipPortalAnchor.isAnchorShip(portal, ship.getUniqueId())) return false;
            level = level.getServer().getLevel(portal.getDestDim());
        }
        return level == destination;
    }

    @Nullable
    static IplTransitPath from(ServerSubLevel ship, IplPieceTracker.Body body, int node) {
        List<Portal> portals = new ArrayList<>();
        int at = node;
        for (int steps = 0; at != IplPieceMap.NATIVE; steps++) {
            if (steps >= IplPieceMap.MAX_PIECE || at < 0 || at > IplPieceMap.MAX_PIECE
                || body.keys[at] == null) return null;
            Portal portal = IplAtlasStraddleSession.sessionPortal(body.keys[at]);
            if (portal == null) return null;
            portals.add(portal);
            at = body.parent[at];
        }
        Collections.reverse(portals);
        ServerLevel level = ship.getLevel();
        StraddleMapping mapping = StraddleMapping.identity();
        for (Portal portal : portals) {
            if (!IplAtlasStraddleSession.supports(level, portal)) return null;
            mapping = mapping.then(StraddleMapping.of(portal));
            level = level.getServer().getLevel(portal.getDestDim());
        }
        return new IplTransitPath(portals, level, mapping);
    }
}
