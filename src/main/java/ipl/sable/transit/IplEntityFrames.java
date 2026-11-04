package ipl.sable.transit;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * FRAME-AWARE SHIP-LOCAL ENTITY NETWORKING.
 *
 * <p>Sable networks an entity that stands on a ship in the ship's plot frame (client move
 * packets, server entity updates) and drags server players along with the ship between
 * packets. Every one of those sites reads the ship's RAW pose {@code P}. An entity standing
 * on a projection is attached to the image pose {@code M·P} instead ({@code M} = portal
 * isometry of the image), so the raw encoding produced coordinates that were neither in the
 * plot nor in the world: the server rejected every move of a player standing on a
 * cross-dimension projection (rubber-banding = "can't stand on it"), dragged it by the
 * ship's motion in the other dimension, and viewers dropped its position updates.
 *
 * <p>The rule: an entity is attached to the frame entity collision uses for it
 * ({@link IplStraddlePoseMap#chooseCollisionFrame}, the same choice the collision pose wrap
 * makes), and every networking/stick site uses that frame's pose. Positions of an entity
 * that is attached to an image are sent to viewers in plain world coordinates, so remote
 * clients need no knowledge of the frame. When the ship is not present in the entity's level
 * through any frame, positions travel as world coordinates and no stick is applied.
 *
 * <p>Мы не добавляем свой пакет «кадра» — сервер восстанавливает кадр
 * сам (ближайший к текущей позиции игрока образ), потому что разные кадры одного корабля
 * разнесены на смещение портала, а за пакет игрок смещается на доли блока.
 */
public final class IplEntityFrames {

    private IplEntityFrames() {}

    /**
     * Where an entity standing on a ship is attached, seen from the entity's level.
     *
     * @param image   portal mapping of the image frame, null for the native frame
     * @param present false when the ship is not physically present in the level at all
     */
    public record Attachment(@Nullable IplStraddlePoseMap.StraddleMapping image, boolean present) {
        public static final Attachment NATIVE = new Attachment(null, true);
        public static final Attachment ABSENT = new Attachment(null, false);

        public boolean isNative() {
            return present && image == null;
        }

        /** The ship's pose in this frame (only meaningful when {@link #present}). */
        public Pose3dc pose(Pose3dc real) {
            return image == null ? real : image.mapPose(real);
        }
    }

    /** Attachment of an entity whose bounding-box center is {@code center}. */
    public static Attachment attachment(SubLevel ship, Level level, Vec3 center) {
        boolean nativeLevel = ship.getLevel() == level;
        if (nativeLevel && IplProjectionTree.of(ship).isEmpty()) return Attachment.NATIVE;
        IplStraddlePoseMap.StraddleFrame frame = IplStraddlePoseMap.chooseCollisionFrame(ship, level, center);
        if (frame != null) return new Attachment(frame.mapping(), true);
        return nativeLevel ? Attachment.NATIVE : Attachment.ABSENT;
    }

    // ------------------------------------------------------------------ client -> server

    /**
     * Client, inside {@code LocalPlayer.sendPosition} after Sable replaced the position field
     * with {@code P⁻¹·W}: re-encodes it in the frame the player is attached to.
     */
    public static void encodeOutgoingPosition(Entity player, boolean forceWorld) {
        SubLevel ship = Sable.HELPER.getTrackingSubLevel(player);
        if (ship == null || ship.isRemoved()) return; // Sable left the field alone
        Attachment at = forceWorld ? Attachment.ABSENT
            : attachment(ship, player.level(), player.getBoundingBox().getCenter());
        if (at.isNative()) return;
        Pose3dc pose = ship.logicalPose();
        Vec3 world = pose.transformPosition(player.position());
        Vec3 field = at.present() ? at.pose(pose).transformPositionInverse(world) : world;
        ((EntityMovementExtension) player).sable$setPosField(field);
    }

    /** Mutable view of the position carried by a move packet. */
    public interface MovePacketPosition {
        boolean ipl$hasPos();

        double ipl$x();

        double ipl$y();

        double ipl$z();

        void ipl$setPosition(double x, double y, double z);
    }

    /**
     * Server, in {@code handleMovePlayer} right after Sable decoded the packet as
     * {@code P·local}: moves the decoded point into the frame the client encoded it in. The
     * candidates are the native frame (when the ship lives in this level) and every image of
     * the ship in this level; the client's frame is the one nearest to where the server
     * already has the player. A packet that cannot belong to any frame is rejected the same way
     * Sable rejects plot coordinates outside every plot.
     */
    public static void decodeIncomingPosition(Entity player, MovePacketPosition packet) {
        if (!packet.ipl$hasPos()) return;
        SubLevel ship = Sable.HELPER.getTrackingSubLevel(player); // set by Sable's decode
        if (ship == null || ship.isRemoved()) return;
        Level level = player.level();
        if (ship.getLevel() == level && IplProjectionTree.of(ship).isEmpty()) return;

        Vec3 decoded = new Vec3(packet.ipl$x(), packet.ipl$y(), packet.ipl$z());
        Vec3 reference = player.position();
        Vec3[] best = {null};
        double[] bestDistance = {Double.MAX_VALUE};
        IplProjectionTree.forEachFrameIn(ship, level, node -> {
            Vec3 candidate = node == null ? decoded : node.mapping.mapPoint(decoded);
            double distance = candidate.distanceToSqr(reference);
            if (distance < bestDistance[0]) {
                bestDistance[0] = distance;
                best[0] = candidate;
            }
        });
        if (best[0] == null) {
            packet.ipl$setPosition(reference.x, reference.y, reference.z);
            ((EntityMovementExtension) player).sable$setTrackingSubLevel(null);
            return;
        }
        packet.ipl$setPosition(best[0].x, best[0].y, best[0].z);
    }

    // ------------------------------------------------------------------ server stick

    /**
     * Server, in {@code ServerPlayer.tick} right after Sable's stick moved the player's
     * bounding-box center {@code c} to {@code P·L⁻¹·c} (L = last pose): replaces that with
     * the motion of the frame the player is attached to, {@code (M·P)·(M·L)⁻¹·c}, or with no
     * motion when the ship is absent from the player's level.
     */
    public static void correctServerStick(Entity player) {
        SubLevel ship = Sable.HELPER.getTrackingSubLevel(player);
        if (ship == null || ship.isRemoved()) return;
        Level level = player.level();
        if (ship.getLevel() == level && IplProjectionTree.of(ship).isEmpty()) return;

        Pose3dc pose = ship.logicalPose();
        Pose3dc last = ship.lastPose();
        Vec3 stuck = player.getBoundingBox().getCenter();
        Vec3 before = last.transformPosition(pose.transformPositionInverse(stuck));
        Attachment at = attachment(ship, level, before);
        if (at.isNative()) return;
        Vec3 target = at.present()
            ? at.pose(pose).transformPosition(at.pose(last).transformPositionInverse(before))
            : before;
        Vec3 correction = target.subtract(stuck);
        if (correction.lengthSqr() < 1.0e-14) return;
        player.setPos(player.position().add(correction));
    }

    // ------------------------------------------------------------------ server -> clients

    /**
     * Server, in {@code ServerEntity.sendChanges} after Sable replaced the position field
     * with {@code L⁻¹·W}: an entity attached to an image (or to a ship absent from its level)
     * is sent in world coordinates instead, which every client decodes as a plain world
     * position.
     */
    public static void prepareTrackedPosition(Entity entity) {
        SubLevel ship = Sable.HELPER.getTrackingSubLevel(entity);
        if (ship == null) return; // Sable left the field alone
        Level level = entity.level();
        if (ship.getLevel() == level && IplProjectionTree.of(ship).isEmpty()) return;
        if (attachment(ship, level, entity.getBoundingBox().getCenter()).isNative()) return;
        Vec3 world = ship.lastPose().transformPosition(entity.position());
        ((EntityMovementExtension) entity).sable$setPosField(world);
    }
}
