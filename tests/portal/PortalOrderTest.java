package ipl.sable.transit;

import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

/** Upstream/downstream relation of portals in one frame (sibling priority, phantom guards). */
public final class PortalOrderTest {
    private static int failures;

    public static void main(String[] args) {
        // Row "->|  |": both crossed toward +x, the second one 3 blocks further.
        Portal front = portal(0, 0, 0, -1, 2, 3);
        Portal behind = portal(3, 0, 0, -1, 2, 3);
        check("row: behind is downstream", PortalOrder.downstreamOf(behind, front, false));
        check("row: front is not downstream", !PortalOrder.downstreamOf(front, behind, false));
        check("row: strict order", PortalOrder.strictlyDownstreamOf(behind, false, front, false));
        check("row: behind covered by front", PortalOrder.coveredBy(behind, false, front, false));
        check("row: front not covered", !PortalOrder.coveredBy(front, false, behind, false));
        check("row: wide behind not inside a tube", !PortalOrder.coveredBy(behind, true, front, false));
        check("row: wide front covers", PortalOrder.coveredBy(behind, true, front, true));

        // Two faces of one aperture, and neighbours in one wall: no order at all.
        Portal twin = portal(0, 0, 0, +1, 2, 3);
        check("twin faces unordered", !PortalOrder.downstreamOf(twin, front, false)
            && !PortalOrder.downstreamOf(front, twin, false));
        Portal neighbour = portal(0, 0, 3, -1, 2, 3);
        check("same wall unordered", !PortalOrder.downstreamOf(neighbour, front, true)
            && !PortalOrder.downstreamOf(front, neighbour, true));

        // Portals facing each other: each lies beyond the other's plane -> no strict order.
        Portal facing = portal(3, 0, 0, +1, 2, 3);
        check("facing: mutual", PortalOrder.downstreamOf(facing, front, false)
            && PortalOrder.downstreamOf(front, facing, false));
        check("facing: no strict order", !PortalOrder.strictlyDownstreamOf(facing, false, front, false)
            && !PortalOrder.strictlyDownstreamOf(front, false, facing, false));
        check("facing: never covered", !PortalOrder.coveredBy(facing, false, front, false));

        // Lateral reach: far to the side only counts behind a wide (half-space) region.
        Portal aside = portal(3, 0, 10, -1, 2, 3);
        check("aside: outside the tube", !PortalOrder.downstreamOf(aside, front, false));
        check("aside: inside the half-space", PortalOrder.downstreamOf(aside, front, true));

        // Partial overlap: downstream, but its region sticks out of the front tube.
        Portal raised = portal(3, 2.5, 0, -1, 2, 3);
        check("raised: downstream", PortalOrder.downstreamOf(raised, front, false));
        check("raised: not covered", !PortalOrder.coveredBy(raised, false, front, false));
        Portal larger = portal(3, 0, 0, -1, 4, 3);
        check("larger: not covered", !PortalOrder.coveredBy(larger, false, front, false));

        if (failures > 0) {
            System.out.println(failures + " PortalOrder check(s) FAILED");
            System.exit(1);
        }
        System.out.println("PortalOrderTest: all checks passed");
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
