# Portal-aware sub-level interpolation

How a hosted Sable sub-level crosses an Immersive Portals portal without the interpolation
giving the trick away. Written for 0.5.2; supersedes the interpolation sections of
`EDITS.md` revisions 6-8.

## TL;DR (RU)

- Кадр (frame) всегда ОДИН: тот, в котором клиент рисует прямо сейчас.
- Пока handoff в очереди, приходящие снапшоты из destination-кадра отображаются НАЗАД в
  текущий кадр. Тело летит сквозь дверь и перелетает её ровно так же, как на сервере.
- В момент коммита вся сохранённая история отображается ВПЕРЁД через ту же изометрию.
  Изометрия коммутирует с lerp/slerp, поэтому кривая не меняется - меняется только запись.
- Тиков по-прежнему 6. Ничего не добавлено, ничего не выброшено, окно
  `backTick - 6` не тронуто. Формат пакетов не менялся вообще.
- `buffer.clear()` + два синтетических снапшота вырезаны: именно они и давали «заморозку
  на двери, потом рывок».

## The problem

The server rehomes a body the moment its collider passes the portal plane
(`SableRehomeOps.executeHostedTransit`). Sable's client renders `backTick = gameTick - 6`,
so for about six ticks:

- the visible body is still on the source side, and
- every snapshot arriving from the server is already in destination coordinates.

`SubLevelSnapshotInterpolator.getSampleAt` lerps `before.pose -> after.pose` without asking
which chart each pose belongs to. Feeding it one of each makes it draw the body travelling
the full distance between the paired portals - the flicker across the whole transition.

## The model: one canonical frame

There is exactly one canonical frame at any instant: **the frame the client is currently
drawing in**. A crossing is a change of chart, not a change of motion.

1. **Queued (crossing in flight).** `IplPendingHandoffSnapshotMixin` intercepts
   `ClientSableInterpolationState.receiveSnapshot` and rewrites the incoming pose through
   `IplParentDimSync.mapPendingDestinationSnapshotBack`, i.e. through `P^-1` for every queued
   handoff, newest first (`descendingIterator`, so an N-portal chain composes in the right
   order). The buffer therefore stays homogeneous, and the body keeps flying its real
   trajectory - including the overshoot past the doorway.
2. **Commit.** `IplParentDimSync.applyHandoff` maps the retained timeline FORWARD through
   `P`: every buffered snapshot, the interpolator's `runningSnapshot`, `lastPose` and
   `logicalPose`. Then ownership flips and cached render state is invalidated.

### Why mapping the history is exact

`P(x) = R * s * (x - origin) + destination` with `R` a rotation and `s > 0` a uniform scale,
acting on the left. Therefore

```
P(lerp(a, b, u))    == lerp(P(a), P(b), u)
P(slerp(qa, qb, u)) == slerp(P(qa), P(qb), u)      (orientation: q -> R*q)
```

So the sampled curve after mapping is the *same curve*, merely written in the destination
chart. This is why nothing about the timing has to change: no snapshot is added or removed,
no `gameTick` is edited, `tick(backTick)`'s `bufferStartTime = (int)(backTick - 6)` window is
untouched, and dead reckoning (`beforeBefore -> before` extrapolation) keeps working across
the crossing.

### Why the old commit path could not be repaired in place

0.5.1 cleared the buffer and reseeded two synthetic snapshots (a doorway pose and the rehome
pose). Three independent failures:

- `getSampleAt` needs a snapshot at or before `backTick`. After a clear, the oldest snapshot
  is the crossing tick, ~6 ticks in the render clock's future, so `before == null` for the
  whole window: the body **froze on the doorway** and then jerked to the rehome point. This
  was the remaining visible artefact, and it was structural.
- Dead reckoning needs `beforeBefore`; a two-entry buffer cannot provide it, so one dropped
  packet right after a crossing stalled the body exactly where motion is most conspicuous.
- The synthetic doorway pose had to be recomputed client-side
  (`PortalMapping.projectOntoExitPlane`, `EXIT_PLANE_CLEARANCE`), duplicating
  `PortalCrossingDetector.projectOntoExitPlane` on the server. Two implementations of one
  clamp is one too many - the client copy is deleted.

## Answer to "can the overshoot look perfect?"

Yes, and it does not need a seam node, a `flipTick`, a crossing fraction `u*`, or an
`exitPlanePose` on the wire.

The reason is that after the map-back, the segment that straddles the crossing is a single
straight interpolation of two poses in one frame, and the portal plane cuts it at the same
parameter value on both sides:

- In the source world, the source-half clip discards everything past the plane, so the body
  is seen sinking into the doorway.
- In the destination world, the same segment's image is drawn and clipped complementarily, so
  the body emerges from the paired portal at the matching depth in the same frame.
- The two halves meet at the plane by construction (they are the same segment, and `P` maps
  the entrance rectangle onto the exit rectangle), so there is no gap and no double image at
  any partial tick.

What remains theoretically imperfect - and is not worth wire changes:

- **Sub-tick dynamics.** Between two snapshots the body is a straight lerp; if it accelerates
  hard mid-tick the server's real path and the drawn path differ slightly. This is true away
  from portals too, so it cannot look wrong *because of* the portal.
- **Two doorways in one tick** (very fast body, portals closer than one tick of travel). The
  chain still composes correctly, but the middle portal is crossed inside a single lerp, so
  its exit is not separately witnessed.
- **Scaled portals** (`s != 1`) stay geometrically exact, but a body's apparent speed changes
  discontinuously at the plane. That is inherent to a scaling portal, not an artefact.

## Stability work in this change

- **Forced commit.** A queued handoff is applied unconditionally after `HANDOFF_FORCE_MS =
  1000` (~20 ticks, well past the 6-tick window) if the client-side exit proof never arrives
  (portal entity out of render range, sweep recorded against another portal, ...). The server
  has already moved the body; staying in the source frame forever is the worse failure.
- **Queue expiry.** `awaitingClientAllocation` entries now expire after 30s, the same horizon
  `PENDING_PARENT_STAMPS` uses. Previously they leaked for the session and kept mapping every
  later snapshot back through a portal nobody crossed.
- **Vanished body.** A forced handoff whose client sub-level no longer exists is dropped
  (with a warning) instead of being retried forever.
- **Locking.** Leaving the map-back chain and rebasing the timeline happen together under
  `synchronized (interpolator.buffer)`, the same monitor `receiveSnapshot` and the map-back
  use, so no snapshot can be expressed for a frame the buffer has already left.
- **Aliasing.** Poses are stored in the buffer *by reference*, so the rebase mutates in place
  and uses an identity set: the same `Pose3d` instance can be reachable twice (buffered and
  as `runningSnapshot`), and mapping it twice would put it a full portal offset away.

## Dead code removed

`client/IplPortalTransitVisual.java` (439 lines: `applyFlip`, `rebaseBuffer`,
`rebaseIncomingSnapshot`, `openWindow`/`window`/`fullyPast`, `shipsProjectingInto`,
`record Crossing(int flipTick, Pose3d exitPlanePose, Pose3d finalPose)`, `record ExitWindow`)
was an earlier epoch-based design, entirely unreferenced - it is not a mixin and needs no
`ipl_sable.mixins.json` change. Its `Crossing`/`ExitWindow` records were the seam-node idea
the model above makes unnecessary.

`PortalMapping.projectOntoExitPlane` and `EXIT_PLANE_CLEARANCE` are removed as a second
source of truth for the exit point (see above).

## One-block sub-levels: the eye-space cut

The crooked, camera-dependent triangular cut on one-block bodies is **not** a 0.5.1
regression, and `IplHostedSingleBlockChunkRenderMixin` **is** required:

- `SableSourceClipMixin` existed in 0.5.0 and clips `renderChunkedSubLevel` only.
- `VanillaSingleSubLevelRenderData.renderSingleBlock` bakes `modelView * transform` into the
  vertices, so its positions are in EYE space, while IP's slot-0 clipping equation is in
  camera-relative WORLD space. `n_world . (R_view * p) == (R_view^T * n_world) . p`, so the
  effective cut normal rotates with the camera - a wrongly angled plane through a single cube
  is exactly a triangular corner slice that changes as you turn.
- 0.5.1 only made it visible, by keeping the destination half drawn during a crossing.

Routing hosted bodies onto the chunked path is a supported Sable configuration
(`SableTags.ALWAYS_CHUNK_RENDERING` does the same thing per block) and is stable for a body's
lifetime, so it cannot thrash `resize()`'s
`renderData instanceof VanillaSingleSubLevelRenderData ^ isSingleBlock(subLevel)` reallocation.

Because that injector is declared `require = 0`, a Sable rename would silently bring the bug
back. `IplHostedRenderRouting` now records that the hook fired and warns once from the client
heartbeat if hosted bodies exist and it never did; `-Dipl.sable.forceChunkedForHosted=false`
restores Sable's own routing without a rebuild.

## Known follow-ups (deliberately not in this change)

- `PortalCrossingDetector.EXIT_LATCH_MAX_TICKS = 3` contradicts its own javadoc; decide which
  is right and align them.
- The portal plate is only rebuilt when it fails `plateIsRectangleOf` within
  `PLATE_IDENTITY_TOLERANCE = 1e-3`. For a moving or ship-mounted portal that is not enough;
  it should be rebuilt per tick from the live portal.
- `touchesPortalVirtually` is not consulted by `IplStraddleStaffPick`, `IplGrabChainClient`,
  `IplStaffBeamRoutes` or `PhysicsStaffSubLevelObserver`, so those four still use plain
  source-frame geometry near a portal.
- `SubLevelSnapshotInterpolator.tick`/`getSampleAt` are not synchronized on `buffer` in Sable
  itself. This change locks every access it makes, but a same-tick race between Sable's own
  tick and a network-thread receive remains possible in principle. A `@WrapMethod`
  synchronization wrapper was rejected for now because MixinExtras availability in this
  toolchain is unverified.
- **This branch has not been compiled.** Same caveat as `EDITS.md` revision 8 ("Сборка не
  запускалась"): mixin targets and Sable/companion signatures are matched by reading the
  vendored 2.0.3 sources on `delete_later`, not by a build.
