package ipl.sable.transit;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side registry of active kinematic mirrors per (source UUID, portal UUID) pair.
 *
 * <p>Each entry records:
 * <ul>
 *   <li>which source sub-level is being mirrored</li>
 *   <li>which portal triggered the mirror</li>
 *   <li>which dest dim the mirror lives in</li>
 *   <li>the mirror's own sub-level UUID</li>
 * </ul>
 *
 * <p>The composite key {@code (sourceUuid, portalUuid)} handles the case where an
 * airship is near multiple portals — each portal gets its own mirror in its own
 * dest dim. (In practice rare for v1 since {@code SableTransitController} only fires
 * on one portal per airship per tick, but the registry is keyed defensively.)
 *
 * <p>The registry is server-side state, not synced to clients. Clients see mirrors as
 * regular sub-levels via Sable's normal tracking system.
 */
public final class MirrorRegistry {

    private static final Map<MirrorKey, MirrorEntry> entries = new HashMap<>();

    private MirrorRegistry() {}

    public record MirrorKey(UUID sourceUuid, UUID portalUuid) {}

    public record MirrorEntry(
        UUID sourceUuid,
        UUID portalUuid,
        UUID mirrorUuid,
        ResourceKey<Level> sourceDim,
        ResourceKey<Level> destDim
    ) {}

    public static void put(MirrorEntry entry) {
        entries.put(new MirrorKey(entry.sourceUuid(), entry.portalUuid()), entry);
    }

    public static MirrorEntry get(UUID sourceUuid, UUID portalUuid) {
        return entries.get(new MirrorKey(sourceUuid, portalUuid));
    }

    public static MirrorEntry remove(UUID sourceUuid, UUID portalUuid) {
        return entries.remove(new MirrorKey(sourceUuid, portalUuid));
    }

    public static Collection<MirrorEntry> getAllForSource(UUID sourceUuid) {
        java.util.List<MirrorEntry> out = new java.util.ArrayList<>();
        for (MirrorEntry e : entries.values()) {
            if (e.sourceUuid().equals(sourceUuid)) out.add(e);
        }
        return out;
    }

    public static Collection<MirrorEntry> all() {
        return Collections.unmodifiableCollection(entries.values());
    }

    /** Drop entries via a custom predicate. Returns count removed. */
    public static int removeIf(java.util.function.Predicate<MirrorEntry> predicate) {
        int removed = 0;
        Iterator<Map.Entry<MirrorKey, MirrorEntry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (predicate.test(it.next().getValue())) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }
}
