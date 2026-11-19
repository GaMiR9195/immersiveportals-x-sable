package qouteall.imm_ptl.core.render;

import ipl.sable.render.IplForeignPerspective;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.Arrays;

/**
 * Sections that the opening of a flat portal passes through on its destination side.
 *
 * <p>Every line of sight of a portal pass enters the destination world through the opening. A
 * section graph search seeded with these sections, instead of the camera's own section (which lies
 * behind the destination plane), therefore reaches everything the opening can show while blocks
 * behind the destination plane cannot occlude it. With such seeds occlusion culling is as exact in
 * a portal pass as in the player's own view.
 */
public final class PortalApertureSections {
	/** Larger openings (world-wrapping portals) keep the camera-based search. */
	private static final int MAX_SEARCHED_SECTIONS = 4096;
	private static final double MARGIN = 0.5;

	private PortalApertureSections() {}

	/**
	 * Whether the Sodium section search of the current portal pass starts at the opening. Then no
	 * line of sight of the pass runs through sections fully behind the destination plane.
	 */
	public static boolean seedsCurrentPass() {
		return PortalRendering.isRendering()
			&& !IplForeignPerspective.isRendering()
			&& SodiumInterface.invoker.isSodiumPresent()
			&& isSupported(PortalRendering.getRenderingPortal());
	}

	public static boolean isSupported(Portal portal) {
		if (!PortalScreenCulling.isFlat(portal)) return false;
		int[] min = new int[3];
		int[] max = new int[3];
		sectionBounds(destinationCorners(portal), min, max);
		return volume(min, max) <= MAX_SEARCHED_SECTIONS && isInsideWorld(portal, min, max);
	}

	/** Packed section positions ({@link SectionPos#asLong}) the opening touches, or null if unsupported. */
	public static long @Nullable [] collect(Portal portal) {
		if (!PortalScreenCulling.isFlat(portal)) return null;
		Vec3[] corners = destinationCorners(portal);
		int[] min = new int[3];
		int[] max = new int[3];
		sectionBounds(corners, min, max);
		long volume = volume(min, max);
		if (volume > MAX_SEARCHED_SECTIONS || !isInsideWorld(portal, min, max)) return null;

		Vec3 origin = corners[0];
		Vec3 normal = corners[1].subtract(origin).cross(corners[3].subtract(origin));
		double length = normal.length();
		if (length < 1e-9) return null;
		normal = normal.scale(1.0 / length);
		// largest distance of a section's corner from its center along the normal
		double reach = 8 * (Math.abs(normal.x) + Math.abs(normal.y) + Math.abs(normal.z)) + MARGIN;

		long[] found = new long[(int) volume];
		int count = 0;
		for (int x = min[0]; x <= max[0]; x++) {
			for (int y = min[1]; y <= max[1]; y++) {
				for (int z = min[2]; z <= max[2]; z++) {
					double dx = x * 16 + 8 - origin.x;
					double dy = y * 16 + 8 - origin.y;
					double dz = z * 16 + 8 - origin.z;
					if (Math.abs(dx * normal.x + dy * normal.y + dz * normal.z) <= reach) {
						found[count++] = SectionPos.asLong(x, y, z);
					}
				}
			}
		}
		return Arrays.copyOf(found, count);
	}

	private static Vec3[] destinationCorners(Portal portal) {
		Vec3 o = portal.getOriginPos();
		Vec3 aw = portal.getAxisW().scale(portal.getWidth() * 0.5);
		Vec3 ah = portal.getAxisH().scale(portal.getHeight() * 0.5);
		return new Vec3[]{
			portal.transformPoint(o.subtract(aw).subtract(ah)),
			portal.transformPoint(o.add(aw).subtract(ah)),
			portal.transformPoint(o.add(aw).add(ah)),
			portal.transformPoint(o.subtract(aw).add(ah)),
		};
	}

	/**
	 * Sections exist only inside the build height: an opening reaching above or below it would
	 * seed nothing there, so such portals keep the camera-based search.
	 */
	private static boolean isInsideWorld(Portal portal, int[] min, int[] max) {
		Level level = portal.getDestinationWorld();
		return level != null && min[1] >= level.getMinSection() && max[1] < level.getMaxSection();
	}

	private static long volume(int[] min, int[] max) {
		return (long) (max[0] - min[0] + 1) * (max[1] - min[1] + 1) * (max[2] - min[2] + 1);
	}

	private static void sectionBounds(Vec3[] corners, int[] min, int[] max) {
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (Vec3 c : corners) {
			minX = Math.min(minX, c.x);
			minY = Math.min(minY, c.y);
			minZ = Math.min(minZ, c.z);
			maxX = Math.max(maxX, c.x);
			maxY = Math.max(maxY, c.y);
			maxZ = Math.max(maxZ, c.z);
		}
		min[0] = SectionPos.blockToSectionCoord(minX - MARGIN);
		min[1] = SectionPos.blockToSectionCoord(minY - MARGIN);
		min[2] = SectionPos.blockToSectionCoord(minZ - MARGIN);
		max[0] = SectionPos.blockToSectionCoord(maxX + MARGIN);
		max[1] = SectionPos.blockToSectionCoord(maxY + MARGIN);
		max[2] = SectionPos.blockToSectionCoord(maxZ + MARGIN);
	}
}
