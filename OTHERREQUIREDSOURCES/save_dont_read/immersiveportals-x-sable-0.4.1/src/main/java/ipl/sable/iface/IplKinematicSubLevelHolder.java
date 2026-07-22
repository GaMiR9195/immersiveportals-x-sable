package ipl.sable.iface;

/**
 * Duck interface added to {@code dev.ryanhcode.sable.sublevel.ServerSubLevel} via
 * {@link ipl.sable.mixin.SableServerSubLevelKinematicMixin}.
 *
 * <p>A sub-level flagged as a kinematic mirror:
 * <ul>
 *   <li>is NOT enrolled in Sable's physics pipeline (no simulation; pose externally driven)</li>
 *   <li>is NOT pose-readback-overwritten each physics substep</li>
 *   <li>is NOT force-applied via {@code prePhysicsTick} / {@code applyQueuedForces}</li>
 * </ul>
 *
 * <p>Its block layout, mass tracker, bounding box, and tracking-system enrollment work
 * normally. The mirror appears to players in dest dim as a regular sub-level; we just
 * own its pose updates entirely from outside Sable's physics loop.
 *
 * <p>Used by Phase 2 sub-level transit: a mirror is spawned in the destination dimension
 * when an airship approaches a portal, and its pose is driven each tick from the source
 * airship's pose via the portal's coord transform. Provides a visual preview of "where
 * the airship is about to appear" before the actual crossing handoff.
 */
public interface IplKinematicSubLevelHolder {
    boolean ipl$isKinematicMirror();
    void ipl$setKinematicMirror(boolean isKinematic);
}
