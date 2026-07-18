# Bug 1: Hosted Sub-Level And Portal Straddle Failures

## User-Observed State

- After the latest collision experiment, all normal hosted sub-levels are physically broken: their own Rapier bodies fall through terrain.
- A player can still stand on those visually falling sub-levels. Player collision is therefore a separate mapped/entity collision path and does not prove that the sub-level's native Rapier collision works.
- During a cross-dimension portal crossing, the destination-side projected half (called "clone" in discussion) has collision with the destination world only while it is inside the portal.
- Once that half exits the destination portal, its collision disappears.
- The intended requirement is collision for the OUT/destination portion of a straddling construction, including same-dimension portals.
- Touching a portal can restore an invisible sub-level. This is a separate client render/culling/handoff failure, not proof of a GL clip-state failure.

## RenderDoc Evidence

The captured sequence around a normal sub-level draw was:

```text
437 glDisable(GL_CLIP_DISTANCE1)
438 glUniform4fv(Program 2396, { 0.00, 0.00, 0.00, 1.00 })
439 glUniformMatrix4fv(...)
440 glUniform3fv(...)
441 glBindVertexArray(...)
442 glDrawElements(60)
```

This is normal sub-level clip scope teardown: slot 1 is disabled and its equation is reset before a later draw.

The larger capture showed repeated correct brackets:

```text
507-511 clip equation written to all registered programs
512-513 glEnable(GL_CLIP_DISTANCE1)
515 glDisable(GL_CLIP_DISTANCE1)
516 current program equation reset to (0,0,0,1)
```

and again at `582-591` for another program. The later `glClear` at events `599-602` clears a framebuffer after those draws. It is not the cause of the empty sub-level.

The empty wireframe/bounds with no terrain geometry means the client object and bounds exist but its section render data/visible-section state is missing or stale.

## Prior Client Changes Still Present

- `IplParentDimSync` maps buffered client poses through the portal during parent handoff, changes the parent level, clears the crossing latch, invalidates active render-pass caches, and invalidates the cached render pose.
- A temporary `SubLevelRenderDispatcher.rebuild(...)` call was added after handoff to recover empty render sections.
- Runtime testing showed that this full rebuild takes roughly 4-5 seconds before the sub-level becomes visible.
- That temporary rebuild call was removed. `rebuild()` only marks all sections dirty; Sable then asynchronously recompiles them, so it is not an immediate visibility repair.
- Deferred handoff retry remains: if the handoff RPC arrives before redirected full-sync creates the client sub-level, it is retained and retried from the client tick.

## Collision Code Before The Failed Experiment

`IplStraddleCloneBody` creates a native Rapier clone while a hosted construction straddles a translation-only, scale-1, block-aligned portal.

- The real body remains in the source scene.
- The clone body is created in the destination scene at the portal-mapped pose.
- Every loaded non-air plot section is packed and uploaded to the clone through `Rapier3D.addChunk(scene, x, y, z, packed, false, cloneId)`.
- The clone is pinned to the mapped real-body pose before each destination physics substep.
- Solver correction from the clone is transferred back to the real body after that substep.
- Terrain around clone destination bounds is enrolled by `IplHostedTicketManagerMixin` through `IplStraddleCloneBody.forEachSessionInto`.
- On straddle end, clone sections are removed with `removeChunk(..., false)` and the clone body is removed.

The clip-region payload was corrected so it carries the portal finite rectangle:

```text
[plane point (3), normal (3), axisW (3), halfWidth, axisH (3), halfHeight]
```

Previously the Java caller sent zero width/height axes, so native finite aperture clipping could not represent the portal rectangle.

## Failed Native Experiment: Fully Reverted

An experiment was made to give native clone bodies a dedicated `chunk_map`.

It changed all of these files:

- `natives/rapier/src/lib.rs`
- `src/main/java/ipl/sable/transit/IplStraddleCloneBody.java`
- `src/main/java/ipl/sable/natives/IplRapierNatives.java`

The experiment added a `useDedicatedChunks(scene, bodyId)` JNI method and changed `Rapier3D_addChunk` to send non-global clone sections to that private map.

Runtime result: normal hosted sub-level physical bodies started falling through terrain. This showed the experiment altered shared/native Sable section behavior incorrectly.

The experiment is fully reverted:

- The Java JNI declaration is removed.
- The Java clone call is removed.
- The Rust JNI symbol is removed.
- `Rapier3D_addChunk` is restored to Sable's original behavior:

```rust
main_level_chunks.insert(section_pos, chunk);
let chunk = main_level_chunks.get(&section_pos).unwrap();
if global == 0 {
    if object_id != -1 {
        let body = level_colliders.get_mut(&object_id).unwrap();
        body.insert_chunk(chunk, x, y, z, collider_map);
    }
} else {
    // populate global terrain octree
}
```

- Clone cleanup is restored to remove each uploaded section before removing the clone body.

Do not reintroduce the dedicated-map experiment without first proving the exact native ownership and lookup requirements for normal hosted body sections.

## Current Native Storage Fact

Sable's original `Rapier3D_addChunk` always stores uploaded sections in the scene-wide `main_level_chunks`. For `global == false` and a valid body id it also inserts that section into that body's octree.

Therefore, during a same-dimension portal straddle, the real body and clone body share the same Rapier scene and may write identical plot-local section coordinates into `main_level_chunks`. Their separate body octrees are built from the uploaded section at upload time, but the shared map and `removeChunk` cleanup still need careful analysis. This is the suspected same-dimension OUT collision area, but it has not been correctly fixed.

## Noise Removed

The server-side `[IPL-PHASE]` log was a per-tick diagnostic and spammed the console while a construction remained `CROSSED`. It was removed.
