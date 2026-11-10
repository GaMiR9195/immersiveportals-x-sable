package dev.ryanhcode.sable.api;

import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.*;

public final class SubLevelHelper {
    private SubLevelHelper() {}

    public static Collection<SubLevel> getConnectedChain(SubLevel root) {
        Set<SubLevel> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<SubLevel> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            SubLevel current = pending.removeFirst();
            if (!visited.add(current)) continue;
            for (BlockEntitySubLevelActor actor : current.getPlot().getBlockEntityActors()) {
                Iterable<SubLevel> dependencies = actor.sable$getConnectionDependencies();
                if (dependencies == null) continue;
                for (SubLevel dependency : dependencies) {
                    if (dependency != null && !visited.contains(dependency)) pending.addLast(dependency);
                }
            }
        }
        return visited;
    }
}