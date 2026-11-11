package ipl.sable.transit;

import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.FreeConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which sub-levels cross a portal together, and which of them must not flip at all.
 *
 * <p>Links come from Sable alone, so every Sable mod is covered without mod-specific code:
 * <ul>
 *   <li>the connection graph published by block-entity actors
 *       ({@code sable$getConnectionDependencies}: bearings, springs, rope holders, connectors);</li>
 *   <li>every constraint created through Sable's physics pipeline between two sub-levels,
 *       including mods that publish no dependency.</li>
 * </ul>
 * Links are undirected and resolved once per tick and level, so a broken spring or a new joint
 * is seen at the next tick, never through a stale group id.
 *
 * <p>A rigid constraint to the static world anchors its body: an anchored group keeps living on
 * its projection instead of tearing the joint across the seam. Free constraints to the world
 * (a physics staff, a handle) drive a body and do not anchor it.
 */
public final class IplLinks {

    /** A linked group in one level; {@code complete} is false when a link could not be resolved. */
    record Group(List<ServerSubLevel> members, boolean complete, boolean anchored) {}

    private record Joint(PhysicsConstraintHandle handle, @Nullable UUID a, @Nullable UUID b, boolean free) {
        boolean anchors() {
            return !free && (a == null) != (b == null);
        }
    }

    private static final List<Joint> JOINTS = new ArrayList<>();
    private static final Map<ServerLevel, Map<UUID, Group>> GROUPS = new HashMap<>();
    private static int groupsTick = Integer.MIN_VALUE;
    private static int prunedSize;

    private IplLinks() {}

    /** Every constraint created through Sable's pipeline; {@code null} bodies are the static world. */
    public static synchronized void onConstraint(@Nullable PhysicsPipelineBody a, @Nullable PhysicsPipelineBody b,
                                                 PhysicsConstraintConfiguration<?> config,
                                                 @Nullable PhysicsConstraintHandle handle) {
        if (handle == null || a != null && !(a instanceof ServerSubLevel) || b != null && !(b instanceof ServerSubLevel)) {
            return;
        }
        boolean free = config instanceof FreeConstraintConfiguration;
        UUID idA = a == null ? null : ((ServerSubLevel) a).getUniqueId();
        UUID idB = b == null ? null : ((ServerSubLevel) b).getUniqueId();
        if (idA == null && idB == null || free && (idA == null || idB == null)) return;
        JOINTS.add(new Joint(handle, idA, idB, free));
        if (JOINTS.size() > 2 * prunedSize + 64) {
            JOINTS.removeIf(joint -> !joint.handle().isValid());
            prunedSize = JOINTS.size();
        }
        GROUPS.clear();
    }

    /** The group of {@code ship} this tick (itself alone when nothing links it). */
    static synchronized Group of(ServerSubLevel ship) {
        int tick = ship.getLevel().getServer().getTickCount();
        if (tick != groupsTick) {
            GROUPS.clear();
            groupsTick = tick;
        }
        Group group = GROUPS.computeIfAbsent(ship.getLevel(), IplLinks::resolve).get(ship.getUniqueId());
        return group != null ? group : new Group(List.of(ship), false, false);
    }

    /** Bodies changed level or links changed within the tick. */
    static synchronized void invalidate() {
        GROUPS.clear();
    }

    public static synchronized void clearAll() {
        JOINTS.clear();
        GROUPS.clear();
        groupsTick = Integer.MIN_VALUE;
        prunedSize = 0;
    }

    private static Map<UUID, Group> resolve(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return Map.of();
        Map<UUID, ServerSubLevel> live = new LinkedHashMap<>();
        for (ServerSubLevel sub : container.getAllSubLevels()) if (!sub.isRemoved()) live.put(sub.getUniqueId(), sub);

        Map<UUID, UUID> roots = new HashMap<>();
        Set<UUID> broken = new HashSet<>();
        Set<UUID> anchored = new HashSet<>();
        for (ServerSubLevel sub : live.values()) linkActors(sub, live, roots, broken);
        JOINTS.removeIf(joint -> !joint.handle().isValid());
        prunedSize = JOINTS.size();
        for (Joint joint : JOINTS) {
            if (joint.anchors()) {
                anchored.add(joint.a() != null ? joint.a() : joint.b());
            } else if (joint.a() != null && joint.b() != null) {
                boolean hasA = live.containsKey(joint.a()), hasB = live.containsKey(joint.b());
                if (hasA && hasB) union(roots, joint.a(), joint.b());
                else if (hasA || hasB) broken.add(hasA ? joint.a() : joint.b());
            }
        }

        Map<UUID, List<ServerSubLevel>> components = new LinkedHashMap<>();
        for (ServerSubLevel sub : live.values())
            components.computeIfAbsent(find(roots, sub.getUniqueId()), id -> new ArrayList<>()).add(sub);
        Map<UUID, Group> groups = new HashMap<>();
        for (List<ServerSubLevel> members : components.values()) {
            members.sort(Comparator.comparing(sub -> sub.getUniqueId().toString()));
            boolean complete = members.stream().noneMatch(sub -> broken.contains(sub.getUniqueId()));
            boolean isAnchored = members.stream().anyMatch(sub -> anchored.contains(sub.getUniqueId()));
            Group group = new Group(List.copyOf(members), complete, isAnchored);
            for (ServerSubLevel sub : members) groups.put(sub.getUniqueId(), group);
        }
        return groups;
    }

    /** One hop of Sable's actor contract; a failing third-party actor breaks only its own group. */
    private static void linkActors(ServerSubLevel sub, Map<UUID, ServerSubLevel> live,
                                   Map<UUID, UUID> roots, Set<UUID> broken) {
        try {
            for (BlockEntitySubLevelActor actor : sub.getPlot().getBlockEntityActors()) {
                Iterable<SubLevel> dependencies = actor.sable$getConnectionDependencies();
                if (dependencies == null) continue;
                for (SubLevel dependency : dependencies) {
                    if (dependency == null || dependency == sub) continue;
                    if (live.get(dependency.getUniqueId()) == dependency) union(roots, sub.getUniqueId(), dependency.getUniqueId());
                    else broken.add(sub.getUniqueId());
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            broken.add(sub.getUniqueId());
        }
    }

    private static UUID find(Map<UUID, UUID> roots, UUID id) {
        UUID root = id;
        for (UUID up = roots.get(root); up != null && !up.equals(root); up = roots.get(root)) root = up;
        for (UUID at = id; !at.equals(root); ) {
            UUID up = roots.get(at);
            roots.put(at, root);
            at = up;
        }
        return root;
    }

    private static void union(Map<UUID, UUID> roots, UUID a, UUID b) {
        UUID ra = find(roots, a), rb = find(roots, b);
        if (!ra.equals(rb)) roots.put(ra, rb);
    }
}
