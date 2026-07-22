package ipl.sable.transit;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * <p><b>Thread-safety:</b> backed by a {@link ConcurrentHashMap} because this map is
 * touched from TWO threads in singleplayer (integrated server + client share one JVM
 * and one static registry):
 * <ul>
 *   <li>The <b>server thread</b> mutates it ({@link #put}/{@link #remove}) during
 *       mirror spawn/despawn and reads it ({@link #get}/{@link #all}) in the transit
 *       controller.</li>
 *   <li>The <b>render thread</b> iterates {@link #all()} every frame via
 *       {@code SourceClipPortalFinder.isKnownMirror} to decide clip-plane orientation
 *       for each straddling sub-level.</li>
 * </ul>
 * With a plain {@link java.util.HashMap}, concurrent read-during-write can corrupt the
 * bucket structure and spin a later {@code put}/{@code get} into an <i>infinite loop</i>
 * -- a silent server-thread hang with no exception (observed 29May: server thread froze
 * mid mirror-churn while the render thread kept going). {@code ConcurrentHashMap} gives
 * lock-free reads, safe writes, and weakly-consistent iterators (no
 * {@link java.util.ConcurrentModificationException}, no corruption).
 */
public final class MirrorRegistry {

    private static final Map<MirrorKey, MirrorEntry> entries = new ConcurrentHashMap<>();

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

    /**
     * Look up an entry by the mirror sub-level's own UUID. Used by the
     * unload-despawn path, which holds the mirror {@code ServerSubLevel}
     * directly and only knows its UUID (not the (source, portal) key).
     * Returns null if no entry matches.
     */
    public static MirrorEntry getByMirrorUuid(UUID mirrorUuid) {
        for (MirrorEntry e : entries.values()) {
            if (e.mirrorUuid().equals(mirrorUuid)) return e;
        }
        return null;
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

    /**
     * Drop every entry. Called on server-stopping so mirror state never outlives
     * the server lifecycle -- the registry is in-memory {@code static} state, and
     * in singleplayer the same JVM hosts successive integrated servers, so without
     * this a session-1 mirror entry survives into session 2 and the controller
     * treats it as a live mirror (the "3 airships after quit-to-title" duplication).
     */
    public static void clear() {
        entries.clear();
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
