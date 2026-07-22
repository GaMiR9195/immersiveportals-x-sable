# Straddling Portal Rendering

## Current Fix: Rotated Destination Clone Cut

### Symptom

When a straddling construction was rotated, the destination-side clone could be
cut along a diagonal where the portal plane intersected its geometry. Depending
on which side of the rotated construction the player viewed from, the two halves
failed to meet and portal aperture pixels showed empty space or source geometry.

The boundary was geometric, not chunk-section aligned: it moved with the portal
plane and could cut through one continuous block face.

### Cause

Immersive Portals slot 0 (`iportal_ClippingEquation`) is expressed in
camera-relative world space. Sable's vanilla chunk draw, however, evaluates
`Position + ChunkOffset` in plot-local camera-relative space; Sable applies the
sub-level rotation later in its model-view matrix.

Using IP's world-space plane directly against that plot-local vertex input
rotated the effective clip plane with the construction. The error is invisible
at axis-aligned rotations and becomes apparent at arbitrary angles.

### Implemented Change

`SableSourceClipMixin` now temporarily converts IP slot 0 to Sable's input
space only around `VanillaChunkedSubLevelRenderData.renderChunkedSubLevel`:

- normal: `R_subLevel^-1 * normal_world`;
- constant: unchanged, because both inputs are already camera-relative;
- scope: restored to IP's original world-space equation immediately after the
  Sable draw.

`SubLevelClipUniformPatcher.patchPortalClipForVanillaSubLevel` performs the
conversion and `restorePortalClip` restores the original equation.

This leaves IP responsible for portal aperture stencil/depth behavior and does
not add a second destination clip plane.

### Files

- `src/main/java/ipl/sable/mixin/client/SableSourceClipMixin.java`
  Scopes slot-0 conversion to the affected Sable draw.
- `src/main/java/ipl/sable/render/SubLevelClipUniformPatcher.java`
  Converts and restores slot-0 equations.
- `src/main/java/ipl/sable/render/IplProgramRegistry.java`
  Existing identification of Sable vanilla plot-local shader inputs.

## Existing Supporting Behavior

- A mapped projection rendered through its active IP portal, including the
  active portal's flipped face, does not install slot 1. IP already owns the
  aperture clip for that render and an additional plane can make halves
  non-complementary.
- Source-side clipping remains slot 1 (`ipl_subLevelClipEquation`) for draws
  where IP has no active portal plane. It is scoped to the sub-level bracket.
- Slot 1 uses a plot-local equation for Sable vanilla terrain and world-space
  equations for Iris/Sodium terrain paths, matching their respective vertex
  coordinate spaces.
- Nested mapped projection state is stack-safe: an inner portal render restores
  the outer pose, plane, and portal state.
- The per-`LevelRenderer.renderLevel` cache avoids repeated hosted-sub-level,
  projection, and source-plane discovery within a render pass.

## Ruled Out For This Bug

These points are useful exclusions from the rotated clone-cut investigation:

- Not lighting. Lighting may change appearance but cannot create a sharp plane
  that intersects one continuous block face.
- Not whole-section frustum or chunk culling. The missing area is not aligned
  to chunk sections and can split a single face diagonally.
- Not a generic depth-test failure. The affected pixels have no surviving
  destination fragment after clipping; depth alone selects between fragments.
- Not a portal-plane epsilon/gap adjustment. Small offsets do not reconcile two
  differently rotated coordinate spaces.
- Not source movement orientation selection. The cut came from the destination
  clone's shader input space, independent of source motion direction.
- Not only flipped-portal identity. The flipped-face guard is still required to
  avoid duplicate clipping, but it does not correct a world-plane evaluated in
  plot-local coordinates.
- Not Iris `iris_gbuffers_*` coordinate handling. The demonstrated construction
  path uses Sable's vanilla chunk draw, whose input convention is different.

## Verification Needed

Runtime-check the fixed path with a construction rotated to a non-right angle,
viewed from both lateral sides near the portal center. The source and destination
halves must meet at the physical portal plane without an empty diagonal strip.

No build or automated check was run for this change.

## Source-Side Hosted Block Entity Clipping

### Symptom

While a hosted sub-level crosses a portal, its normal source-side chunk sections
correctly disappear after passing the portal plane. Signs, beds, chests, and
other block entities could remain visible in that clipped source half. This was
not destination projection behavior: the original sub-level was correctly being
hidden, but its separately rendered block-entity geometry was not.

### Implemented Change

- Before Sable dispatches a hosted block entity, transform that block's center
  with the current sub-level render pose and classify it against the same
  `SourceClipPortalFinder` plane used for source chunk sections.
- Do not dispatch a block entity whose center is on the culled side. It emits no
  model or sign-text vertices, so it cannot remain visible after its ordinary
  block has disappeared.
- This intentionally does not modify shared shader transformations, uniforms,
  or IP's `portal_area` stencil path. A previous shader-wide attempt caused
  startup failure before IP initialized its renderer.

The cost is one position transform and one plane dot product only for block
entities in a currently straddling hosted sub-level. A block entity crossing the
plane hides as one complete block entity instead of being cut through its model.

### Files

- `src/main/java/ipl/sable/mixin/client/SableVanillaSubLevelBERMixin.java`

### Verification Needed

Runtime-check a hosted sub-level containing signs, beds, chests, and text while
crossing a portal, including a rotated construction. After each block entity
passes the source clip plane, its source-side model and text must disappear with
the chunk sections; returning through the portal must remain normal.

No build or automated check was run for this change.

## Native Clone Cleanup

### Symptom

Server shutdown could abort the whole process with Rust panic
`No rigid body for id` at `rapier/src/lib.rs:702`.

### Cause

`Rapier3D.removeSubLevel` assumed every cleanup call still owned a live native
rigid body. A straddle clone can already have been removed by a transit or scene
teardown before a later cleanup path reaches the same `(scene, bodyId)` pair.
The native registry lookup used `expect`, and a Rust panic crossing JNI cannot
unwind into Java, so it terminates the JVM.

### Implemented Change

Native body removal is idempotent. `removeSubLevel` now removes the registry
entry when present and returns normally when it was already removed. Existing
live-body removal still performs the same Rapier collider, joint, and rigid-body
cleanup.

### Files

- `natives/rapier/src/lib.rs`
  Treats an absent body during `removeSubLevel` as completed cleanup instead of
  panicking across JNI.

### Verification Needed

Build and deploy the custom native DLL, then start and stop a world after a
straddle clone session and after a completed hosted transit. Server shutdown
must complete without `No rigid body for id` or a native abort.

No build or automated check was run for this change.

## Render Profiling And Culling

### JFR Findings

A 60-second JFR recording found repeated render-thread work during portal world
renders. The largest broad costs were particle rendering, portal frustum tests,
and Sodium dynamic-light lookups. IPL's own measurable hot path was
`SourceClipPortalFinder`: its render-pass lookups repeatedly rebuilt portal
candidate lists and allocated corner/interval arrays while evaluating the same
sub-levels.

### Implemented Change

- `IplStraddleRenderCache` now keeps portal candidates and canonical entrance-face
  results for the active render pass.
- `SourceClipPortalFinder` reuses those results, performs mirror detection once per
  lookup, and uses exact AABB projection extrema instead of allocating eight-corner
  and interval arrays.
- UUID tie-breaking compares UUIDs directly rather than allocating strings.
- Destination straddle projections now update Sable section culling while their
  portal-mapped render pose is active. This applies to both Sodium and vanilla
  render paths; source-pose culling is not valid for a mapped destination draw.

### Diagnostic Cleanup

- `IplRenderDataProbeMixin` was removed from the active mixin configuration. Its
  `re-dirty source` stack trace was an intentional temporary diagnostic, not a
  rendering failure.
- `IplServerWatchdog` ignores the precise `MinecraftServer.waitUntilNextTick`
  paused/idle path. It still reports actual non-idle server stalls.

### Files

- `src/main/java/ipl/sable/render/SourceClipPortalFinder.java`
- `src/main/java/ipl/sable/client/IplStraddleRenderCache.java`
- `src/main/java/ipl/sable/mixin/client/IplHostedSubLevelRenderSodiumMixin.java`
- `src/main/java/ipl/sable/mixin/client/IplHostedSubLevelRenderMixin.java`
- `src/main/java/ipl/sable/IplServerWatchdog.java`
- `src/main/resources/ipl_sable.mixins.json`

No build or automated check was run for these changes.

## Fast Re-entry And Coplanar Portal Faces

### Cause

Portal candidates were collapsed by destination dimension and their crossing
direction was inferred from the construction center. This was unstable for
separate portals with one destination and opposite portal faces sharing one
plane.

### Implemented Change

- `PortalCrossingDetector` now uses IP's fixed portal-facing direction and
  compares the previous and current full OBB poses. A new session requires a
  source-side crossing of the finite portal aperture.
- Fast crossings trace the OBB center and eight corners through IP's portal
  shape. This catches a one-tick full crossing without adding detection range.
- Portal companions collapse only when their directed entrance face and mapped
  exit are identical. Coplanar opposite faces remain separate and deterministic.
- Client source clipping uses the same directed-face selection, preventing a
  flipped coplanar face from clipping the construction in the wrong dimension.

### Cost

Detection evaluates two eight-corner OBBs per nearby portal. Finite ray traces
run only for a source-side crossing step, at most nine per candidate. This is
small compared with normal physics and rendering work.

### Files

- `src/main/java/ipl/sable/transit/PortalCrossingDetector.java`
- `src/main/java/ipl/sable/transit/SableTransitController.java`
- `src/main/java/ipl/sable/render/SourceClipPortalFinder.java`

No build or automated check was run for this change.

## Transit Quality Of Life

### Finite-Aperture Detection

The crossing detector previously classified an OBB only against the portal's
infinite supporting plane. A construction could therefore straddle that plane
beside a portal, gain a hosted straddle latch, and flip dimensions if moved
quickly to the other side.

`PortalCrossingDetector` now tests the twelve OBB edges against the crossing
plane and accepts `STRADDLING` only if an edge-plane hit lies within the portal's
finite width and height. `SableTransitController` removes an existing hosted
latch when that condition is lost. The construction must physically overlap the
aperture before a destination clone or eventual transit can start.

### Full Construction Extent

Sable plot bounds store inclusive maximum block coordinates. The crossing
detector previously transformed `max` directly, omitting the outer face at
`max + 1.0`; Sable's own `SubLevel.updateBoundingBox` includes that face.

The detector now evaluates the same physical OBB as Sable. `CROSSED` cannot
fire until the trailing outer block face has cleared the portal plane, avoiding
an early parent flip while the construction still occupies the output aperture.

### Exit Lock

After a hosted parent flip, only re-entry through the physical output aperture
is blocked until the construction fully clears it. All other portals remain
available, including same-dimension portals and nearby unrelated portals.

IP confirms the cluster relation used here: `reversePortal` lives in the
entrance portal's destination world at its destination position and maps back to
the original world. `flippedPortal` is the opposite face of a portal in the same
world. The lock evaluates the OBB against the finite aperture of `reversePortal`;
while it straddles, that output portal cannot start another session.

While it straddles, the transit controller ignores only `reversePortal` and its
flipped face. The lock does not remove any clone, render projection, or existing
session during the unfinished exit.

The exit-only aperture is expanded by `0.5` blocks along its width and height.
It remains a zero-thickness plane: the normal-direction crossing test is
unchanged. This small lateral overhang keeps the output lock active when only an
edge of a construction remains in the portal, without changing initial portal
entry or requiring a portal frame.


### Files

- `src/main/java/ipl/sable/transit/PortalCrossingDetector.java`
  Uses Sable's full inclusive plot bounds and reports whether a straddling OBB
  intersects the finite portal aperture.
- `src/main/java/ipl/sable/transit/SableTransitController.java`
  Gates hosted latches by aperture overlap and blocks only output-aperture
  re-entry until a flipped construction clears it.

## Seamless Destination Handoff

### Symptom

After a hosted construction fully left a destination portal, its destination
projection disappeared briefly before the normal destination-side construction
appeared. The construction could then visibly skip ahead despite continuous
motion while it straddled the aperture.

### Cause

The client evaluates straddling from its delayed interpolated AABB. That AABB
can become fully destination-side before the server-side `CROSSED` decision,
parent flip, and parent-dimension RPC reach the client. The projection lookup
then returned no straddling portal and removed the only visible destination
representation during this network window.

The first handoff implementation also reset Sable's interpolation buffer to a
fresh server pose. That pose is ahead of the delayed pose rendered by the
projection. Clearing the buffer therefore introduced a second visual jump when
the first destination movement update arrived. Sable additionally caches
`ClientSubLevel.renderPose()` per partial tick, allowing one source-space cache
read immediately after a parent flip.

### Implemented Change

`SourceClipPortalFinder` now retains the last valid source/destination split
The retained decision continues to render the destination projection until the
client receives the parent handoff. A full return to the source half-space still
releases it as an aborted crossing.

`SableRehomeOps` keeps existing trackers through the parent flip and sends a
handoff RPC immediately. It includes the exact crossing transform: source and
destination origins, rotation, and scale. `IplParentDimSync` maps the client's
currently rendered pose and all buffered Sable interpolation snapshots with that
transform, preserving the delayed timeline until native destination movement
packets extend it.

The client no longer discovers the source `Portal` entity by UUID during the
handoff. IP may not track a distant source portal in `entitiesForRendering()`
when a construction crosses it in one physics tick; that left the construction
in the source render frame after its server parent had already changed. The
server-provided transform is the same portal transform used for the server flip,
so this is a direct frame handoff rather than a visual fallback.

This work runs once per tracked client at a parent flip, not per frame. It
serializes eleven doubles and maps only the small interpolation buffer, avoiding
the entity-list scan. It has no continuing FPS cost after the handoff.

The handoff clears the retained split only after switching the parent level. It
also invalidates Sable's per-partial-tick render-pose cache, so the first native
destination draw cannot reuse a source-space cached pose.

### Files

- `src/main/java/ipl/sable/render/SourceClipPortalFinder.java`
  Retains a fully crossed destination projection until client handoff.
- `src/main/java/ipl/sable/transit/SableRehomeOps.java`
  Sends an immediate tracked-client parent handoff with the exact portal transform.
- `src/main/java/ipl/sable/client/IplParentDimSync.java`
  Maps visible and buffered poses from the handoff transform, then switches parent state.
- `src/main/java/ipl/sable/mixin/client/IplSnapshotInterpolatorAccessor.java`
  Exposes Sable's running interpolation pose for the atomic handoff.
- `src/main/java/ipl/sable/mixin/client/IplClientSubLevelRenderPoseAccessor.java`
  Invalidates Sable's cached render pose after handoff.
- `src/main/resources/ipl_sable.mixins.json`
  Registers the client-side accessors.

### Verification Needed

Runtime-check a smoothly moving construction crossing a portal while observing
from the destination side. The projection must remain visible until native
single-frame source-pose stutter.

No build or automated check was run for this change.

## Portal Block Entity Rendering

### Symptom

Signs, beds, chests, and other block entities on a hosted sub-level disappeared
while crossing a portal and could remain absent after arriving. The normal chunk
terrain still rendered, so returning through the portal could make the objects
appear again.

### Cause

`SableVanillaSubLevelBERMixin` returned before Sable dispatched every hosted
block entity whenever IP was rendering portal content. Destination straddle
projections therefore rendered their chunk sections but omitted the separate
block-entity pass.

Removing that return exposed a second timing error. Sable block-entity renderers
append vertices to `MultiBufferSource.BufferSource`; their shader and clip
distances are evaluated later at `endBatch()`. The old per-block-entity bracket
disabled slot 1 immediately after each renderer returned, before its queued
vertices were drawn. Thus a block entity could ignore the portal cut while it
straddled, or inherit a stale slot-0 portal plane after the native destination
handoff and disappear there.

The native-destination failure is not a missing clip plane. IP temporarily
swaps `LevelRenderer`'s `RenderBuffers` while it renders a portal dimension.
`VanillaSubLevelBlockEntityRenderer` captures the `RenderBuffers` supplied to
its constructor. Keeping one renderer instance on a `LevelRenderer` therefore
left it bound to a previous portal buffer after the player entered the
destination; block entities wrote into that old buffer while the native pass
flushed its current buffer. The destination projection worked because it was
drawn during the pass that originally created the cached renderer.

The attempted source-side fix that added slot 1 to global vanilla entity and text
shader transformations was reverted. It changed `portal_area`, a shader used by
IP's stencil setup, and left `RenderSystem` without an active shader before
`ViewAreaRenderer.renderPortalArea`. That produced a render-thread
`NullPointerException` on `shader.MODEL_VIEW_MATRIX`.

### Implemented Change

The portal-content early return is removed. `SubLevelBlockEntityRenderScope`
now brackets an entire hosted sub-level's block-entity pass, including its
`BufferSource.endBatch()` draw. Slot 1 therefore stays enabled with the matching
sub-level plane until queued vertices reach the GPU; it is then cleared before
the next sub-level or world draw.

`IplHostedSubLevelRenderMixin` now recreates its Sable BE renderer whenever the
current `LevelRenderer.renderBuffers` object changes. Each pass therefore writes
and flushes the same buffer set, including after IP enters or returns from a
portal dimension. The scope still flushes a clipped straddle pass only after the
complete sub-level pass, never between individual block entities, preserving
renderers which build shared buffers across their own calls.

The global entity/text slot-1 transformation and its program classification are
removed. IP's `portal_area` shader again has only its original slot-0 contract,
so the stencil render retains an active shader. The remaining source-side BE
clipping must be implemented through a Sable-specific renderer or shader path,
not by mutating IP's shared portal shaders.

The scope also disables IP slot 0 only for native passes where IP has no active
portal bracket. This prevents a stale portal equation from culling block entities
after a parent handoff, while leaving IP fully responsible for slot 0 during a
portal-content pass. Program-bind hooks follow the same ownership rule and do
not re-enable slot 0 for a source-only sub-level scope.

### Files

- `src/main/java/ipl/sable/mixin/client/SableVanillaSubLevelBERMixin.java`
  Allows hosted block entities to render during IP portal-content passes and
  defers to the outer sub-level scope when present.
- `src/main/java/ipl/sable/mixin/client/IplHostedSubLevelRenderMixin.java`
  Rebuilds the captured-buffer BE renderer after IP buffer swaps and renders each
  hosted or projected sub-level BE pass inside one scope.
- `src/main/java/ipl/sable/render/SubLevelBlockEntityRenderScope.java`
  Holds sub-level clip state through the deferred BE draw and restores it safely.
- `src/main/java/ipl/sable/mixin/client/IplGlUseProgramProbeMixin.java`
- `src/main/java/ipl/sable/render/IplProgramBindHook.java`
  Keep IP slot 0 scoped to active portal rendering.

### Verification Needed

Runtime-check signs, beds, chests, and animated block entities on a hosted
construction during a slow and a one-tick-fast portal crossing. Verify that
source-side BE geometry is cut behind the portal, the destination projection is
visible only on its complementary side, native destination rendering persists
after the player enters that dimension and IP swaps render buffers, and the
return trip remains correct.

No build or automated check was run for this change.
