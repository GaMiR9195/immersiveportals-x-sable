package ipl.sable.transit;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.List;

/**
 * Fixture: a body with NO straddle sessions (empty projection tree), so every piece-scoped
 * detector query sees all blocks -- the single-portal behaviour these fixtures cover.
 */
public final class IplProjectionTree {
    public static final double SKIN = 1.0e-3;
    private static final IplProjectionTree EMPTY = new IplProjectionTree();

    public static final class Node {
        public String path = "";
        public Portal portal;
        public Node parent;
        public IplStraddlePoseMap.StraddleMapping mapping;
        double halfW;
        double halfH;
        public Node root() { return parent == null ? this : parent.root(); }
        public boolean beyondContains(Vec3 w) { return false; }
    }

    public static IplProjectionTree of(ServerSubLevel body) { return EMPTY; }
    public static long generation() { return 0L; }
    public boolean isEmpty() { return true; }
    public List<Node> roots() { return List.of(); }
    public List<Node> nodes() { return List.of(); }
    public Node pieceAt(Vec3 realWorld) { return null; }
}
