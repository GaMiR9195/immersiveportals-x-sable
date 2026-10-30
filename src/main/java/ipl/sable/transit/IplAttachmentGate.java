package ipl.sable.transit;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.FreeConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.atlas.IplScenes;
import ipl.sable.natives.IplRapierNatives;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * "Is this sub-level attached to something?" — while it is, a finished crossing does
 * not flip: the session stays open and the far side keeps living on the projection.
 *
 * <ul>
 *   <li>non-Free constraints to the static world (staff lock, stickers, anchors)
 *       block; Free constraints to the world (physics-staff drag, handles) are motor
 *       drivers, not attachments, and are ignored;</li>
 *   <li>joints to sub-levels outside the rigid group block (they would be torn
 *       across the seam);</li>
 *   <li>any rope attached to a group member blocks;</li>
 *   <li>a jointed group never moves to another dimension (joints cannot follow a
 *       level change); it keeps flying on its projection instead.</li>
 *   <li>"strange" connections that are NOT native joints: Simulated springs, docking
 *       connectors, rope holders, swivel plates and any other physics actor apply their
 *       coupling as Java-side forces every physics tick. Native joint queries never see
 *       them, so a spring-tethered ship used to flip and leave its partner behind (same
 *       dimension: the spring yanked across the portal distance and snapped; cross
 *       dimension: the partner lookup failed and the spring broke). Three probes:
 *       <ol>
 *         <li>Sable's own actor contract: {@code sable$getConnectionDependencies()} and
 *             {@code sable$getLoadingDependencies()} of the group's actors (forward)
 *             and of every other sub-level's actors that point AT the group (reverse);</li>
 *         <li>paired actors: an actor block entity whose {@link BlockPos} field points
 *             outside its own plot at a block entity of the SAME class (spring pairs,
 *             connector pairs, from any mod) -- to the world = anchored, to another
 *             sub-level outside the group = linked. A world-anchored spring reports no
 *             dependency at all, so this is the only way to see it;</li>
 *         <li>the native joint/rope queries (unchanged).</li>
 *       </ol></li>
 * </ul>
 */
public final class IplAttachmentGate {
    private static final Map<ServerSubLevel, List<PhysicsConstraintHandle>> WORLD_LINKS = new WeakHashMap<>();

    private IplAttachmentGate() {}

    /** Called for every constraint Sable creates (server thread). */
    public static synchronized void onConstraintAdded(@Nullable PhysicsPipelineBody a, @Nullable PhysicsPipelineBody b,
                                                      PhysicsConstraintConfiguration<?> config,
                                                      @Nullable PhysicsConstraintHandle handle) {
        if (handle == null || config instanceof FreeConstraintConfiguration) return;
        if ((a == null) == (b == null)) return; // body-body joints are read from the natives
        if (!((a == null ? b : a) instanceof ServerSubLevel sub)) return;
        WORLD_LINKS.computeIfAbsent(sub, ignored -> new ArrayList<>(1)).add(handle);
    }

    public static synchronized boolean isWorldAnchored(ServerSubLevel sub) {
        List<PhysicsConstraintHandle> links = WORLD_LINKS.get(sub);
        if (links == null) return false;
        links.removeIf(handle -> {
            try {
                return !handle.isValid();
            } catch (Throwable t) {
                return true;
            }
        });
        if (links.isEmpty()) {
            WORLD_LINKS.remove(sub);
            return false;
        }
        return true;
    }

    /** Reason the group must not flip right now, or null when it may (legacy: mates = native joints). */
    @Nullable
    public static String flipBlocker(ServerSubLevel ship, List<ServerSubLevel> mates, boolean crossDimension) {
        return flipBlocker(ship, mates, !mates.isEmpty(), crossDimension);
    }

    /**
     * Reason the transit group must not flip right now, or null when it may.
     *
     * <p>{@code group}: every body that crosses together with {@code ship} -- native rigid
     * mates AND actor-linked partners (springs, connectors, ...; see {@link #actorPartners}).
     * {@code nativeJointed}: some member has native joints (those cannot follow a level
     * change).
     *
     * <p>Русский комментарий (почему): раньше любой "актёрный" партнёр (пружина между
     * двумя под-уровнями) навсегда запрещал флип: прошедший кусок жил проекцией, а
     * второй — оставался реальным, и пара разваливалась (часть — проекция без
     * взаимодействия, перетаскивание посохом не работало). Теперь партнёры — члены одной
     * группы перехода: пока прошли не все, прошедшие держат завершённую (широкую) сессию;
     * когда прошли все — группа флипается вместе одной изометрией, и пружина видит
     * прежнюю взаимную геометрию (между измерениями — те же UUID и, при свободном слоте,
     * те же координаты плотов).
     */
    @Nullable
    public static String flipBlocker(ServerSubLevel ship, List<ServerSubLevel> group,
                                     boolean nativeJointed, boolean crossDimension) {
        List<ServerSubLevel> mates = group;
        if (isWorldAnchored(ship)) return "anchored";
        for (ServerSubLevel mate : mates) if (isWorldAnchored(mate)) return "anchored";
        if (crossDimension && nativeJointed) return "jointed-group";
        if (!(ship.getLevel() instanceof ServerLevel level)) return null;
        String actorReason;
        try {
            actorReason = actorBlocker(level, ship, mates);
        } catch (RuntimeException | LinkageError e) {
            // A misbehaving third-party actor must not wedge transit: log once, fall back.
            if (!loggedActorFailure) {
                loggedActorFailure = true;
                org.slf4j.LoggerFactory.getLogger("ipl-sable-transit")
                    .warn("[IPL-TRANSIT] actor connection probe failed (logged once)", e);
            }
            actorReason = null;
        }
        if (actorReason != null) return actorReason;
        if (!IplScenes.isEnabled()) return null;
        long scene = IplScenes.sceneHandle(level);
        if (scene == 0) return null;
        try {
            // Every native joint partner of every member must be inside the group.
            Set<Integer> ids = new HashSet<>(1 + mates.size());
            ids.add(Rapier3D.getID(ship));
            for (ServerSubLevel mate : mates) ids.add(Rapier3D.getID(mate));
            List<ServerSubLevel> members = new ArrayList<>(1 + mates.size());
            members.add(ship);
            members.addAll(mates);
            for (ServerSubLevel member : members) {
                int id = Rapier3D.getID(member);
                for (int linked : IplRapierNatives.connectedSableBodyIds(scene, id, false)) {
                    if (!ids.contains(linked)) return "linked";
                }
                if (IplRapierNatives.ropesAttachedToSableBody(scene, id).length > 0) return "rope";
            }
        } catch (UnsatisfiedLinkError ignored) {
            // Older natives without the queries: only the Java-side world links apply.
        }
        return null;
    }

    public static synchronized void clearAll() {
        WORLD_LINKS.clear();
    }

    // ------------------------------------------------------------------ actor connections

    private static boolean loggedActorFailure;

    /** Non-static {@link BlockPos} fields of an actor class (own hierarchy below BlockEntity). */
    private static final ClassValue<Field[]> PAIR_FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            List<Field> out = new ArrayList<>(2);
            for (Class<?> c = type; c != null && c != BlockEntity.class && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType() != BlockPos.class) continue;
                    try {
                        f.setAccessible(true);
                        out.add(f);
                    } catch (RuntimeException ignored) {
                        // module-protected field: skip it
                    }
                }
            }
            return out.toArray(new Field[0]);
        }
    };

    @Nullable
    private static String actorBlocker(ServerLevel level, ServerSubLevel ship, List<ServerSubLevel> mates) {
        Set<UUID> group = new HashSet<>(1 + mates.size());
        List<ServerSubLevel> members = new ArrayList<>(1 + mates.size());
        group.add(ship.getUniqueId());
        members.add(ship);
        for (ServerSubLevel mate : mates) {
            if (group.add(mate.getUniqueId())) members.add(mate);
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        for (ServerSubLevel member : members) {
            LevelPlot plot = member.getPlot();
            if (plot == null) continue;
            for (BlockEntitySubLevelActor actor : plot.getBlockEntityActors()) {
                if (dependsOutside(actor.sable$getConnectionDependencies(), group)
                    || dependsOutside(actor.sable$getLoadingDependencies(), group)) {
                    return "linked";
                }
                String paired = pairedBlocker(level, container, member, actor, group);
                if (paired != null) return paired;
            }
        }
        // Reverse: actors elsewhere that are coupled TO the group (a spring controller on a
        // neighbour, a bearing whose plate is our ship, ...).
        if (container != null) {
            for (SubLevel other : List.copyOf(container.getAllSubLevels())) {
                if (other.isRemoved() || group.contains(other.getUniqueId())) continue;
                LevelPlot plot = other.getPlot();
                if (plot == null) continue;
                for (BlockEntitySubLevelActor actor : plot.getBlockEntityActors()) {
                    if (dependsOnAny(actor.sable$getConnectionDependencies(), group)
                        || dependsOnAny(actor.sable$getLoadingDependencies(), group)) {
                        return "linked";
                    }
                }
            }
        }
        return null;
    }

    /** Upper bound of one transit group (a spring network is walked transitively). */
    public static final int MAX_TRANSIT_GROUP = 16;

    /**
     * Sub-levels of the same level coupled to {@code body} by physics actors rather than
     * native joints: forward connection/loading dependencies of its actors, paired actors
     * (a {@link BlockPos} field pointing at a same-class block entity on another
     * sub-level) and, in reverse, actors elsewhere that depend on {@code body}.
     */
    public static List<ServerSubLevel> actorPartners(ServerSubLevel body) {
        if (!(body.getLevel() instanceof ServerLevel level)) return List.of();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return List.of();
        java.util.LinkedHashSet<ServerSubLevel> out = new java.util.LinkedHashSet<>();
        try {
            LevelPlot plot = body.getPlot();
            if (plot != null) {
                for (BlockEntitySubLevelActor actor : plot.getBlockEntityActors()) {
                    addDependencies(out, actor.sable$getConnectionDependencies(), level);
                    addDependencies(out, actor.sable$getLoadingDependencies(), level);
                    ServerSubLevel paired = pairedPartner(level, container, body, actor);
                    if (paired != null) out.add(paired);
                }
            }
            Set<UUID> self = Set.of(body.getUniqueId());
            for (SubLevel other : List.copyOf(container.getAllSubLevels())) {
                if (other == body || other.isRemoved() || !(other instanceof ServerSubLevel server)) continue;
                LevelPlot otherPlot = other.getPlot();
                if (otherPlot == null) continue;
                for (BlockEntitySubLevelActor actor : otherPlot.getBlockEntityActors()) {
                    if (dependsOnAny(actor.sable$getConnectionDependencies(), self)
                        || dependsOnAny(actor.sable$getLoadingDependencies(), self)) {
                        out.add(server);
                        break;
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            if (!loggedActorFailure) {
                loggedActorFailure = true;
                org.slf4j.LoggerFactory.getLogger("ipl-sable-transit")
                    .warn("[IPL-TRANSIT] actor partner probe failed (logged once)", e);
            }
        }
        out.remove(body);
        return List.copyOf(out);
    }

    private static void addDependencies(java.util.Set<ServerSubLevel> out, @Nullable Iterable<SubLevel> deps,
                                        ServerLevel level) {
        if (deps == null) return;
        for (SubLevel dep : deps) {
            if (dep instanceof ServerSubLevel server && !server.isRemoved() && server.getLevel() == level) out.add(server);
        }
    }

    /** Partner sub-level of a paired actor (spring pairs etc.), or null (none / world partner). */
    @Nullable
    private static ServerSubLevel pairedPartner(ServerLevel level, ServerSubLevelContainer container,
                                                ServerSubLevel member, BlockEntitySubLevelActor actor) {
        if (!(actor instanceof BlockEntity be)) return null;
        for (Field field : PAIR_FIELDS.get(be.getClass())) {
            Object value;
            try {
                value = field.get(be);
            } catch (IllegalAccessException e) {
                continue;
            }
            if (!(value instanceof BlockPos pos) || pos.equals(be.getBlockPos())) continue;
            LevelPlot ownPlot = member.getPlot();
            if (ownPlot != null && ownPlot.contains(new ChunkPos(pos))) continue;
            if (!level.isLoaded(pos)) continue;
            BlockEntity partner = level.getBlockEntity(pos);
            if (partner == null || partner.getClass() != be.getClass()) continue;
            LevelPlot partnerPlot = container.getPlot(new ChunkPos(pos));
            SubLevel partnerSub = partnerPlot == null ? null : partnerPlot.getSubLevel();
            if (partnerSub instanceof ServerSubLevel server && !server.isRemoved() && server != member) return server;
        }
        return null;
    }

    private static boolean dependsOutside(@Nullable Iterable<SubLevel> deps, Set<UUID> group) {
        if (deps == null) return false;
        for (SubLevel dep : deps) {
            if (dep != null && !dep.isRemoved() && !group.contains(dep.getUniqueId())) return true;
        }
        return false;
    }

    private static boolean dependsOnAny(@Nullable Iterable<SubLevel> deps, Set<UUID> group) {
        if (deps == null) return false;
        for (SubLevel dep : deps) {
            if (dep != null && group.contains(dep.getUniqueId())) return true;
        }
        return false;
    }

    /**
     * Paired-actor probe: a {@link BlockPos} field of the actor pointing outside its own plot
     * at a loaded block entity of the same class. World partner = "anchored", partner on a
     * sub-level outside the group = "linked". Never loads chunks.
     */
    @Nullable
    private static String pairedBlocker(ServerLevel level, @Nullable ServerSubLevelContainer container,
                                        ServerSubLevel member, BlockEntitySubLevelActor actor, Set<UUID> group) {
        if (!(actor instanceof BlockEntity be)) return null;
        for (Field field : PAIR_FIELDS.get(be.getClass())) {
            Object value;
            try {
                value = field.get(be);
            } catch (IllegalAccessException e) {
                continue;
            }
            if (!(value instanceof BlockPos pos) || pos.equals(be.getBlockPos())) continue;
            LevelPlot ownPlot = member.getPlot();
            if (ownPlot != null && ownPlot.contains(new ChunkPos(pos))) continue; // same ship
            if (!level.isLoaded(pos)) continue;
            BlockEntity partner = level.getBlockEntity(pos);
            if (partner == null || partner.getClass() != be.getClass()) continue;
            LevelPlot partnerPlot = container == null ? null : container.getPlot(new ChunkPos(pos));
            SubLevel partnerSub = partnerPlot == null ? null : partnerPlot.getSubLevel();
            if (partnerSub == null) return "anchored-pair";
            if (!partnerSub.isRemoved() && !group.contains(partnerSub.getUniqueId())) return "linked-pair";
        }
        return null;
    }
}
