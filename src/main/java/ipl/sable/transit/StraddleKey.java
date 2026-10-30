package ipl.sable.transit;

import java.util.UUID;

/**
 * Identifies one construction's contact session with one portal face.
 *
 * <p>{@code via} is the chain path of the parent session ("" for a session of the real
 * body). A chained session is a projection of a projection: the image of the body that
 * already went through the portals in {@code via} straddles {@code portalUuid}.
 */
public record StraddleKey(UUID subLevelUuid, UUID portalUuid, String via) {
    public StraddleKey(UUID subLevelUuid, UUID portalUuid) {
        this(subLevelUuid, portalUuid, "");
    }

    public StraddleKey {
        if (via == null) via = "";
    }

    public boolean isRoot() {
        return via.isEmpty();
    }

    /** Full chain path, e.g. {@code A} for a root session or {@code A>B} for a chain. */
    public String path() {
        return via.isEmpty() ? portalUuid.toString() : via + ">" + portalUuid;
    }

    public int depth() {
        if (via.isEmpty()) return 1;
        int depth = 2;
        for (int i = 0; i < via.length(); i++) if (via.charAt(i) == '>') depth++;
        return depth;
    }

    /** Key of a child session continuing this chain through {@code nextPortal}. */
    public StraddleKey child(UUID nextPortal) {
        return new StraddleKey(subLevelUuid, nextPortal, path());
    }
}
