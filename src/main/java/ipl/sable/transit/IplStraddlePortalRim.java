package ipl.sable.transit;

import dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderBakery;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderData;
import ipl.sable.mixin.IplRapier3DInvoker;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Portal containment rim: a world-anchored kinematic-contraption body shaped as a
 * hollow rectangle in the portal plane — solid everywhere on the plane EXCEPT the
 * aperture. IP portals have no frame blocks (arbitrary placement), so without this the
 * plane-minus-aperture is vacuum: ships could cross the plane beside the opening or
 * shear laterally into the aperture column without ever starting a proper crossing.
 * Rims are ALWAYS-ON per portal entity (see {@link IplPortalRimManager}) — containment
 * must exist before any straddle session does.
 *
 * <p>Built entirely on stock Sable natives (the Create-contraption path). The trick
 * for thinness: contraption voxels carry BAKED BLOCK SHAPES, not just full cubes — the
 * rim's voxels are carpet (1/16-thick collision box, the same machinery that lets
 * Chisels-and-Bits-sized geometry collide properly), and the contraption's local frame
 * is oriented with local +Y along the portal normal, so the thin axis lies across the
 * plane: ±1/32 of a block around it. Arbitrary portal rotation, real solver collision
 * with every ship. Entities never touch Rapier and pass through freely.
 *
 * <p>Aperture exactness: voxel holes are whole blocks but apertures can be fractional
 * (a 45° portal's width is k·√2). For fractional apertures TWO overlapping rims are
 * spawned, holes anchored to the aperture's MIN and MAX corners respectively: each
 * body is slack on one side only, and the UNION of the two solids bounds the exact
 * aperture rectangle on every edge. Integer apertures need just one body.
 */
public final class IplStraddlePortalRim {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-portal-rim");

    private IplStraddlePortalRim() {}

    /** Immutable spawn geometry, reused for move updates. */
    public record RimGeometry(
        Vec3 planePoint, Vec3 normal, Vec3 axisW, Vec3 axisH, double halfW, double halfH
    ) {}

    /**
     * Spawn the rim bodies in {@code scene}; returns spawned ids (possibly empty).
     * {@code outerMargin} is how far (blocks) the frame extends beyond the aperture.
     */
    public static int[] spawn(
        long scene, RapierVoxelColliderBakery bakery, RimGeometry g, int outerMargin
    ) {
        int holeW = holeW(g);
        int holeH = holeH(g);
        boolean fractional = holeW - 2.0 * g.halfW() > 1.0e-6 || holeH - 2.0 * g.halfH() > 1.0e-6;

        int idA = spawnOne(scene, bakery, g, true, outerMargin);
        if (!fractional || idA < 0) {
            return idA < 0 ? new int[0] : new int[]{idA};
        }
        int idB = spawnOne(scene, bakery, g, false, outerMargin);
        return idB < 0 ? new int[]{idA} : new int[]{idA, idB};
    }

    /** Re-anchor already-spawned rims to a portal's new geometry (portal moved). */
    public static void updateTransforms(long scene, int[] ids, RimGeometry g) {
        for (int i = 0; i < ids.length; i++) {
            applyTransform(scene, ids[i], g, i == 0);
        }
    }

    /** Remove a rim body; safe on ids the scene no longer knows. */
    public static void remove(long scene, int id) {
        if (id < 0 || scene == 0) return;
        try {
            IplRapier3DInvoker.ipl$removeKinematicContraption(scene, id);
        } catch (Throwable t) {
            LOG.error("[IPL-RIM] remove failed for id={}", id, t);
        }
    }

    private static int holeW(RimGeometry g) {
        return Math.max(1, (int) Math.ceil(2.0 * g.halfW() - 1.0e-6));
    }

    private static int holeH(RimGeometry g) {
        return Math.max(1, (int) Math.ceil(2.0 * g.halfH() - 1.0e-6));
    }

    private static int spawnOne(
        long scene, RapierVoxelColliderBakery bakery, RimGeometry g,
        boolean minAnchored, int outerMargin
    ) {
        try {
            int holeW = holeW(g);
            int holeH = holeH(g);
            int r = Math.max(1, outerMargin);

            // Carpet (1/16-thick collision box), packed exactly like the stock
            // contraption feed packs any block (CORNER neighborhood; bakery handle + 1).
            // The thin axis is local Y = the portal normal, so the rim protrudes just
            // 1/32 of a block on each side of the plane. If fast ships ever tunnel
            // through, thicken here: trapdoor = 3/16, slab = 1/2.
            RapierVoxelColliderData plate = bakery == null
                ? null : bakery.getPhysicsDataForBlock(Blocks.WHITE_CARPET.defaultBlockState());
            int colliderValue = plate == null ? 0 : plate.handle() + 1;
            int packed = (VoxelNeighborhoodState.CORNER.byteRepresentation() & 0xFF)
                | (colliderValue << 16);

            int id = Rapier3D.nextBodyID();
            IplRapier3DInvoker.ipl$createKinematicContraption(
                scene, -1, id, new double[]{0, 0, 0, 0, 0, 0, 1});

            // Voxel content: single layer y = 0 (the slab plate), frame in local XZ:
            // x ∈ [-r, holeW+r) along axisW, z ∈ [-r, holeH+r) along the height axis,
            // minus the aperture. Stock feed's index packing.
            Long2ObjectMap<int[]> sections = new Long2ObjectOpenHashMap<>();
            for (int x = -r; x < holeW + r; x++) {
                for (int z = -r; z < holeH + r; z++) {
                    if (x >= 0 && x < holeW && z >= 0 && z < holeH) continue; // aperture
                    long sectionKey = SectionPos.asLong(
                        Math.floorDiv(x, 16), 0, Math.floorDiv(z, 16));
                    int[] data = sections.computeIfAbsent(sectionKey, k -> new int[4096]);
                    data[(x & 15) + ((z & 15) << 4)] = packed; // y = 0
                }
            }
            for (Long2ObjectMap.Entry<int[]> entry : sections.long2ObjectEntrySet()) {
                SectionPos sectionPos = SectionPos.of(entry.getLongKey());
                IplRapier3DInvoker.ipl$addKinematicContraptionChunkSection(
                    scene, id, sectionPos.x(), sectionPos.y(), sectionPos.z(), entry.getValue());
            }

            applyTransform(scene, id, g, minAnchored);
            IplRapier3DInvoker.ipl$setLocalBounds(
                scene, id, -r, 0, -r, holeW + r - 1, 0, holeH + r - 1);

            LOG.info("[IPL-RIM] spawn id={} hole={}x{} margin={} anchor={} scene={}",
                id, holeW, holeH, r, minAnchored ? "min" : "max", Long.toHexString(scene));
            return id;
        } catch (Throwable t) {
            LOG.error("[IPL-RIM] spawn failed", t);
            return -1;
        }
    }

    private static void applyTransform(long scene, int id, RimGeometry g, boolean minAnchored) {
        int holeW = holeW(g);
        int holeH = holeH(g);

        // Right-handed basis with Y along the normal: X = axisW, Z = X×Y. Z is ±the
        // height axis — irrelevant, extents are symmetric.
        org.joml.Vector3d yAxis = new org.joml.Vector3d(
            g.normal().x, g.normal().y, g.normal().z).normalize();
        org.joml.Vector3d xAxis = new org.joml.Vector3d(
            g.axisW().x, g.axisW().y, g.axisW().z).normalize();
        org.joml.Vector3d zAxis = new org.joml.Vector3d(xAxis).cross(yAxis).normalize();
        org.joml.Matrix3d basis = new org.joml.Matrix3d(
            xAxis.x, xAxis.y, xAxis.z,
            yAxis.x, yAxis.y, yAxis.z,
            zAxis.x, zAxis.y, zAxis.z);
        org.joml.Quaterniond rot = new org.joml.Quaterniond().setFromNormalized(basis);

        // The carpet plate occupies local y ∈ [0, 1/16]; its mid-plane (y = 1/32) is
        // the portal plane. Anchor the hole's min (or max) corner to the aperture's
        // matching corner.
        org.joml.Vector3d anchorLocal = minAnchored
            ? new org.joml.Vector3d(0, 0.03125, 0)
            : new org.joml.Vector3d(holeW, 0.03125, holeH);
        double sign = minAnchored ? -1.0 : 1.0;
        Vec3 corner = new Vec3(
            g.planePoint().x + sign * (xAxis.x * g.halfW() + zAxis.x * g.halfH()),
            g.planePoint().y + sign * (xAxis.y * g.halfW() + zAxis.y * g.halfH()),
            g.planePoint().z + sign * (xAxis.z * g.halfW() + zAxis.z * g.halfH()));

        org.joml.Vector3d rotated = rot.transform(
            new org.joml.Vector3d(anchorLocal), new org.joml.Vector3d());
        IplRapier3DInvoker.ipl$setKinematicContraptionTransform(
            scene, id,
            new double[]{0, 0, 0},
            new double[]{
                corner.x - rotated.x, corner.y - rotated.y, corner.z - rotated.z,
                rot.x, rot.y, rot.z, rot.w
            },
            new double[]{0, 0, 0, 0, 0, 0});
    }
}
