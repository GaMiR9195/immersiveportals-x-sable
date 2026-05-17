# Sub-level transit through portals — design doc

This is the living design doc for IP + Sable sub-level transit (airships moving
through IP portals). Updated as research progresses through Phase 0.

## Decisions locked in

- **Phasing**: Phase 0 (research, this doc) → Phase 1 (atomic teleport MVP) → Phase 2
  (mirror without straddling) → Phase 3 (true straddling) → Phase 4 (polish).
- **Identity**: mirror gets a new UUID with an association field
  (`mirrorOf=<sourceUuid>`) — keeps Sable's per-dim tracking clean.
- **Passengers**: defer mounted-passenger handling (Create Aero seats) to a later
  phase. v1 only handles entities standing on deck.
- **Block changes mid-transit**: snapshot at mirror-spawn time, disallow source-side
  changes from propagating. Live sync is a later phase.
- **Render culling**: when an airship straddles a portal plane, each dim should only
  render its "valid side." Sable client renderer mixin adds a clip plane.

## Open questions by area

Each area has a "Findings" section (what we've learned) and an "Open" section (what
we still need to answer before we can implement).

---

## Area A — Sable sub-level lifecycle

### Findings (session 1, b7d454f-era code review of `sable-src/common/`)

**Key APIs on `SubLevelContainer` (`api/sublevel/SubLevelContainer.java`):**

- `allocateSubLevel(UUID uuid, int x, int z, Pose3d pose)` — creates a sub-level at
  specific plot coords with a specific UUID. **This is exactly the API we need for
  spawning a mirror.** Throws `IllegalArgumentException("Plot already exists")` if
  the plot is occupied; throws `IllegalArgumentException("Plot coordinates out of
  bounds")` if outside the plotgrid.
- `allocateNewSubLevel(Pose3d pose)` — picks the first empty plot and generates a
  new UUID. We'd use `allocateSubLevel` directly for mirrors to control UUID.
- `removeSubLevel(SubLevel, SubLevelRemovalReason)` — for cleanup. Two reasons:
  `REMOVED` (clears occupancy) and others (keep occupancy bit set — for unload paths).
- `getSubLevel(UUID)` — UUID lookup.
- `tick()` — iterates `allSubLevels.forEach(SubLevel::tick)` then
  `processSubLevelRemovals()` then `observers.forEach(o -> o.tick(this))`.
- **Observer pattern**: `addObserver(SubLevelObserver)` — observers fire on
  `onSubLevelAdded`, `onSubLevelRemoved`, and per-tick.

**Allocation flow**:
1. `allocateSubLevel(uuid, x, z, pose)` checks bounds + empty plot
2. Calls abstract `createSubLevel(globalPlotX, globalPlotZ, pose, uuid)` — impl in
   `ServerSubLevelContainer` or `ClientSubLevelContainer`
3. Adds to `subLevels[]` array + `allSubLevels` list + `subLevelsByUUID` map
4. Sets occupancy bit
5. Fires `observer.onSubLevelAdded(subLevel)` — **this is how physics auto-enrolls**
6. Marks `SubLevelOccupancySavedData.setDirty()` if on a `ServerLevel` (persists)

**Removal flow**:
1. Fires `observer.onSubLevelRemoved(subLevel, reason)` — **this is how physics
   auto-removes**
2. Calls `subLevel.onRemove()` (cleans up plot)
3. Clears the array slot, list entry, UUID map entry
4. Clears occupancy if reason was `REMOVED`

**Persistence**: `SubLevelOccupancySavedData` and `SubLevelSerializer` handle save/load.
For mirrors, we'd want to mark them as non-persistent — they should be transient.
Need to find the API surface for that.

**`SubLevelAssemblyHelper.assembleBlocks`** (`api/SubLevelAssemblyHelper.java:68`) is
the canonical "create a sub-level from world blocks" path used by Aero contraption
assembly. Reference for what setup steps a properly-initialized sub-level needs:
- Allocate via `container.allocateNewSubLevel(pose)`
- Create center chunk: `plot.newEmptyChunk(plot.getCenterChunk())`
- Move blocks via `moveBlocks(level, transform, blocks)`
- `pipeline.teleport(subLevel, position, orientation)` to set initial physics pose
- Tracking points, block entities, hanging entities handled separately

**For our mirror creation**, the major difference is: blocks aren't being moved from
world; they're being *copied* from another plot. That copy operation is not yet
identified — need to look at `LevelPlot` and `PlotChunkHolder` next session.

### Open (Area A)

- **How do we copy blocks from source plot to mirror plot?** No helper found yet.
  Likely need to: iterate `PlotChunkHolder`s in source plot → for each chunk, iterate
  block positions → write to mirror plot via some `setBlock` API. Mirror plot needs
  the corresponding chunks pre-allocated. Block entities need NBT copy.
- **How do we mark a mirror as non-persistent** (won't be saved/restored on world
  reload)? Need to investigate `SubLevelOccupancySavedData` and `SubLevelSerializer`.
- **`mirrorOf` field**: where do we store it? `ServerSubLevel.userDataTag` is a
  `CompoundTag` that's serialized — could use that, but we'd want it ignored at
  load time for the mirror itself. Maybe we just keep a server-side `Map<UUID, UUID>`
  outside Sable.
- **Does `markRemoved()` on a sub-level cleanly cascade through observers**, including
  Sable's physics + tracking systems? (Probably yes per the observer pattern, but
  verify there's no special "save before removal" path we'd hit.)

---

## Area B — Sable physics pipeline + kinematic mode

### Findings

**`PhysicsPipeline` interface** (`api/physics/PhysicsPipeline.java`):

- `add(ServerSubLevel, Pose3dc)` — registers a sub-level as a dynamic body
- `remove(ServerSubLevel)` — opt out of physics
- `add(KinematicContraption)` / `remove(KinematicContraption)` — separate API for
  pose-driven bodies (more on this below)
- `readPose(ServerSubLevel, Pose3d dest)` — pipeline writes the sub-level's current
  pose into dest
- `teleport(subLevel, position, orientation)` — sets the pose externally (used
  during initial setup or NaN-recovery)

**`KinematicContraption` interface** (`api/sublevel/KinematicContraption.java`):
*Not* what it sounds like at first read. It's an interface for "contraptions attached
to a plot" — secondary contraptions that piggyback on a sub-level's plot for lift/drag
calculations. Methods: `sable$getPosition`, `sable$getOrientation`, `sable$blockGetter`,
`sable$getMassTracker`, `sable$liftProviders`. Used in
`ServerSubLevel.prePhysicsTick` at line 347: `for (KinematicContraption contraption :
plot.getContraptions())`. Probably for child contraptions attached to a parent
airship. **Not directly usable as the "kinematic mirror" abstraction we want.**

**`SubLevelPhysicsSystem`** (`sublevel/system/SubLevelPhysicsSystem.java`):

- Implements `SubLevelObserver`. Auto-enrolls server sub-levels on add, auto-removes
  on removal:
  ```java
  @Override public void onSubLevelAdded(SubLevel subLevel) {
      if (subLevel instanceof ServerSubLevel s) this.pipeline.add(s, s.logicalPose());
  }
  @Override public void onSubLevelRemoved(SubLevel subLevel, ...) {
      if (subLevel instanceof ServerSubLevel s) this.pipeline.remove(s);
  }
  ```
- `getPipeline()` exposes the pipeline (line 411).
- Physics flow per tick (`tickPipelinePhysics`):
  1. `pipeline.prePhysicsTicks()`
  2. For each substep (multiple per tick):
     - `prePhysicsTickBegin()` on each sub-level
     - `updateMergedMassData()` on each
     - `prePhysicsTick()` on each (applies lift/drag/floating-block forces)
     - `pipeline.physicsTick(substepTimeStep)` — actual sim step
     - `processSubLevelRemovals` (handles fragile-block break)
     - **`updateAllPoses(container)`** — pulls poses from pipeline back to sub-levels
  3. `pipeline.postPhysicsTicks()`

- `updateAllPoses` iterates ALL non-removed sub-levels and calls `updatePose(s)` which
  reads `pipeline.readPose(s, storage)` and writes to `s.logicalPose()`. **This is
  where physics overwrites a sub-level's pose.**
- NaN-detection at line 299-311 — if `readPose` returns NaN, `recoverSubLevel` fires
  which re-adds the body to the pipeline. **So we can't just `pipeline.remove` and
  expect the sub-level to stay un-tracked — the recovery path would re-enroll it.**

**Kinematic mode strategy options:**

| Option | Approach | Mixin surface | Pros | Cons |
|---|---|---|---|---|
| A | Mixin `onSubLevelAdded` to skip pipeline.add for mirrors | 1 mixin on `SubLevelPhysicsSystem` | Clean, mirror never enrolls | Need mirror flag visible to mixin |
| B | Mixin `updateAllPoses` to skip mirrors during readback | 1 mixin on `SubLevelPhysicsSystem` | Lets physics run (wasted CPU) but we authoritatively set pose | Same as A but worse: simulation runs for no reason |
| C | After alloc, `pipeline.remove(mirror)`, then intercept `recoverSubLevel` to NOT re-enroll | 2 mixins | More precise control | More moving parts |
| D | Set mirror's pose AFTER updateAllPoses each tick (overwrite physics) | 1 mixin on tick TAIL | Simplest code | Physics still runs, mass/forces wasted |

**Recommended**: Option A. Pre-empt enrollment. Cleanest separation.

**Flag mechanism**: duck interface on `ServerSubLevel`. Mixin adds a unique field +
accessor (e.g., `ipl$isKinematicMirror() : boolean`). Our `onSubLevelAdded` skip
condition reads it.

### Open (Area B)

- **What does `pipeline.readPose` return if a body isn't in the pipeline?** Need to
  read `Sable.createPhysicsPipeline` impl (probably JOLT or RapierJVM backend).
  Affects whether Option A works as expected vs. needing Option B too.
- **For the mirror, do we still want `prePhysicsTick` to run?** It generates lift/drag
  forces that the pipeline would apply. If the mirror isn't in the pipeline, those
  forces have no target — wasted work but harmless. To save CPU, skip
  `prePhysicsTick` for mirrors too.
- **What about `updateMergedMassData` / `prePhysicsTickBegin`?** Same as above.
  Cheapest path: skip the whole physics substep iteration for mirrors. Means mixin
  on `tickPipelinePhysics` to filter `container.getAllSubLevels()` for mirror exclusion.
- **What about block-change physics signals** like `pipeline.handleChunkSectionAddition`
  triggered when we *copy* blocks to the mirror plot? Would those try to add chunks
  to the physics pipeline for a mirror that isn't enrolled? May error or silently
  succeed-with-no-effect.
- **Mass tracker for mirror**: do we need to build it? `ServerSubLevel.buildMassTracker`
  exists. For a kinematic mirror, mass doesn't matter for simulation, but it might
  matter for things like player collision raycast weight, block-break-physics, etc.
  Safest to build it the same way as source (it's pure block data → mass).

---

## What the v1 spawn-mirror procedure looks like

Putting Area A+B together, here's the rough flow for **spawning a kinematic mirror in
the destination dim**:

```java
// In dest dim (the dim we're spawning the mirror INTO):
ServerSubLevelContainer destContainer = SubLevelContainer.getContainer(destLevel);

// 1. Pick a free plot in dest container (or compute deterministic plot from sourceUuid)
Vector2i plotPos = destContainer.getFirstEmptyPlot();  // (need to expose this; currently private)

// 2. Compute mirror pose: portal-mapped from source.logicalPose()
Pose3d mirrorPose = portalTransform.applyToPose(sourceSubLevel.logicalPose());

// 3. Allocate. (The IPL mirror-skip mixin on SubLevelPhysicsSystem.onSubLevelAdded
//    inspects a flag on the new sub-level. We need to set the flag BEFORE
//    allocateSubLevel returns, but allocateSubLevel fires the observer
//    immediately on add. So either:
//    (a) pre-register the UUID as "incoming mirror" in a Map, mixin checks Map; OR
//    (b) intercept createSubLevel and set the flag on the just-created instance
//        before observer.onSubLevelAdded fires.)
ipl$mirrorRegistry.markIncoming(mirrorUuid, sourceUuid);
ServerSubLevel mirror = (ServerSubLevel) destContainer.allocateSubLevel(
    mirrorUuid, plotPos.x, plotPos.y, mirrorPose
);
// At this point onSubLevelAdded has fired; our mixin saw the UUID in the
// registry and skipped pipeline.add. Mirror is allocated but not in physics.

// 4. Copy blocks from source plot → mirror plot.
//    [API TBD — see Area A "Open"]
SableBlockCopy.copyPlotBlocks(sourceSubLevel.getPlot(), mirror.getPlot());

// 5. Build mass tracker (for collision queries; not used by sim since not enrolled).
mirror.buildMassTracker();

// 6. Mark as transient (don't persist).
//    [API TBD — see Area A "Open"]
ipl$markTransient(mirror);

// 7. Wire it up to receive pose updates from source each server tick.
//    [Done in a per-tick hook on SubLevelTrackingSystem or similar]
sourceMirrorRegistry.put(sourceUuid, mirror.getUniqueId());

return mirror;
```

Per-tick mirror pose sync (server-side, in source dim's tick):

```java
ServerSubLevel source = ...;
ServerSubLevel mirror = lookupMirror(source);
if (mirror != null && !mirror.isRemoved()) {
    Pose3d mirrorPose = portalTransform.applyToPose(source.logicalPose());
    mirror.logicalPose().set(mirrorPose);
    // The mirror's tick() will pick up the new pose for bounding box update.
    // The mirror is NOT in the physics pipeline, so updateAllPoses doesn't
    // overwrite this.
}
```

This pose change in the mirror will be observed by Sable's `SubLevelTrackingSystem`
in the dest dim (which runs as a per-dim observer), which will broadcast bounds +
movement updates to mirror-tracking players in the normal flow. No special networking
required.

## Mixin surface estimate (Area A+B coverage only)

So far, we need:

1. **`SubLevelPhysicsSystem.onSubLevelAdded`** — pre-empt enrollment for mirror UUIDs.
2. **`SubLevelPhysicsSystem.tickPipelinePhysics` or `updateAllPoses`** — skip mirror
   sub-levels during pose readback (depends on Option A vs B above).
3. **`ServerSubLevel`** — add duck-interface field for "is kinematic mirror" flag.
4. **`ServerSubLevel.tick()` or `prePhysicsTick`** — possibly skip force application
   for kinematic mirrors (CPU saving).
5. **Persistence hook (TBD)** — skip mirror serialization.

Plus the not-yet-researched:

6. Per-tick mirror pose driver (probably a new server-side helper class, not a mixin —
   could hook into our existing `SableCrossDimTrackingMixin.tick HEAD`).
7. Block-copy helper (probably not a mixin, just a utility class using Sable's APIs).
8. Lifecycle triggers (when to spawn / despawn / hand off authority — needs IP
   portal awareness — Area D research).
9. Sable client renderer clip plane (Area F research).
10. Aero integration (Area C research).

## Session 1 takeaways

The good news: **Sable's lifecycle and physics APIs are clean.** We can spawn a
sub-level with arbitrary UUID, opt it out of physics with a single mixin, and drive
its pose externally — exactly the substrate the mirror approach needs.

The not-fully-resolved questions: how to copy blocks plot-to-plot (probably tractable
via the existing `PlotChunkHolder` API; need to look at `LevelPlot` next), and how
to skip persistence. Both fit cleanly into "small additional helper classes" rather
than "new mixin surface."

Next session: **Area C — Create Aero contraption integration.** What does Aero
expect about the sub-level it's attached to, and can a mirror sub-level coexist with
Aero's controller-block / contraption identity model.

---

## Area C — Create Aero contraption integration

### Findings (session 2, monorepo at `simulated-src/`)

The repo cloned is the Aeronautics + Simulated monorepo. Contains two mods:
- `aeronautics/` — `dev.eriksonn.aeronautics.*` (the user-facing airship blocks,
  balloons, propellers, levitite, etc.)
- `simulated/` — `dev.simulated_team.simulated.*` (lower-level framework/utils used
  by Aero)

35 Aero source files import from `dev.ryanhcode.sable`. Aero is fully built on top
of Sable as its substrate.

**Aero's "airship" has no separate identity from a Sable sub-level.** No
contraption UUID, no controller-block-as-identity. The sub-level UUID *is* the
airship identity. Aero attaches state via per-level maps and per-block-position
metadata. Specifically:

- **Balloons** (hot-air structures with a controller block + airtight enclosure):
  - Stored in `BalloonMap.MAP.get(level)` — keyed by controller `BlockPos`.
  - Each `Balloon` has a `controllerPos` (a block in the airship), a `graph` of
    enclosed cells, a set of `heaters` (block entities providing lifting gas).
  - Lifecycle observed via `BalloonMap.BalloonSubLevelObserver`, registered as a
    `SubLevelObserver` on the container (`AeronauticsCommonEvents.onSubLevelContainerReady`).
- **Lift providers**: blocks implementing `BlockSubLevelLiftProvider`. Per-block,
  scanned by Sable each physics tick from `plot.getLiftProviders()`. Pure block-
  level — no separate state.
- **Levitite crystallizers**: `LevititeCrystallizerManager`. Per-level state about
  crystal propagation. Not tied to a sub-level per se.

**The auto-cascade flow is the load-bearing insight for us:**

Sable's `SableCommonEvents.handleBlockChange(level, chunk, x, y, z, oldState, newState)`
fires on every block change in any Sable-tracked level, **including plot-chunk
changes**. The handler:
1. Updates the plot chunk's mass/heatmap/floating-block state.
2. Aero's `SableCommonEventsMixin` injects at HEAD and calls
   `AeronauticsCommonEvents.onBlockModifiedEvent` → `BalloonMap.updateNearbyBalloons`.

So **if we write blocks into the mirror's plot via Sable's normal block-setting API,
Aero's Balloon (and Sable's mass/heatmap/floating-block) state updates automatically
on the dest side.** No need to manually wire up Aero state.

**Aero's hooks into `SubLevelAssemblyHelper`** (Aero's
`SubLevelAssemblyHelperMixin`) only fire during the canonical assembly flow
(blocks-in-world → sub-level). They're irrelevant for mirror creation because we're
copying plot → plot, not world → plot. We must NOT call `assembleBlocks` for the
mirror — we need a different path that goes directly through chunk block sets.

### Implications for the mirror approach

**Aero does not block our design.** Each dim's Aero state attaches independently to
its sub-level via block-change events. Source and mirror each get their own
balloon, their own lift-provider scan, their own heatmap. They are "the same
airship" only at our (IPL's) layer — Aero treats them as two unrelated airships
sharing identical block layouts.

For Phase 1 (atomic teleport, no straddling): trivially fine. Destroy source →
its balloon gets GC'd via removal observer. Create dest sub-level → blocks copy
→ Aero's `updateNearbyBalloons` fires → dest balloon appears. No state transfer
needed (the dest balloon will rebuild from controller block + airtight cells in
the new plot).

For Phase 2 (mirror without straddling): source remains authoritative, mirror is
kinematic. Both sides have their own balloon. Source balloon runs heat/gas
simulation and feeds the physics pipeline (which moves the source). Mirror balloon
*also* runs heat/gas (because Aero auto-attaches), but the forces have nowhere to
apply (mirror not enrolled in physics pipeline) — wasted CPU but no
correctness concern. At handoff, the source is destroyed and the mirror takes
over physics enrollment.

For Phase 3 (straddling): same as Phase 2 with longer-lived mirror. Aero's
internal state on the dest side may drift slightly from source (different heat
exchange timings, etc.) but as long as we don't tell the dest balloon to apply
physics forces, the discrepancy is purely cosmetic.

### Aero-specific risks for transit

1. **`ServerBalloon` does its own physics tick** (`AeronauticsCommonEvents.physicsTick
   → BalloonMap.physicsTick(level, timeStep)`). This computes heat/gas state per
   balloon. On the mirror side, this work is wasted but presumably harmless.
   *Mitigation*: optionally skip dest-side balloon physicsTick when the balloon's
   sub-level is marked as a kinematic mirror. Probably defer to phase 4 polish.

2. **`LevititeCrystallizerManager`** is per-level. If an airship has crystallizers,
   we'd need to handle their state separately. Niche case; defer.

3. **Aero block-entity tickers**: many Aero block entities tick state (heaters,
   propellers, bearings). On the mirror side, these would tick if the chunks are
   loaded. Same wasted-work concern. Probably want chunk-level "this is a mirror,
   don't tick" guard for v2 polish.

4. **Inter-block-entity references**: Aero has things like
   `HotAirBurner → BalloonController` relationships established at runtime
   (e.g., the burner registers itself with the balloon when first ticked). When we
   copy blocks, those runtime references won't be wired. The auto-cascade
   via block-change events would re-wire them as long as the references rebuild
   from block placement (which they appear to, per `checkHeaters` in `Balloon.java`).
   Worth verifying empirically once we have a v1 build.

5. **Network sync to client**: Aero sends balloon state to clients via its own
   packets (separate from Sable's sub-level packets). For a mirror that doesn't
   exist on the client until tracking starts, this should work naturally —
   when the player crosses into the dest dim, Sable's start-tracking flow fires,
   and Aero's per-block-entity sync delivers heater/balloon state via block-entity
   updates.

### Open (Area C)

- **Does `chunk.setBlockState(...)` on a plot chunk fire `SableCommonEvents.handleBlockChange`?**
  Sable's `SableCommonEvents.handleBlockChange` is called from somewhere — presumably
  a Sable mixin on `LevelChunk.setBlockState` or similar. Need to verify so we can
  trust the auto-cascade. (Highly likely yes given the way Aero attaches to it, but
  worth one grep.)
- **Block entity copy**: blocks copy via chunk setBlockState; block entities need
  manual `setBlockEntity(pos, BlockEntity.loadStatic(pos, state, nbt))` after the
  block is placed. Aero block entities likely have NBT serialization for their
  internal state — should "just work" as long as we save+restore NBT through the
  copy.
- **Plot chunk creation pattern**: how do new plot chunks get created in Sable
  when blocks are placed via API? Does Sable auto-create chunks on demand for plot
  block changes, or do we need to pre-allocate via `plot.newEmptyChunk(...)`?
  (`SubLevelAssemblyHelper` calls `plot.newEmptyChunk(plot.getCenterChunk())` only
  once at start; doesn't seem to do it during each block copy.)

### Session 2 takeaways

**Aero is a non-issue for our design.** It auto-attaches via block-change events and
sub-level observers, so creating a mirror sub-level with copied blocks gets
Aero's balloon system rebuilt for free on the dest side. No identity conflict.
No manual state transfer needed. The only concern is wasted CPU on the mirror
side (running balloon heat simulation, block entity ticks) which is a polish
issue, not a correctness one.

This is great news for the mirror approach. The next session (Area D + E — IP
machinery) will tell us how to hook portal detection and clip-plane rendering;
between those and what we have now we'll have everything needed for a Phase 1
MVP design.

Next session: **Area D — IP entity teleport and coord mapping; Area E — IP's
portal-view chunk clipping** (mostly internal to our own repo).
