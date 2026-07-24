# Sub-Level Mod Compatibility — One Root: Level Identity

## Round 4 — Same-Slot Rehome + Constraint Soft-Fail (Swivel Crash)

### Crash trace analysis

`SwivelBearingBlockEntity.checkPersistence → reattachConstraint → addConstraint →
validateAnchors: "pos2 does not fall within the plot of the second sub-level"`.
Simulated persists the split part's link position (`platePos`) in PLOT
coordinates and re-attaches its rotary constraint from a BE tick. A swivel
activated on a WORLD structure creates the split part in the parent container;
our rehome then moved it to a DIFFERENT hosting slot — the stored anchor now
points outside the twin's plot, Sable's validation throws mid-tick, whole
server crashes.

### Changes

- **Same-slot rehome** (`SableRehomeOps` + `IplSubLevelContainerOriginAccessor`,
  new): the hosted twin is allocated at the source's GLOBAL plot coordinates
  whenever that slot is free in the hosting grid. Every persisted
  plot-coordinate reference (constraint anchors, mods' stored link/plate
  positions) survives the rehome, and the verbatim pose carries over with slot
  delta zero. Falls back to first-free with a warning when the slot is taken
  (two dimensions' ships on the same source slot).
- **Constraint validation soft-fail**
  (`IplConstraintValidationSoftFailMixin`, new): a stale persisted anchor is
  data, not a code invariant — `RapierPhysicsPipeline.addConstraint` now
  returns null with a throttled warning instead of throwing out of a ticking
  block entity and killing the server. Callers already handle a null handle
  (Simulated re-checks persistence and retries).

### Files

- `src/main/java/ipl/sable/transit/SableRehomeOps.java`
- `src/main/java/ipl/sable/mixin/IplSubLevelContainerOriginAccessor.java` (new)
- `src/main/java/ipl/sable/mixin/IplConstraintValidationSoftFailMixin.java` (new)
- `src/main/resources/ipl_sable.mixins.json`

### Verification Needed

Swivel bearing activated on a ground structure: split works, top stays
attached, no crash (old saves may log `[IPL-CONSTRAINT] rejected invalid
constraint` once per stale anchor — freshly split swivels must not).
`[IPL-REHOME]` should stop reporting differing src/dst plot minima in the
`[IPL-REHOME-POSE]` line.

No build or automated check was run for these changes.

## Round 3 — Connections: Physics Objects, Accelerator Routing, First-Upload Jump

### The pattern from the second test

Everything that CONNECTS two points broke identically: rope strands (fully
invisible), plunger-gun pairs (lying disconnected), swivel bearing tops
(detached from their base), spring coils (endpoints render, middle missing).
Plus: disassembly placed the ship's blocks into the void dimension at world
coordinates, and assembly still shifted ships by 0.5 along the assembler's
facing (scaling with size; swivel splits identical, single-block splits
unaffected).

### Causes and changes

- **Arbitrary physics objects never activated / lived in the wrong system**
  (`IplHostedPhysicsObjectRoutingMixin` + `IplHostedTicketQueryRoutingMixin` +
  `IplHostedPhysicsObjects`, new): mods resolve their physics system from
  `be.getLevel()` — the hosting void. A world-frame `ArbitraryPhysicsObject`
  (rope strand, joint, connection) added there is gated by
  `wouldBeLoaded(hostingLevel, object)`, which always answers no over void
  terrain — Simulated's rope strands were never simulated at all (hence no
  points even after the tracking-sink fix). Armed adds and the ticket query now
  route to the PARENT's system; removals follow a recorded object→system map so
  unarmed unload paths clean up correctly.
- **`LevelAccelerator` bypassed the world-frame router**
  (`IplLevelAcceleratorOverrideMixin`): Sable's `moveBlocks` reads/writes
  chunks through the accelerator's own cache, not `Level` methods — an armed
  hosted DISASSEMBLY therefore materialized the ship's blocks in the void at
  world coordinates. The accelerator's `getChunk` now applies the same gate as
  the router: hosting level + armed context + world-frame chunk → the parent's
  chunk.
- **Assembly/split offset root confirmed and fixed**
  (`IplMergedMassFirstUploadMixin`): `MergedMassTracker.uploadData` keeps the
  world mapping invariant on CoM moves (`position += R·(CoM − lastCoM)`), but
  the FIRST upload null-baselines `lastCenterOfMass`, computes zero movement,
  and still jumps `rotationPoint` from assembly's `plotAnchor + 0.5` fallback
  to the real CoM — shifting the ship by `R·(rpBefore − CoM)`: 0.5 along the
  assembler's facing for assembler+block, growing with size, zero for a single
  split block (CoM == rotation point) — matching every observation. The wrap
  applies the missing position compensation and re-teleports. A rehome guard
  skips plot-slot-translation jumps (> 512 blocks), which are correct
  uncompensated; the world-assembly case is fixed parent-side before the
  rehome copies the pose.
- **Spring coil / partner renders** (`IplHostedSubLevelRenderMixin`): the
  hosted BE render pass now arms the client world-frame context, so renderers
  resolving partners through the BE's level FIELD at world coordinates
  (SpringRenderer's `getPairedSpring`) read this pass's dimension instead of
  the void.

### Files

- `src/main/java/ipl/sable/mixin/IplHostedPhysicsObjectRoutingMixin.java` (new)
- `src/main/java/ipl/sable/mixin/IplHostedTicketQueryRoutingMixin.java` (new)
- `src/main/java/ipl/sable/mixin/IplMergedMassFirstUploadMixin.java` (new)
- `src/main/java/ipl/sable/dim/IplHostedPhysicsObjects.java` (new)
- `src/main/java/ipl/sable/mixin/IplLevelAcceleratorOverrideMixin.java`
- `src/main/java/ipl/sable/mixin/client/IplHostedSubLevelRenderMixin.java`
- `src/main/resources/ipl_sable.mixins.json`

### Verification Needed

Ropes visible and simulated (plunger pairs connected, winch/connector strands);
swivel bearing top stays attached, split has no offset and no crash (if it
still crashes, capture the crash report); springs show the coil between ship
and ground; assembler+block assembly lands exactly in place (watch for
`[IPL-MASS] first-upload rotation-point jump compensated` — nonzero values
confirm the fix engaging); disassembly places blocks into the ship's actual
dimension. Multi-block swivel split crash needs its log if it persists.

No build or automated check was run for these changes.

## Round 2 — Tracking Sinks, Flywheel Sweep, Re-anchor Rollback

### Findings from the first runtime test

Wheels fixed. Ropes still fully invisible, Flywheel parts (swivel bearing,
throttle lever, torsion springs) still missing, springs still camera-dependent,
assembler "too far from ground" unchanged, and the rehome pose re-anchor made
freshly assembled ships VANISH (regression).

### Causes and changes

- **Tracking-based packet sinks sent to nobody** (`IplHostingChunkTrackersMixin`,
  new): mods address per-chunk packet sinks through `ChunkMap.getPlayers`
  (NeoForge `PacketDistributor.trackingChunk`, Veil's
  `VeilPacketManager.tracking(be)` — which carries Simulated's rope strand
  point snapshots — Create chunk syncs). Hosting plot chunks have no vanilla
  trackers, so those packets were dropped: rope strands never received points
  (fully invisible regardless of light), spring/BE live state never synced.
  Plot-chunk `getPlayers` on the hosting level now resolves to the owning
  sub-level's tracking players — the block-event broadcast fix generalized one
  layer lower.
- **Flywheel reroute never fired** for Sable-managed plot chunks (custom chunk
  pipeline bypasses the vanilla `LevelChunk` add path; parent can also sync
  after the BE arrives). Added a safety-net sweep from the hosting container's
  client tick (`IplClientFlywheelReroute.sweepHostedContainer`, wired in
  `SableAltContainerTickMixin`): any hosted plot BE not yet queued is
  registered into its parent's visualization world; already-queued BEs cost a
  weak-map hit.
- **Rehome pose re-anchor rolled back to a diagnostic log** (`SableRehomeOps`):
  the active mutation was unsafe against live enrollment/upload ordering.
  `[IPL-REHOME-POSE]` now logs position, rotation point, both plot anchors and
  the new-slot self CoM per rehome — one runtime trace pins the size-scaled
  assembly offset exactly.
- **Interaction arming instrumented** (`IplHostedInteractionContextMixin`):
  throttled `[IPL-USE] armed ...` / `... did NOT resolve` logs show whether the
  assembler's click-time ground check actually runs armed — if the miss log
  fires (or neither fires) on an assembler click, the disassembly entry point
  bypasses `useItemOn` and needs its own arming site.

### Files

- `src/main/java/ipl/sable/mixin/IplHostingChunkTrackersMixin.java` (new)
- `src/main/java/ipl/sable/client/IplClientFlywheelReroute.java`
- `src/main/java/ipl/sable/mixin/client/SableAltContainerTickMixin.java`
- `src/main/java/ipl/sable/transit/SableRehomeOps.java`
- `src/main/java/ipl/sable/mixin/IplHostedInteractionContextMixin.java`
- `src/main/resources/ipl_sable.mixins.json`

### Verification Needed

Ropes visible with live physics points; torsion springs / swivel bearing /
throttle lever rendered on hosted ships; springs stable from all angles;
assembling a structure no longer vanishes it (offset may be back — capture
`[IPL-REHOME-POSE]` lines); on an assembler click capture `[IPL-USE]` lines.

No build or automated check was run for these changes.

## Hosted Level Identity For Third-Party Mods

### Symptom

With the mod loaded, every mod built for Sable sub-levels misbehaved on hosted
ships: Offroad wheels did not move or find ground, Simulated ropes rendered
invisible, springs appeared only from some camera positions, Flywheel-visualized
block-entity parts (swivel bearing, throttle lever) were missing, the physics
assembler spawned assembled ships too high (0.5 blocks for a single block,
scaling with size) and disassembled into the wrong dimension, and sub-levels
ignored ambient occlusion and lighting.

### Cause

One root: rehoming plots into `ipl_sable:sublevels` splits the Level-object
identity that stock Sable guarantees. In stock Sable a ship's plot chunks live
in the SAME level the ship occupies, so `be.getLevel()`, `Minecraft.level`, the
container level and the interaction level are one object. Hosted, a plot BE's
level is the void hosting dimension, and every mod keys something off the
difference:

- `LevelRenderer.getLightColor(be.getLevel(), worldPos)` sampled the void at
  world-frame positions → light 0 → world-frame BE geometry (rope strands)
  rendered black.
- `WorldAttached` registries split buckets: rope strands registered under the
  hosting level, zipline and render-side lookups searched the parent's.
- `SubLevelContainer.getContainer(minecraft.level)` vs
  `getContainer(be.getLevel())` resolved different containers.
- Flywheel visuals registered with the hosting level's visualization world,
  which never renders; renderers meanwhile skip their vanilla path when
  `VisualizationManager.supportsVisualization(be.getLevel())`.
- Client BE ticks on the hosting level probed world-frame terrain
  (wheel suspension `clip`, `getSignal`) in the void.
- Server interaction handlers (assembler `placeIntoWorld`) ran against
  `this.getLevel()` = the void: ground/build-height checks and the disassembly
  target were wrong.
- The hosting dimension type declared `ambient_light: 1.0`, flattening all
  baked sub-level lighting to fullbright (no AO variation).

### Implemented Change

Client — restore the stock identity invariant:

- `BlockEntity.getLevel()` on a hosted plot BE returns the owning sub-level's
  PARENT `ClientLevel` (`IplHostedBeLevelIdentityMixin` +
  `IplClientBeIdentity`). Getter only; the `level` field, chunk storage,
  ticking and packet application stay on the hosting level. Plot-coordinate
  block reads from the parent already resolve through Sable's client
  chunk-cache mixins + the plot bridge.
- Plot light bridge (`IplPlotLightBridgeMixin`): plot-range positions on a
  non-hosting `ClientLevel` read the hosting level's light engine, so the BE
  dispatcher's `getLightColor(parent, plotPos)` returns the plot-local light
  stock Sable would have had.
- Client world-frame router + arming (`IplHostedClientWorldFrameRouterMixin`,
  `IplClientWorldFrameContext`, client half of `IplHostedBeTickContextMixin`
  via `IplClientBeTickArming`): hosted plot BE client ticks read world-frame
  terrain (suspension probes, redstone) from the ship's parent dimension.
  Read-only surface.
- Flywheel visual reroute (`IplHostedFlywheelVisualRerouteMixin`,
  `IplClientFlywheelReroute`): BEs entering/leaving hosting-level plot chunks
  are queued into the PARENT level's `VisualizationManager` through Flywheel's
  public API. Sable's own `BlockEntityStorageMixin` then embeds them
  per-sub-level with `renderPose()` transforms in the world that actually
  renders. Parent flips re-home the visuals (`IplParentDimSync`).

Server — complete the contextual router:

- `IplHostedWorldFrameRouterMixin` now also routes `getChunk` (both overloads,
  chunk-grid gate |chunk| >= 62,500) and `getEntities` (both overloads, AABB
  center gate). Build-height accessors are deliberately NOT routed: chunk
  section indexing chains through the level height profile, and the routed
  per-position `setBlock` already enforces the parent's real limits.
- Interaction arming (`IplHostedInteractionContextMixin`): block-use on a
  hosted ship (plot-range hit position) arms the world-frame context for the
  click, so synchronous handlers — assembler assemble/disassemble, swivel
  bearing split — route their world-frame access structurally.

Assembly offset:

- `SableRehomeOps.rehome` re-anchors the hosted twin's pose after the block
  copy: `rotationPoint := new-slot self center-of-mass`, `position := the world
  position that material point had under the source mapping`. The fresh
  `MergedMassTracker`'s first `uploadData()` null-baselines `lastCenterOfMass`
  and jumps `rotationPoint` WITHOUT position compensation; any skew between the
  verbatim-copied rotation point (translated across plot slots) and the
  recomputed center of mass displaced the ship by exactly that delta — +0.5 on
  a fresh single-block assembly, scaling with ship size. The re-anchor makes
  the world mapping identical by construction and turns the first upload into
  a no-op. A one-line INFO log reports any nonzero correction.

Lighting:

- `dimension_type/sublevels.json`: `ambient_light` 1.0 → 0.0 (skylight stays
  on). Plot sections bake real skylight + block light again — ambient
  occlusion and light gradients return to stock appearance.

Removed point patches (superseded by the structural routing above):

- `IplAssemblerDisassemblyParentMixin` — covered by interaction arming + the
  router's `getChunk`/set-block routing. Its portal-restore piece (a ship-borne
  portal FEATURE hook, not a bug patch) moved to
  `IplDisassemblyPortalRestoreMixin` at Simulated's single disassembly
  chokepoint.
- `IplWheelMountLevelFilterMixin` + `IplWheelMountInvoker` — under the fused
  step each container's wheels are queued by its own actor pass and drained by
  its own event in the same substep; the global-drain filter is unnecessary,
  and `applyBatchedForces` applies through the sub-level's own handle with no
  world reads.

### Files

- `src/main/java/ipl/sable/client/IplClientBeIdentity.java` (new)
- `src/main/java/ipl/sable/client/IplClientBeTickArming.java` (new)
- `src/main/java/ipl/sable/client/IplClientFlywheelReroute.java` (new)
- `src/main/java/ipl/sable/dim/IplClientWorldFrameContext.java` (new)
- `src/main/java/ipl/sable/mixin/client/IplHostedBeLevelIdentityMixin.java` (new)
- `src/main/java/ipl/sable/mixin/client/IplPlotLightBridgeMixin.java` (new)
- `src/main/java/ipl/sable/mixin/client/IplHostedClientWorldFrameRouterMixin.java` (new)
- `src/main/java/ipl/sable/mixin/client/IplHostedFlywheelVisualRerouteMixin.java` (new)
- `src/main/java/ipl/sable/mixin/IplHostedInteractionContextMixin.java` (new)
- `src/main/java/ipl/sable/mixin/IplDisassemblyPortalRestoreMixin.java` (new)
- `src/main/java/ipl/sable/mixin/IplHostedWorldFrameRouterMixin.java`
- `src/main/java/ipl/sable/mixin/IplHostedBeTickContextMixin.java`
- `src/main/java/ipl/sable/dim/IplWorldFrameContext.java`
- `src/main/java/ipl/sable/transit/SableRehomeOps.java`
- `src/main/java/ipl/sable/client/IplParentDimSync.java`
- `src/main/resources/data/ipl_sable/dimension_type/sublevels.json`
- `src/main/resources/ipl_sable.mixins.json`
- deleted: `IplAssemblerDisassemblyParentMixin.java`,
  `IplWheelMountLevelFilterMixin.java`, `IplWheelMountInvoker.java`

### Verification Needed

Runtime-check on a hosted ship: rope strands visible and correctly lit in the
parent dimension; springs visible from all camera angles between ship and
ground and between two ships; swivel bearing and throttle lever fully rendered
(Flywheel backend on AND off); Offroad wheels find ground, spin and steer;
assembling a single block yields no vertical offset (watch for the
`[IPL-REHOME] pose re-anchor` log line — a nonzero correction confirms the
fixed skew); assembler disassembly places blocks in the ship's dimension with
correct ground/height checks; ship-borne portal frames still restore on
disassembly; sub-level lighting shows AO and light gradients; cross-portal
transit keeps ropes/springs/visuals alive after the parent flip; dedicated
server boots (client classes untouched server-side).

No build or automated check was run for these changes.

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

## Nested Portal Aperture Culling

### Symptom

While rendering through portal B, portal A in B's destination world could render over
B even when A was fully behind B's destination clip plane and therefore could not be
physically visible to the player.

### Cause

`portal_area` lost its slot-0 clipping injection after it was removed from the shader
transformation list. Its stencil mask was drawn without the active destination plane,
so the occlusion query could start a recursive render for portal A. Portal collection
also performed the behind-plane check too late, after other per-portal work.

### Implemented Change

- `portal_area.vsh` now writes `gl_ClipDistance[0]` using
  `iportal_ClippingEquation`; its JSON declares the uniform.
- `MixinShaderInstance` adopts that JSON uniform after shader locations are loaded.
- `PortalRenderer` rejects portals fully behind the active clip plane while building
  the render candidate list, before frustum tests, stencil drawing, occlusion queries,
  or recursive world rendering.
- A `-0.01` tolerance preserves portals that are coplanar with the destination plane.

### Files

- `src/main/java/qouteall/imm_ptl/core/render/renderer/PortalRenderer.java`
- `src/main/java/qouteall/imm_ptl/core/mixin/client/render/shader/MixinShaderInstance.java`
- `src/main/resources/assets/immersive_portals/shaders/core/portal_area.vsh`
- `src/main/resources/assets/immersive_portals/shaders/core/portal_area.json`

`jarJar` build completed successfully.

## Sodium Portal Context Cache

Sodium portal renders now reuse a bounded cache of per-dimension and portal-path
contexts. The cache retains Sodium's visible render lists and camera history instead
of rebuilding them from an empty context every portal frame.

The return path no longer schedules an unnecessary Sodium terrain update, and portal
rendering restores the previous `smartCull` value instead of always enabling it.

### Files

- `src/main/java/qouteall/imm_ptl/core/render/MyGameRenderer.java`
- `src/main/java/qouteall/imm_ptl/core/compat/sodium_compatibility/SodiumInterface.java`

`jarJar` build completed successfully.

## Sodium Same-Dimension Portal Stability

Sodium's per-region draw-command batches are now isolated by IP portal depth,
matching its per-depth visible `ChunkRenderList` state. The portal-depth batch
storage is sparse; clear and deletion paths skip empty depth slots.

When Sodium resets a portal `ChunkRenderList`, the matching batch slot is found
by list identity rather than the currently active portal depth. A shallower list
can be reset while a deeper portal render is active, and clearing by active
depth would otherwise invalidate the wrong commands.

Sodium rendering contexts retain and swap `lastCameraPos`. A same-dimension
context switch therefore does not appear to Sodium as a camera movement and does
not trigger unnecessary terrain updates. New contexts start with the active
portal camera position.

The Sodium 0.8.12 viewport hook now uses `isBoxVisible` and `testSection`,
converting the section center into padded section bounds before IP frustum
culling.

### Files

- `src/main/java/qouteall/imm_ptl/core/compat/mixin/sodium/MixinSodiumRenderRegion.java`
- `src/main/java/qouteall/imm_ptl/core/compat/mixin/sodium/MixinSodiumChunkRenderList.java`
- `src/main/java/qouteall/imm_ptl/core/compat/sodium_compatibility/IESodiumRenderRegion.java`
- `src/main/java/qouteall/imm_ptl/core/compat/mixin/sodium/IESodiumWorldRenderer.java`
- `src/main/java/qouteall/imm_ptl/core/compat/mixin/sodium/MixinSodiumViewport.java`
- `src/main/java/qouteall/imm_ptl/core/compat/sodium_compatibility/SodiumInterface.java`
- `src/main/java/qouteall/imm_ptl/core/compat/sodium_compatibility/SodiumRenderingContext.java`
- `src/main/java/qouteall/imm_ptl/core/render/MyGameRenderer.java`

No build or automated check was run for these changes.

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

### Follow-up: Nested Render Cache Invalidation

The handoff now maps all client pose state before exposing the destination
parent. This prevents a render pass from seeing destination ownership paired
with a source-frame interpolation timeline.

It also invalidates every active `IplStraddleRenderCache` pass after the parent
switch. IP can perform nested portal renders; invalidating only the innermost
pass allowed an enclosing pass to reuse a source-frame projection after the
same handoff. The next lookup rebuilds hosted and projection lists from the
destination parent, avoiding a stale projection or double-draw at portal exit.

### Files

- `src/main/java/ipl/sable/client/IplParentDimSync.java`
  Commits mapped client state before parent visibility and clears active render
  caches after handoff.
- `src/main/java/ipl/sable/client/IplStraddleRenderCache.java`
  Invalidates all nested render-pass cache entries.

### Follow-up: Remove One-Tick Render Hold

The previous exact-pose bridge held the construction still until the next
`ClientSubLevel.tick`. That is a visible one-tick pause after native destination
rendering takes over, especially at speed. It is removed.

The handoff already maps every buffered snapshot, running snapshot, logical
pose, and last pose. It now only invalidates Sable's per-partial-tick cache.
Sable immediately rebuilds from two identical mapped handoff endpoints, then
continues normally from destination snapshots on the following tick.

### Files

- `src/main/java/ipl/sable/client/IplParentDimSync.java`
  Invalidates cached pose without holding native movement.
- `src/main/java/ipl/sable/mixin/client/IplClientSubLevelPoseOverrideMixin.java`
  Removes handoff-only pose override.

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

## Staff Drag Through Same-Dimension Portals

### Symptom

Dragging a held construction into a same-dimension portal did not work at all.
The body could suddenly fly toward the raw look point, lose its held rotation,
or be teleported by an unwanted transit mid-drag. The expected behavior is
that a held construction simply slides into the portal, keeps its rotation,
can go fully in, and can be pulled fully back out, with no parent transition
while the staff holds it.

### Cause

Two independent failures:

- The server goal mapper selected the constraint's coordinate frame from a
  sticky player-eye distance heuristic (`playerUsesSourceFrame`). Mid-crossing,
  when eye-to-body distances were comparable, the flag flipped arbitrarily and
  the PD constraint received a goal teleported through the portal — the
  "flying" yank. Same-dimension portals have both apertures in one `Level`, so
  the dimension-key frame identity used for cross-dimension grabs does not
  exist.
- The transit controller's majority rehome fired at 0.6 crossed fraction
  regardless of an active staff session. The rehome mapped the native pose
  through the portal and reframed the held orientation mid-drag, producing the
  visible "sub-level transition" jump and rotation change.

### Implemented Change

- `IplStaffPortalDragState` was rewritten around per-tick cursor geometry.
  For a same-dimension straddle session, both candidate goals are computed —
  the raw player-frame point and the same point inverse-mapped through the
  session portal — and the one nearer the NATIVE body wins, with a 1.5-block
  sticky margin. While pushing straight through, the raw goal already is the
  correct native goal (the point continues behind the plane), so the constraint
  stays continuous; the mapped candidate wins only when the player physically
  works the body from the exit side. The held orientation is never touched for
  same-dimension portals; the cross-dimension reframe path is unchanged.
- `SableTransitController` freezes same-dimension transit for staff-held
  bodies (`isHeldByStaff`): the majority rehome is skipped, and a fully-crossed
  held body keeps its straddle session alive (image colliders, clip seam,
  projection) instead of transiting, so it can be parked beyond the plane or
  pulled back out. Normal transit rules resume the tick after release.
  Cross-dimension transit while held is deliberately unchanged.
- The client mouse-rotation axis mirrors the same chooser
  (`IplStraddleStaffPick.sameDimCursorMapped`) and maps the pitch axis through
  the portal rotation only for rotated same-dimension pairs; translation-only
  pairs are untouched.

### Files

- `src/main/java/ipl/sable/transit/IplStaffPortalDragState.java`
  Geometric goal chooser; same-dimension orientation never reframed.
- `src/main/java/ipl/sable/transit/SableTransitController.java`
  Staff freeze: no same-dimension rehome/transit while held; fully-through
  held bodies keep their session.
- `src/main/java/ipl/sable/client/IplStraddleStaffPick.java`
  Client-side chooser for the mouse rotation axis.

## Staff Beam Portal Rendering Rewrite

### Symptom

The staff beam flickered when the held construction entered or exited a
portal, and disappeared entirely when the player was not looking at the
portal, even though the drag stayed active. When the grab anchor was already
through the portal, the beam did not continue to the exiting part of the
construction.

### Cause

- Beam endpoints were resolved per pass from state that changes exactly at
  session boundaries (drag-target capture, session-store snapshots, transit
  frames); a transiently unresolvable route blanked the whole beam for a
  frame — the flicker.
- The per-pass segment selection required IP's active portal render pass, so
  the beam existed only while its portal was on screen. The near segment
  (staff to aperture) lives in the player's world and needs no portal pass at
  all; a same-dimension far segment lives in the same world too.
- Segment extraction used `Portal.rayTrace` exclusively. When the beam line
  missed the aperture quad (anchor deep beyond the portal, staff swung wide),
  the raytrace returned null and the entire beam was dropped.

### Implemented Change

New `IplStaffBeamRoutes` owns beam geometry; the renderer only selects
segments per pass:

- One route per beam per frame from stable inputs: fresh staff tip (sampled on
  the main pass only, cached for portal passes — recomputing it in a portal
  pass mixes the virtual camera with root-world state, the old pivot bug), the
  body's render pose, and the live session portal.
- The beam targets the anchor's IMAGE exactly when the native anchor is past
  the portal plane in the crossing direction — the same seam the render clip
  uses, so the endpoint always lands on visible geometry and the route switch
  at the plane is continuous.
- Same-dimension sessions compare the direct line against the through-aperture
  split (forward through the session portal, backward through its reverse) and
  draw the shorter physical path, so the beam threads the portal from either
  side.
- Aperture points fall back to the line-plane intersection CLAMPED into the
  aperture rectangle when the exact raytrace misses: the beam can bend at the
  portal edge but can never vanish.
- The main pass draws every segment located in the main world — the near
  segment renders regardless of portal visibility, and a same-dimension far
  segment renders at the exit aperture. Portal passes draw the segment whose
  traversed-portal prefix matches IP's render path by UUID (a session-store
  surrogate portal still matches its live entity).
- Routes are retained for 15 ticks across transient resolution gaps (session
  snapshot latency, portal entity not yet synced) while the drag is active.

### Files

- `src/main/java/ipl/sable/client/IplStaffBeamRoutes.java`
  Route building, light-path selection, aperture clamp, retention.
- `src/main/java/ipl/sable/client/IplStaffPortalBeamRenderer.java`
  Per-pass segment emission; main-pass staff-tip sampling.
- `src/main/java/ipl/sable/mixin/client/IplStaffBeamMixin.java`
  Noise windowing adapted to the new segment type.
- `src/main/java/ipl/sable/client/IplStraddleStaffPick.java`
  Beam geometry removed; exposes pick/transit state to the route builder.

### Verification Needed

Runtime-check with a same-dimension portal pair: drag a construction into the
portal from the source side (it must slide in holding rotation, no transition,
no flying), push it fully in, pull it fully back out, walk to the exit side
and pull it the rest of the way, and release it half-through (normal transit
must resume). Verify the beam: staff-to-aperture piece visible while looking
away from the portal, continuous staff-aperture-exit-anchor path when the
anchor is through, no flicker at session start/end, and correct behavior for
a rotated portal pair.

No build or automated check was run for these changes.

## Staff Drag/Beam Round 2 — Precision, Pin, Remote Grabs

### Symptoms

- Held point imprecise; the beam target flickered between "through the portal to
  the part inside" and "directly to the sub-level".
- A construction pushed fully into a same-dimension portal, when pulled back,
  drifted into the backward/opposite plane of a bidirectional portal instead of
  coming back out of the portal it went in through.
- Grabbing the already-emerged (out) half could start pulling it back inside.
- Grabbing a construction in another dimension (standing in the overworld,
  reaching through a portal) made it vanish in that dimension until the player
  followed, and the hold distance snapped to maximum.

### Causes

- Hold distance came from Simulated's `logicalPose` (the body's NATIVE pose):
  wrong coordinate space for a cross-dimension grab (distance huge, clamped to
  max), and the wrong location for a same-dimension image grab (held point off,
  pulling the out half back in).
- The beam picked direct-vs-through by comparing path lengths each frame; near
  the crossover the shorter path flipped, switching the drawn target.
- The staff freeze kept the same-dimension session alive but did not stop OTHER
  portal faces from opening their own sessions. A fully-inserted body straddled
  the opposite/reverse face, that face's session mapped the goal, and the body
  was pulled into the backward plane.
- A pure remote grab (body fully in another dimension, no straddle session) was
  never frame-mapped on either side, so the raw player-frame goal was applied in
  the wrong dimension and the body flew away.

### Implemented Change

- The physical pick now records the true VISIBLE ray length to the grabbed point
  (summed across portal frames; rigid mappings preserve length) and a TAIL hook
  on `startDraggingSubLevel` overwrites the session's hold distance with it. Plain
  same-world grabs keep stock behavior.
- The same-dimension beam route is now deterministic: it ends at the visible
  representation of the anchor (image when past the portal plane, native
  otherwise) and takes the physically correct path for the player's own side of
  the portal (direct, through the held portal, or through its reverse). No
  per-frame length comparison; switches occur only at plane crossings where the
  representations coincide.
- Held bodies are pinned to exactly one same-dimension portal for the whole grab
  (`IplStaffPortalDragState.HELD_PORTAL`, latched by the transit controller on
  first straddle). The transit controller refuses to open or keep a session on
  any other face while held, and the goal mapper maps only through the pinned
  portal — so pulling a fully-inserted body back out returns it through the
  entrance, never the backward plane.
- Pure remote cross-frame grabs are mapped CLIENT-side through the captured pick
  portal chain in `mapOutgoingDragGoal` (server passes them through untouched);
  straddle and post-transit grabs remain server-mapped. The two cases are
  disjoint, so there is no double transform.

### Files

- `src/main/java/ipl/sable/client/IplStraddleStaffPick.java`
  Visible pick distance, grab-distance store/apply, deterministic client axis
  chooser, pure-remote outgoing goal mapping.
- `src/main/java/ipl/sable/client/IplStaffBeamRoutes.java`
  Deterministic same-dimension route (player-side + plane test); length compare
  removed.
- `src/main/java/ipl/sable/transit/IplStaffPortalDragState.java`
  Held-portal pin store; goal mapper prefers the pinned portal.
- `src/main/java/ipl/sable/transit/SableTransitController.java`
  Pin gate: only the pinned portal keeps a session while held.
- `src/main/java/ipl/sable/mixin/client/IplStaffPickMixin.java`
  TAIL hook applying the visible grab distance.

### Verification Needed

Same-dimension bidirectional portal: push a construction fully in, pull it back
out — it must exit through the entrance plane, not the opposite one. Grab the
emerged half — it must not slide back in. Cross-dimension: grab a construction
through a portal from another dimension — hold distance must match where it was
grabbed (not max), it must stay put and visible, and follow the cursor in its
own dimension. Beam target must not flip between through-portal and direct.

No build or automated check was run for these changes.

## Staff Aim Follows The Beam

### Symptom

The held Creative Physics Staff pointed at the grabbed joint's native world
position. For a joint reached through a portal (cross-dimension grab, or a
same-dimension construction held inside the portal), that position is elsewhere,
so the staff aimed at empty space and looked wrong.

### Implemented Change

`PhysicsStaffItemRenderer` computes its barrel aim from
`renderPose().transformPosition(dragLocalAnchor)`. `IplStaffItemAimMixin` wraps
that single call and returns the beam's first endpoint instead — the entrance
aperture when the beam threads a portal, or the joint itself for a direct grab
(identical to stock). The staff now looks into the portal exactly where the beam
leaves, which also makes cross-dimension holds read correctly.

### Files

- `src/main/java/ipl/sable/mixin/client/IplStaffItemAimMixin.java`
- `src/main/java/ipl/sable/client/IplStaffBeamRoutes.java` (staffAimPoint helper)
- `src/main/resources/ipl_sable.mixins.json`

No build or automated check was run for this change.

## Staff Round 3 — Beam Length, Player Crossings, Face Pin (Client)

- Beam node density: the overwritten beam render now feeds `PhysicsBeam.length`
  the true physical route length each draw (stock did this from its endpoints;
  a cross-dimension grab seeded it from cross-frame garbage, wrong beam look).
- Walking through portals while holding: the server drag frame now tracks the
  PLAYER's dimension hops. Each hop latches the nearest reverse portal in the
  new level; the chain composes for repeated crossings and pops when walking
  back. Cursor goals map through the chain to the body's parent frame — fixes
  carrying a plain local grab through a portal (no straddle session to infer
  from) and repeated in/out crossings. Falls back to the session-based branches
  when the chain is empty or broken.
- Beam/aim face pin: the client latches ONE session portal per grabbed body
  (mirror of the server held-portal pin). resolvePortal's first-of-several race
  could hand a coincident reverse face, flipping the plane test so the beam
  pointed at the EXIT while the body was held inside the entrance.

Files: `IplStaffBeamMixin`, `IplStaffPortalDragState`, `IplStaffBeamRoutes`.
True multi-aperture recursion (body imaged through several apertures at once)
is NOT implemented — needs recursive image sessions first.

No build or automated check was run.

## Staff Drag/Beam Rewrite — Event-Sourced Grab Chain

### Symptoms

- Cross-dimension grabs drew a beam with the wrong node density: correct start,
  correct end, wrong segment count.
- Walking through a portal while holding flung the constraint goal for a tick
  (worse for same-dimension portals, whose hops were never detected at all).
- After dragging a construction fully through an entrance portal a few times, it
  could never enter the paired exit portal — or any other portal — for the rest
  of the grab.
- Sequential multi-portal recursion (fully through one portal, then another,
  then all the way back) was structurally impossible while held.
- The beam sometimes pointed at the exit-side image while the construction was
  clearly held inside the entrance.

### Cause

Grab frame state was scattered across independent heuristics that each guessed
the answer from geometry snapshots: a distance-to-box goal chooser with sticky
margins, a client-side session-portal latch racing `resolvePortal` order, a
held-portal pin that was never released when the body left its aperture, a
single-transit record that a second transit overwrote, nearest-portal-within-24-
blocks inference after a dimension change (blind to same-dimension hops), and a
staff freeze that suppressed same-dimension transit entirely — parking bodies in
a state the rest of the pipeline never expected. Beam node density was seeded
from `distanceSquaredWithSubLevels` across frames (garbage for a through-portal
grab) and only corrected during render passes that actually drew a segment.

### Implemented Change

One concept replaces all of it: the **grab chain** — an ordered list of exact
portal-isometry snapshots (`IplGrabLink`) from the dragging player's eye to the
grabbed body's parent frame. It is event-sourced, never inferred:

- **Grab start** seeds it from the pick raycast's exact portal path (client
  sends identities; the server re-snapshots geometry itself). A click that
  landed on a straddle IMAGE — resolved by testing both concrete candidate
  positions against the actual ray, same-dimension included — seeds the session
  portal's inverse link, so grabbing the emerged half never pulls it back in.
- **Player teleports** prepend the crossed portal's inverse. The hook is IP's
  own teleportation manager (server and client), which knows the exact portal —
  same-dimension hops included. The stored player-relative cursor vector is
  rotated through the portal at the same moment, so the folded goal is
  continuous through the hop by construction; TCP ordering covers the packets.
- **Body transits** append the crossed portal forward and reframe the held
  orientation through its rotation — same-dimension transits included. A
  revision/ack protocol premultiplies in-flight packet orientations until the
  client processes the ordered rebase RPC, so a server-initiated flip can never
  be stomped by a stale packet.
- Adjacent links composing to the identity annihilate, so dragging or walking
  back pops the chain. Sequential recursion at any depth is plain list algebra.

The PD goal is a pure fold of the absolute cursor point through the chain.
Straddle sessions never affect the goal — a straddling body's native pose is
authoritative and the folded goal simply continues past the plane on image
colliders. The staff freeze and held-portal pin are deleted: held bodies ride
the standard declarative straddle/minority-face/majority-rehome pipeline, and
because the goal, the held orientation, and the chain all rebase through the
same portal in the same tick, the constraint error is invariant across a flip —
transit while held is yank-free, and entering any subsequent portal works
because nothing is pinned.

The client mirrors the chain (server snapshots broadcast for every dragging
player, plus zero-latency local prediction from the client teleport hook and
the pick seed). The beam route is derived from that chain plus one refinement:
when the grabbed anchor is past a live session portal's plane, the route gains
that session link and targets the image — the beam threads the aperture to the
exiting part, continuously, because native and image coincide exactly at the
plane. The mouse-rotation axis uses the same route (axis = M⁻¹·R_route·a, with
M the image rotation), so goal, beam, aim, and rotation share one frame source.
Beam node density is fed the true physical route length at creation (pick-ray
length), every client tick, and every render pass.

### Files

- `src/main/java/ipl/sable/transit/IplGrabLink.java` (new)
  Exact portal-isometry snapshot links: forward/inverse capture, fold,
  identity annihilation, wire encoding.
- `src/main/java/ipl/sable/transit/IplGrabChain.java` (new)
  Server-authoritative chains: seed/teleport/transit events, goal fold,
  orientation ack protocol, snapshot broadcast. Replaces
  `IplStaffPortalDragState` (deleted).
- `src/main/java/ipl/sable/client/IplGrabChainClient.java` (new)
  Client mirror: snapshots, local prediction, rebase handler + ack.
- `src/main/java/ipl/sable/client/IplStaffBeamRoutes.java`
  Chain-driven routes with the straddle image refinement; SESSION_PIN latch,
  length comparisons, and transit-frame plumbing deleted; aperture clamp,
  segment fractions, and retention kept; route-length feed for node density.
- `src/main/java/ipl/sable/client/IplStraddleStaffPick.java`
  Pick/capture only: geometric native-vs-image candidate resolution, chain
  seeding, pick-distance store; all frame heuristics deleted.
- `src/main/java/ipl/sable/transit/SableTransitController.java`
  Staff freeze and held-portal pin removed; grab-chain rebase wired at transit.
- `src/main/java/ipl/sable/mixin/IplStaffDragSessionOverwriteMixin.java`
  Goal fold at the constraint; relative-goal rotation duck; chain cleanup when
  Simulated removes a session without stopDragging.
- `src/main/java/ipl/sable/mixin/IplStaffServerStateMixin.java`
  Chain begin/end hooks; unacked-transit orientation compensation.
- `src/main/java/ipl/sable/mixin/client/IplStaffPickMixin.java`
  True beam length at creation; beam-owner binding.
- `src/main/java/ipl/sable/mixin/client/IplStaffBeamMixin.java`
  Per-tick true-length feed; noise rotation through link rotations.
- `src/main/java/ipl/sable/client/IplStaffPortalBeamRenderer.java`
  Segment dim/prefix matching against the new route shape.
- `src/main/java/ipl/sable/client/IplParentDimSync.java`
  Staff handoff plumbing removed (rebase RPC owns it).
- `src/main/java/qouteall/imm_ptl/core/teleportation/ServerTeleportationManager.java`
- `src/main/java/qouteall/imm_ptl/core/teleportation/ClientTeleportationManager.java`
  Exact-portal player-crossing hooks.
- `src/main/java/ipl/sable/duck/IplStaffDragSessionControl.java`
- `src/main/java/ipl/sable/mixin/client/IplStaffOutgoingDragMixin.java` (deleted;
  stock raw packets are correct — the server owns all frame mapping)
- `src/main/resources/ipl_sable.mixins.json`

### Verification Needed

Runtime-check with a same-dimension pair and a cross-dimension pair: grab from
another dimension (hold distance and beam node density correct); walk through
the portal while holding, both directions, repeatedly (no fling, beam stable);
drag fully through the entrance several times, then through the exit portal and
other portals (no dead portals); sequential recursion through two-plus portals
and all the way back; hold the joint inside the aperture from both sides (beam
threads the portal to the exiting part, never flips to the wrong face); rotate
a held body through a rotated pair from both sides.

No build or automated check was run for these changes.
