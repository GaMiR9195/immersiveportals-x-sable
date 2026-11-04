package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fixture: projection trees of ROOT sessions only (straight production prisms, no chains).
 * A body without {@link #install} has no sessions (empty tree), so every piece-scoped detector
 * query sees all blocks -- the single-portal behaviour most fixtures cover.
 */
public final class IplProjectionTree {
    private static final double MAX_DEPTH = 64.0;
    private static final IplProjectionTree EMPTY = new IplProjectionTree(List.of());
    private static final Map<UUID, IplProjectionTree> TREES = new HashMap<>();
    private static long generation;

    private final List<Node> roots;

    private IplProjectionTree(List<Node> roots) {
        this.roots = roots;
    }

    public static final class Node {
        public String path = "";
        public Portal portal;
        public Node parent;
        public IplStraddlePoseMap.StraddleMapping mapping;
        public PortalPrism prism;
        public Node root() { return parent == null ? this : parent.root(); }
    }

    /** Root sessions of {@code body} through {@code portals}, in sibling priority order; none = no sessions. */
    public static void install(ServerSubLevel body, Portal... portals) {
        List<Node> roots = new ArrayList<>(portals.length);
        for (Portal portal : portals) {
            Node node = new Node();
            node.path = portal.getUUID().toString();
            node.portal = portal;
            node.mapping = IplStraddlePoseMap.StraddleMapping.of(portal);
            node.prism = PortalPrism.straight(portal, MAX_DEPTH);
            roots.add(node);
        }
        TREES.put(body.getUniqueId(), new IplProjectionTree(List.copyOf(roots)));
        generation++;
    }

    public static IplProjectionTree of(ServerSubLevel body) { return TREES.getOrDefault(body.getUniqueId(), EMPTY); }
    public static long generation() { return generation; }
    public boolean isEmpty() { return roots.isEmpty(); }
    public List<Node> roots() { return roots; }
    public List<Node> nodes() { return roots; }

    /** Native piece (null) or the first root whose prism holds the point, as in production. */
    public Node pieceAt(Vec3 realWorld) {
        for (Node root : roots) if (root.prism.contains(realWorld)) return root;
        return null;
    }
}
