package ipl.sable.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.client.IplClientProjections;
import ipl.sable.client.IplParentDimSync;
import ipl.sable.client.IplProjectionLevelScope;
import ipl.sable.client.IplStraddleRenderCache;
import ipl.sable.client.IplStraddleRenderState;
import ipl.sable.mixin.client.LevelRendererAccessor;
import ipl.sable.render.clip.IplClipPlanes;
import ipl.sable.render.route.IplOpenings;
import ipl.sable.transit.IplContraptionShape;
import ipl.sable.transit.IplContraptions;
import ipl.sable.transit.IplPieceMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import qouteall.q_misc_util.my_util.Plane;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Create contraptions on straddling bodies, drawn like the body's own blocks: each piece of the
 * body draws the part of the contraption in that piece, at the piece's pose and cut by the
 * apertures the contraption lies across ({@link IplContraptionShape#split}). The native piece is
 * drawn by the entity pass of the body's level, the others by the projection passes
 * ({@link #drawProjections}) with Create's renderer.
 *
 * <p>Flywheel cannot cut or move an instanced visual, so a contraption near an opening, not wholly
 * native, or of a transit successor still hidden behind its ghost is handed to Create's renderer
 * ({@link IplFlywheelFallback}). The hand-over starts before the contraption reaches the opening:
 * by the time it is cut, its renderer already draws it.
 */
public final class IplContraptionRender {

    /** A contraption this close to an opening is handed over before it touches it. */
    private static final double NEAR_OPENING = 2.0;
    /** Farther than this from every opening, no contraption of a body can be near one. */
    private static final double BODY_REACH = 32.0;

    private static final class Flywheel {
        static final boolean LOADED = ModList.get().isLoaded("flywheel");
    }

    /** Touched only with Flywheel present. */
    private static final class FlywheelVisuals {
        /** Flywheel serves only the player's level, and not what it handed over. */
        static boolean draw(Entity entity) {
            return BackendManager.isBackendOn() && entity.level() == Minecraft.getInstance().level
                && !IplFlywheelFallback.isHanded(entity);
        }
    }

    private static final Map<Entity, IplContraptionShape> SHAPES = new IdentityHashMap<>();
    private static final Map<Entity, IplContraptionShape.Split> SPLITS = new IdentityHashMap<>();
    private static final Map<ClientLevel, Map<SubLevel, List<Entity>>> CONTRAPTIONS = new IdentityHashMap<>();
    private static long cacheFrame = Long.MIN_VALUE;

    private IplContraptionRender() {}

    // ------------------------------------------------------------------ queries

    private static void refresh() {
        long frame = IplStraddleRenderCache.frameId();
        if (frame == cacheFrame) return;
        cacheFrame = frame;
        SHAPES.clear();
        SPLITS.clear();
        CONTRAPTIONS.clear();
    }

    /** Contraptions mounted on {@code sub}, this frame. */
    private static List<Entity> contraptionsOf(ClientSubLevel sub) {
        return contraptionsIn(sub.getLevel()).getOrDefault(sub, List.of());
    }

    /** Contraptions of {@code level} by the body they are mounted on, this frame. */
    private static Map<SubLevel, List<Entity>> contraptionsIn(ClientLevel level) {
        refresh();
        return CONTRAPTIONS.computeIfAbsent(level, l -> {
            Map<SubLevel, List<Entity>> out = new IdentityHashMap<>();
            for (Entity entity : l.entitiesForRendering()) {
                if (entity.isRemoved() || !IplContraptions.isContraption(entity)) continue;
                SubLevel body = Sable.HELPER.getContaining(entity);
                if (body != null) out.computeIfAbsent(body, b -> new ArrayList<>(2)).add(entity);
            }
            return out;
        });
    }

    @Nullable
    private static ClientSubLevel bodyOf(Entity entity) {
        return Sable.HELPER.getContaining(entity) instanceof ClientSubLevel sub ? sub : null;
    }

    @Nullable
    private static IplContraptionShape shape(Entity entity) {
        refresh();
        if (SHAPES.containsKey(entity)) return SHAPES.get(entity);
        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        IplContraptionShape shape = IplContraptions.shape(entity, partialTick);
        SHAPES.put(entity, shape);
        return shape;
    }

    @Nullable
    private static IplContraptionShape.Split split(Entity entity, IplPieceRender.Frame frame) {
        refresh();
        if (SPLITS.containsKey(entity)) return SPLITS.get(entity);
        IplContraptionShape shape = shape(entity);
        IplContraptionShape.Split split = null;
        if (shape != null) {
            int tether = IplPieceMap.classify(frame.edges, frame.view.label((int) Math.floor(shape.tx),
                (int) Math.floor(shape.ty), (int) Math.floor(shape.tz)), shape.tx, shape.ty, shape.tz);
            split = shape.split(frame.edges, tether);
        }
        SPLITS.put(entity, split);
        return split;
    }

    /** Planes keeping {@code node}'s side of every edge the contraption lies across (at most two). */
    private static Plane[] planes(IplContraptionShape.Split split, int node, Pose3dc drawPose) {
        List<Plane> out = new ArrayList<>(2);
        for (IplPieceMap.Edge e : split.cut()) {
            if (e.parent != node && e.child != node) continue;
            if (out.size() == IplClipPlanes.PIECE_CUTS) break;
            out.add(IplPieceRender.keepPlane(e, node, drawPose));
        }
        return out.toArray(new Plane[0]);
    }

    /** Whether Create's renderer draws {@code entity} now (no Flywheel visual, or the visual handed over). */
    private static boolean rendererDraws(Entity entity) {
        return !Flywheel.LOADED || !FlywheelVisuals.draw(entity);
    }

    /** Contraptions of {@code level} that Flywheel must hand to their renderer this frame. */
    static Set<Entity> wantedByRenderer(ClientLevel level) {
        Set<Entity> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!IplContraptions.CREATE) return wanted;
        for (Map.Entry<SubLevel, List<Entity>> body : contraptionsIn(level).entrySet()) {
            if (!(body.getKey() instanceof ClientSubLevel sub)) continue;
            if (IplParentDimSync.isSuppressedSuccessor(sub)) {
                wanted.addAll(body.getValue());
                continue;
            }
            IplPieceRender.Frame frame = IplPieceRender.frameFor(sub);
            if (frame == null && !IplOpenings.anyNear(level, sub.boundingBox().toMojang(), BODY_REACH)) continue;
            for (Entity entity : body.getValue()) {
                IplContraptionShape.Split split = frame == null ? null : split(entity, frame);
                if (split != null && !split.whollyIn(frame.view.nativeNode()) || nearOpening(level, sub, entity)) {
                    wanted.add(entity);
                }
            }
        }
        return wanted;
    }

    /** Whether everything {@code entity} covers or sweeps comes near an opening of {@code level}. */
    private static boolean nearOpening(ClientLevel level, ClientSubLevel sub, Entity entity) {
        IplContraptionShape shape = shape(entity);
        if (shape == null) return false;
        double[] b = shape.bounds();
        BoundingBox3d world = new BoundingBox3d(b[0], b[1], b[2], b[3], b[4], b[5]).transform(sub.renderPose());
        return IplOpenings.anyNear(level, world.toMojang(), NEAR_OPENING);
    }

    // ------------------------------------------------------------------ drawing

    /** The entity pass of the contraption's own level: the body's native piece. */
    public static void drawInOwnPass(Entity entity, MultiBufferSource buffers, Runnable draw) {
        ClientSubLevel sub = IplContraptions.isContraption(entity) ? bodyOf(entity) : null;
        if (sub == null || !rendererDraws(entity)) {
            draw.run();
            return;
        }
        Runnable vanilla = () -> IplSubLevelBlockEntityPass.runForcingVanilla(draw);
        IplPieceRender.Frame frame = IplPieceRender.frameFor(sub);
        if (frame == null || !(buffers instanceof MultiBufferSource.BufferSource source)) {
            vanilla.run();
            return;
        }
        drawPiece(entity, frame, frame.view.nativeNode(), frame.pose, source, vanilla);
    }

    /**
     * Every projection of this pass draws the part of its body's contraptions in its piece, with
     * Create's renderer: Flywheel never draws a projection, so nothing waits for a hand-over here.
     */
    public static void drawProjections(List<IplClientProjections.StraddleProjection> projections, LevelRenderer renderer,
                                       Camera camera, float partialTick, MultiBufferSource.BufferSource source) {
        if (!IplContraptions.CREATE) return;
        Vec3 cam = camera.getPosition();
        for (IplClientProjections.StraddleProjection projection : projections) {
            List<Entity> contraptions = contraptionsOf(projection.sub());
            if (contraptions.isEmpty()) continue;
            IplPieceRender.Frame frame = IplPieceRender.frameFor(projection.sub());
            if (frame == null) continue;
            IplStraddleRenderState.set(projection);
            try {
                for (Entity entity : contraptions) {
                    drawPiece(entity, frame, projection.node(), projection.mappedPose(), source, () ->
                        IplProjectionLevelScope.runInOwnerLevel(projection.sub(), () ->
                            IplSubLevelBlockEntityPass.runForcingVanilla(() ->
                                ((LevelRendererAccessor) renderer).ipl$renderEntity(
                                    entity, cam.x, cam.y, cam.z, partialTick, new PoseStack(), source))));
                }
            } finally {
                IplStraddleRenderState.clear();
            }
        }
    }

    /** Draws the part of {@code entity} in piece {@code node}, cut where it lies across an aperture. */
    private static void drawPiece(Entity entity, IplPieceRender.Frame frame, int node, Pose3dc drawPose,
                                  MultiBufferSource.BufferSource source, Runnable draw) {
        IplContraptionShape.Split split = split(entity, frame);
        if (split == null) {
            // No blocks to classify: whatever Create draws stays with the native piece.
            if (node == frame.view.nativeNode()) draw.run();
            return;
        }
        if (node < 0 || !split.nodes().get(node)) return;
        Plane[] planes = planes(split, node, drawPose);
        if (planes.length == 0) {
            draw.run();
            return;
        }
        // Planes apply when the buffer is flushed: flush before and after, outside the renderer.
        source.endBatch();
        IplPieceClip.set(planes);
        try {
            draw.run();
            source.endBatch();
        } finally {
            IplPieceClip.clear();
        }
    }
}
