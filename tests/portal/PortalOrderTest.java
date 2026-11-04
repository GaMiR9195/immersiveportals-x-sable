package ipl.sable.transit;

import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

/** Upstream/downstream relation of portals in one frame (sibling priority, phantom guards). */
public final class PortalOrderTest {
    private static final double DEPTH = 64.0;
    private static int failures;

    public static void main(String[] args) {
        // Row "->|  |": both crossed toward +x, the second one 3 blocks further.
        Portal front = portal(0, 0, 0, -1, 2, 3);
        Portal behind = portal(3, 0, 0, -1, 2, 3);
        check("row: behind is downstream", PortalOrder.downstreamOf(behind, straight(front)));
        check("row: front is not downstream", !PortalOrder.downstreamOf(front, straight(behind)));
        check("row: strict order", PortalOrder.strictlyDownstreamOf(behind, straight(behind), front, straight(front)));
        check("row: no reverse order", !PortalOrder.strictlyDownstreamOf(front, straight(front), behind, straight(behind)));

        // Two faces of one aperture, and neighbours in one wall: no order at all.
        Portal twin = portal(0, 0, 0, +1, 2, 3);
        check("twin faces unordered", !PortalOrder.downstreamOf(twin, straight(front))
            && !PortalOrder.downstreamOf(front, straight(twin)));
        Portal neighbour = portal(0, 0, 3, -1, 2, 3);
        check("same wall unordered", !PortalOrder.downstreamOf(neighbour, straight(front))
            && !PortalOrder.downstreamOf(front, straight(neighbour)));

        // Portals facing each other: each lies beyond the other's plane -> no strict order.
        Portal facing = portal(3, 0, 0, +1, 2, 3);
        check("facing: mutual", PortalOrder.downstreamOf(facing, straight(front))
            && PortalOrder.downstreamOf(front, straight(facing)));
        check("facing: no strict order", !PortalOrder.strictlyDownstreamOf(facing, straight(facing), front, straight(front))
            && !PortalOrder.strictlyDownstreamOf(front, straight(front), facing, straight(facing)));

        // Beside the prism nothing is downstream, however deep: there is no infinite plane.
        check("aside: outside the prism", !PortalOrder.downstreamOf(portal(3, 0, 10, -1, 2, 3), straight(front)));
        check("deep aside: still outside", !PortalOrder.downstreamOf(portal(40, 0, 10, -1, 2, 3), straight(front)));

        // Partial overlap of the apertures is enough.
        check("raised: downstream", PortalOrder.downstreamOf(portal(3, 2.5, 0, -1, 2, 3), straight(front)));
        check("off the edge: not downstream", !PortalOrder.downstreamOf(portal(3, 3.5, 0, -1, 2, 3), straight(front)));

        // Slanted passage: the prism follows the anchor's chord, not the normal.
        PortalPrism slanted = PortalPrism.of(front, new Vec3(3, 0, 4.5), DEPTH);
        Portal diagonal = portal(3, 0, 4.5, -1, 2, 3);
        check("diagonal: outside the straight tube", !PortalOrder.downstreamOf(diagonal, straight(front)));
        check("diagonal: inside the slanted prism", PortalOrder.downstreamOf(diagonal, slanted));
        check("slanted: leaves the straight row", !PortalOrder.downstreamOf(portal(6, 0, 0, -1, 2, 3), slanted));
        check("slanted: strict order", PortalOrder.strictlyDownstreamOf(diagonal, straight(diagonal), front, slanted));

        if (failures > 0) {
            System.out.println(failures + " PortalOrder check(s) FAILED");
            System.exit(1);
        }
        System.out.println("PortalOrderTest: all checks passed");
    }

    private static PortalPrism straight(Portal portal) {
        return PortalPrism.straight(portal, DEPTH);
    }

    /** Portal in the plane x = const, crossed toward {@code -nx}, width along z, height along y. */
    private static Portal portal(double x, double y, double z, double nx, double width, double height) {
        Portal p = new Portal();
        p.origin = new Vec3(x, y, z);
        p.normal = new Vec3(nx, 0, 0);
        p.axisW = new Vec3(0, 0, 1);
        p.axisH = new Vec3(0, 1, 0);
        p.width = width;
        p.height = height;
        return p;
    }

    private static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name);
        }
    }
}
