# Changelog

All notable changes to this project will be documented in this file, per sometimes.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project tries to adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.6.0]

### Material-sweep fixes (same version)

- Preserve non-empty portal pieces outside a finite aperture; retire only genuinely empty images.
- Observe actual physics substeps; remove the 32-block teleport cutoff and tick-ahead ownership prediction.
- Order sibling/nested material crossings chronologically, with a per-voxel crossing cursor.
- Share label-time finite apertures with rendering and native ABI 6 contact classification.
- Fix source-ground contact witnesses, exact voxel-boundary ownership, warm-start leakage and sleeping contact caches.
- Apply client changes at the rendered pose time, not the logical interpolation endpoint; retain per-body packet order without cross-body head-of-line blocking.
- Add headless material, extreme-speed and real source/image-manifold regressions.
- See `docs/fixes-0.6.0-material-sweep.md` for test status and remaining limits.

Rewrite of the foundation: the `ipl_sable:sublevels` hosting dimension is gone. Sub-levels
live natively in exactly one level (stock Sable model); portals are handled by Atlas
projections. See `README060NEW.md`.

### Changed

- Straddling no longer clones or synchronizes bodies. The far side is a **projection**: an
  image collider of the SAME rigid body registered in the destination level's Atlas chart
  (`image pose = portal isometry × body pose`), plus the same render data drawn at the
  mapped pose. One body, one source of truth; forces on the image act on the real body.
- **Pieces instead of clip volumes.** Which material went through which portal is now a
  property of the material: every plot voxel near a portal carries a piece label
  (`IplPieceMap`, one byte per voxel). `IplPieceTracker` follows every aperture near every piece
  at observed physics-substep boundaries: a voxel whose centre passes the rectangle front to back
  joins the session's piece, back to front returns to the parent, and material that passes the
  portal plane beside the frame keeps its piece. Between samples, finite observed sweeps carry
  centre ownership; only actually touching voxels are split by the plane. Natives, entity
  collision, raycasts, staff, outlines and rendering all use this material rule.
- Sessions are keyed by the portal path (`StraddleKey`), one piece each. Chains and branches:
  a piece crossing a portal of its destination level opens a child session with the composite
  isometry; a piece may have several child portals and a body several root portals, each piece
  cut by its own edges. The old depth-16 and 64-session caps are removed; the byte format
  still limits a body to 255 non-native piece IDs. Opposite entrance faces may own distinct
  sibling pieces, while duplicate same-normal faces of the same mapping are canonicalized.
- A session opens on an observed finite crossing or actual geometric contact with the aperture.
  Non-empty pieces retain material history outside the aperture; no detached timeout moves their
  blocks back into the source terrain. Empty sessions without a bridge retire at tick tail.
  A body flips through exactly one portal once its native piece is empty and the root session
  with the most material is through its aperture; other root branches follow it into the new
  frame. Promoted child sessions keep their labels; up to 16 flips are processed per tick.
- Translucent blocks of a piece blend back to front: RANGES and SPLIT draws of a sorted
  translucent layer follow the quad order of its index buffer, recorded at every upload and
  resort (`VertexBufferMixin`, `IplSectionRanges.quadOrder`).
- Rendering of bodies crossing portals (`IplPieceRender`): every draw is one piece; per section
  and layer SKIP, FULL (one plain draw, the common case), RANGES (one multi-draw over the quads of
  the piece's blocks, recorded per block at section compile by `IplSectionRanges`) or SPLIT (zone
  blocks drawn by both pieces of an edge, each cut by the aperture plane). Plans are cached per
  buffer and label revision. Block entities draw in one pass for the piece plus one plane-cut
  pass per edge; Flywheel keeps instancing everything but the block entities that are not wholly
  native, which go to BER one by one.
- Entity collision clips per piece (`PieceCut`): a frame only collides with its piece's blocks,
  zone blocks are trimmed at the aperture plane (step-up included), and the frame of an entity is
  chosen by the nearest piece material. Raycasts walk only the piece's blocks; entity lookups only
  touch pieces whose sections overlap the query box.
- Clients keep sessions and labels per coordinate frame: payloads of a frame not entered yet wait
  and replace the old frame at entry, so nothing blinks at a flip. Transit ghosts draw their
  successor's pieces re-rooted through the crossing portal. Label payloads are partial (changed
  sections only) and split into packets of at most 64 sections.
- Natives ABI 3: piece labels and aperture edges per body (`setPieceSection`, `setPieceEdges`,
  `setImageNode`, `clearPieces`); a contact survives only on the collider of the piece its point
  belongs to. With older natives or stock natives sessions open without an image collider (the
  body is still split, drawn and flipped; its far side does not collide).
- Attachment gate: all native joint connections and actor dependencies form a transit group;
  the former 16-body cap is removed and every member must be ready for the same mapping.
  World anchors and cross-dimension native-joint groups block the flip. With native ABI 4,
  eligible ropes attached inside a group have their particles reframed after a same-dimension
  flip; particles are not yet projected or clipped while straddling.
- Staff picking walks the available ray range without a three-portal depth cap and stores the
  complete image path in NBT. Invalid path seeds are rejected rather than silently mapped to
  the wrong frame. These changes require in-game validation.
- Flip: same-dimension = pose teleport of the live body + client interpolation rebase
  (`handoff`); cross-dimension = native move into the destination level, keeping the same
  global plot slot when free, with a client "ghost" until the successor is compiled (3 s
  timeout).
- Physics stepping (`atlas/IplFusedStep`): with IPSable natives one fused step per server
  tick drives every level, and levels that do not drive it update `lastPose` before the
  step; with stock natives every level steps its own world in its own tick, as in Sable.
  Ship-anchored portals follow their ships after the step in both modes.
- Plot allocation uses the union of all levels' plots, so plot-local coordinates stay unique
  across dimensions. Plot reads fall back to a foreign level's plot only when its owner is
  projected into the querying level. Server writes into a foreign level's plot run in the
  owner level (neighbour updates, light, block entities, chunk tracking); client writes are
  rejected.
- Physics staff, picks and outlines route to the owner level of the projected sub-level.
- Cross-dimension tracking follows what IP streams to the player: any loaded chunk under the
  ship's world bounds, from any level and through any portal, or proximity to its image.
  Sub-level packets are dimension-tagged; players who see another level and the singleplayer
  host get movement over TCP.
- Client: the sub-level containers of all IP client levels tick once per client tick, at the
  point where Sable ticks the current level, and not while paused.
- One snapshot timeline: every dimension stamps pose snapshots, clock packets and
  start-tracking with the same server-wide tick number (`IplTimeline`) instead of a per-level
  counter. The client runs one interpolation clock for all levels (`IplSharedClock`,
  `ClientSubLevelContainerMixin`), advanced once per client tick. Clock packets of all levels
  are merged (`SnapshotInfoPacketMixin`): the clock runs while any level reports motion, stops
  only when all have stopped, restarts like Sable after every level went quiet, and resumes in
  phase when it stopped at most 3 ticks ago.
- Create: Pocket Sized: its client scale handshake covers the sub-levels of all IP client
  levels (`PocketScaleHandshakeMixin`), and a scale sync that repeats the scale the client
  already holds no longer resets the sub-level's interpolation (`PocketScaleStateMixin`).
  Its frustum filter for sub-level draws tests the drawn box (the mapped pose for a
  projection) against the frustum of the current pass (`PocketRenderFrameMixin`).
- The ghost of a cross-dimension move follows its successor's pose mapped back through the
  portal.
- Custom natives: loader picks
  `sable_rapier_<x86_64|aarch64>_<windows.dll|linux.so|macos.dylib>` like stock Sable, falls
  back to stock natives (single INFO line) when missing; kill switch
  `-Dipl.sable.customNatives=false`. New `natives/build-linux.sh` (Linux/macOS); any
  `sable_rapier_*` library at the project root is bundled into the jar.
- Natives are packed like Sable's: one `natives_ipl/sable_rapier_binaries.zip.l4z` in the
  jar, the platform's library extracted to `.sable/natives-ipl/`.
- Release jars bundle natives for Windows, Linux and macOS (x86_64 and aarch64), built by
  the GitHub release workflow (`natives/build-cross.sh`).
- Much less console spam: per-event logs moved to DEBUG; per-draw diagnostics only with
  `-Dipl.portalDiagnostics=true`. `-Dipl.perfDiagnostics=true` also times the client's piece
  section draws (`client-piece-draw`).
- Code cleanup: redundant `require = 0` (the config default) and member-level
  `remap = false` dropped, imports instead of fully qualified names, accessors instead of
  reflection (`StaffActionPacketAccessor`, `SectionOcclusionGraphAccessor`),
  `catch (Throwable)` narrowed to `RuntimeException` and kept only at native, optional-mod,
  persisted-data and per-tick isolation boundaries, no blanket try/catch around RPC
  handlers. `IplClipUniformPatcher.patchForSubLevel` enables the sub-level clip slots itself.
  Comments describe current behavior. Moved, merged and removed classes:
  `docs/renames-0.6.0.md`.

### Removed

- `ipl_sable:sublevels` dimension, its datapack, hosting/clone/pin/UUID-bridge code and ~70
  related classes/mixins.
- `IplDimSwitchGuard`: display-pose smoothing, skylight hold and `[IPL-DIM]` logs after a
  dimension switch. It masked the cross-dimension stall that is now fixed at its cause.
- Rope portal seam (`IplRopePortalSeam`, `RopeObjectAccessor`, properties
  `ipl.sable.ropePortalSeam` and `ipl.sable.ropeBreakStretch`): ropes hold the flip, so the
  seam never ran.
- No-op portal rim stubs (`IplPortalRimManager`, `IplStraddlePortalRim`), unused
  `IplSubLevelUniformRegistry`, unused native declarations, `IplTransitOps.executeTransit`
  and pass-through wrappers.
- `-Dipl.sable.clip.forceAll` and debug logging in per-draw render paths.
- The swept-volume crossing detector and everything built on it: tubes and wide half-spaces
  per session, sibling priority, client entry prediction, early-open look-ahead, two-plane
  clipping of whole bodies (`PortalCrossingDetector`, `PortalSweepMath`, `PortalAperture`,
  `PortalOrder`, `PortalSectionMath`, `IplPortalVolumeCache`, `IplClipPortalFinder`,
  `IplRenderCullMath`, `IplClientVisualTransitLatch`, `VoxelShapeMixin`), the entering-volume
  overlay (`IplEnteringVolumeVisualization`, `IplEnteringVolumeRenderer`), the commands
  `early_open_segments` and `visualize_sweep_detector`, `-Dipl.sable.clip.seamSupportMargin`, and
  the native clip regions of ABI 1 and 2 (`setClipRegions`, `setImageClipRegions`,
  `setClipRegionsV2`, `setImageClipRegionsV2`). `tests/portal` went with them.
- `IplTrackingStamp` and the clock offset argument of the `move` RPC, clock alignment of the
  destination level and ghost extrapolation: one timeline makes them unnecessary.
- Native JNI exports Java never called (`createPortalRim`, `removePortalRim`,
  `setPortalRimTransform`, `remapRope`, `overstretchedRopes`, `setRopePortalPrefix`,
  `setBodyDormant`, `setBodyPairExclusion`, `setParentFrame`) with their scene state and the
  dispatcher and hook checks that read it; unused Rust helpers `get_rigid_body` and
  `ChartData::get_octree_chunk(_mut)`.

### Fixed

- Pieces and sweeps with several portals at once (both faces of a doorway, multiportaling).
  An edge now cuts, holds and flips only its OWN material: a parent voxel whose centre lies on
  the entry side or a child voxel on the far side (`IplPieceMap.Edge#consistency`, natives
  `IplPieceEdge::owns_voxel`). Material that reached the other side of a plane without passing
  its rectangle (around the frame, through the opposite face) is no longer split by that plane
  and no longer keeps its session alive. The server and the natives test the voxel centre
  against the plane the labels were made with (edge payload stride 24, native ABI 5; older
  natives get the old 18-double edges); clients allow the zone's motion slack, and a strict
  sibling edge wins over a loose one. Both faces of a bi-faced doorway may now carry sessions
  at the same time (`oppositeFaceActive` removed): a U-shaped body leaving through the front
  with one arm while the other arm enters through the back no longer hides the leaving arm
  until the exit sweep catches up, and crossings through the back face are no longer lost
  (they used to stay native behind the doorway and lie on the source ground). A voxel
  relabelled in one frame in a tick is not swept again by another aperture of that frame in
  the same tick (it would enter the back face right after coming home through the front).
- Session and label payloads carry their server tick (`t`) and the client applies payloads of
  the current frame when the shared interpolation clock shows that tick
  (`IplStraddleSessionStore.drainTimed`). A quick dip into a portal no longer removes the far
  half of the blocks still drawn in the doorway, and blocks no longer switch sides ahead of
  (or behind) the drawn pose.
- A sub-level that had left a portal kept lying on phantom ground or fell through the ground:
  a flip dropped the record of the piece data held by the natives, so the old labels kept
  clipping contacts of the real collider. The natives now get the new frame's labels right after
  the flip and lose the data of a body that changed id or scene. Sessions no longer linger for
  10 ticks after the last touch, and leftovers that went around the frame no longer keep a
  session open forever or hang at an offset from the ship.
- Reset the piece label when a block is removed, so a later placement does not inherit the removed
  block's label. Staff picking now prefers a nearer local hit over a farther projection.
- A ship moving to another dimension stopped at the exit for a tick and then jerked to catch
  up, on every portal pass; seen by players in the destination dimension and by players
  entering it. The successor now starts from the pre-step pose only when the destination
  physics still steps in this server tick (stock natives, a level that ticks later),
  otherwise from the current pose, and its previous pose is mapped through the portal as
  well.
- A ship leaving a portal into another dimension skipped ahead and then slowed down for a
  tick when its successor reached the client: Sable holds a new client object at the
  server's previous pose until its first tick and then samples its two start snapshots,
  while the transit ghost already follows that object. The successor now starts from the
  poses the ghost shows, mapped through the portal.
- A fast ship leaving a portal into another dimension was pulled ahead or hung at the exit and
  then lost pace for a few ticks, as if idling. Every client level had its own Sable
  interpolation clock with its own delay, so the ship changed time at the portal: an idle
  destination clock restarted on the successor's first packet, a running one stood at another
  phase. All levels now share one snapshot timeline and one clock (see Changed), and the
  successor also takes the ghost's older snapshots: the ship keeps its time and pace across the
  portal, whatever else moves in either dimension.
- Levels that did not drive the fused step kept `lastPose == pose` for their whole tick:
  entities on moving ships there were not carried, and start-tracking sent a frozen first
  interval.
- With stock natives only the last level's physics stepped; ships in the other dimensions
  froze.
- Dedicated servers: movement for players who see another level went over Sable's UDP path
  without the dimension tag and could land in the wrong client container after a dimension
  switch.
- Ship sections compiled during another level's portal pass took Sable's sub-level shading
  flag from the wrong level and rendered twice as dark until rebuilt (black blink when
  entering a dimension or passing a portal).
- With Create: Pocket Sized, a ship that finished leaving a portal into another dimension
  stopped in place and jerked once or twice for players in the destination dimension: the
  successor's full sync and the reply to Pocket's scale handshake each reset its
  interpolation, throwing it to its newest snapshot and holding it there until Sable's clock
  caught up.
- With Create: Pocket Sized, a ship moving in another dimension appeared further along, froze
  and then moved on, hitbox included, when the player entered that dimension: Pocket's scale
  handshake forgot the ships of every level but the current one and asked again on each
  return, and the reply reset their interpolation.
- With Create: Pocket Sized, a projection was invisible from some distances and angles: Pocket
  culled it by its source box, and after a portal pass the rest of the view by that portal's
  frustum.
- Ships scaled by Create: Pocket Sized, vanilla shaders (no Iris shader pack): the portal cuts
  of their chunk geometry were shifted by `(1 - s²)` times the camera's distance to the plane,
  because the plane normal was divided by the scale instead of multiplied; a shrunk ship's
  projection or kept half disappeared depending on where the camera stood.
- `tests/render` and `tests/gl` fixtures follow the production API again; all test suites
  pass.
- A ring pushed through a portal was cut by the portal's infinite plane: material beside the
  frame vanished behind the plane or was moved to the far side. Only material that went through
  the aperture is transferred now; the rest continues past the plane in its own level, at any
  rotation, in physics, collision, interaction and rendering.
- FPS halved while a projection entered a portal: every section of a straddling body was drawn
  once per piece with clip planes over the whole body, all its block entities and Flywheel
  visuals fell back to BER, and each render pass scanned every client entity for portals to
  predict entries. Now sections holding one piece draw as before, mixed sections draw only their
  piece's quads in one multi-draw, the plane is enabled only for blocks inside the aperture zone,
  Flywheel keeps instancing everything but the block entities at the aperture, and the client
  predicts nothing.
- Several portals at once and chains: a piece with two child portals was cut by the first only,
  coplanar siblings could disagree between physics and rendering, and chain detection on the
  whole mapped body opened spurious child sessions. Each piece now has its own edges and child
  sessions open only for material that crossed.
- After a same-dimension flip the server announced an empty session list for the old frame,
  which could blink projections; moved session portals were not re-advertised to clients.

- Uneven frame pacing (stutter at normal FPS) while a ship straddled a portal. Every Sable
  spatial query (each particle's move, entity collisions and entity lookups, raycasts) resolved
  the session portals by scanning every loaded client entity; only render passes cached that
  scan, so the client tick cost "queries × entities" for as long as a projection existed.
  Portals are now looked up by UUID in the level's entity index, and so are ship-anchored
  cluster portals and physics-staff links (they scanned every frame while a member was not
  synced). The server splice no longer copies the session set or builds a set per query, and
  skips ships without an image into the queried level; image bounds go through a one-point
  pre-reject before the 8-corner box. Surrogate portals that cannot be rebuilt in a level are
  no longer re-decoded (and their failure re-logged) on every query. `-Dipl.perfDiagnostics=true`
  logs the cost of these paths every 5 seconds.

### Known limitations

- Worlds saved with ships inside `ipl_sable:sublevels` are NOT migrated: disassemble or
  remove such ships with 0.5.x before upgrading.
- Staff picks, drag and lock interaction through long paths and loops need in-game testing.
- Self-collision through a portal is not implemented (image and body are the same Rapier
  body; Rapier filters same-body pairs).
- A body pushed into several portals completes through the one holding the most material; the
  material in the other portals moves with it into the new frame (a visible snap).
- Multiple aperture cuts intersecting one block are not fully represented by the one-plane
  renderer/entity-collision path.
- Rope particles are not projected/clipped while straddling and cross-dimension joint groups
  do not flip. Group moves are not transactional if an unexpected post-preflight failure occurs.
- Range draws use triangles, so Iris packs that tessellate terrain may not draw piece sections
  at a portal.
- The staged Windows x86_64 DLL is built from these ABI 4 sources; other platforms require
  rebuilding their native libraries. The DLL's exports and imports were checked, not run in game.
- Java type-check, Windows DLL build and focused Java/Rust tests pass; not tested in game.
  Test sessions, terrain, rotating rings, joint chains and staff loops before release.

## [0.5.3]

### Fixed

- Select CPS first/third-person focus from the physical camera mode, not IP's
  `Camera.isDetached()` entity-visibility override during portal renders.
- Use the rendered third-person CPS model tip as the beam origin. Do not substitute a
  body-space approximation when the exact held-model sample is unavailable.
- When a CPS beam approaches the back of a one-way portal entrance, route its visual
  polyline around the nearest rim before entering from the front instead of drawing
  straight through the portal's back.
- Preserve the live soft CPS drag constraint across a sub-level parent flip: map both
  joint frames and the linear-motor target through the same portal transform as the body,
  then let Simulated replace the joint at its normal next-substep lifecycle point.
- Preserve CPS target interpolation when the dragging player crosses a portal. Continue
  Simulated's next substeps from the portal-mapped pre-teleport eye position instead of
  inheriting IP's intentional old/current-position collapse.
- Use the live BeamNode count for folded beams instead of resampling with a different
  density; keep original node animation, line parameters and Catnip phase ordering.
- Render registered Catnip block-cluster and chasing-AABB selections at BOTH native
  and portal-mapped sub-level poses with complementary geometric cuts, including
  same-dimension portals. Do not pick one half by the selected block's centre.
- Flush each selection inside its own pose/clip scope using isolated phase buffers;
  restore outer clip equations/GL flags and avoid stock onRender's global buffer flush.
- Aim the Creative Physics Staff at the unfolded target image, not the near portal surface.
- Move the visual beam origin through a finite grab-chain doorway when the barrel crosses
  before the player; leave the server grab chain and motor untouched.
- Capture rendered third-person staff origins and reuse one physical beam route per frame.
- Restore missing vanilla entity/sign-text clip transformations, including Catnip's CPS
  beam/selection shaders; use eye-space cuts for Iris no-shaderpack replacements.
- Resolve late portal clipping from the current recursive path instead of a global last-plane
  cache, and synchronize Java uniforms with raw shader binds without overwriting scoped
  Sable plot-local terrain clipping.
- Pin beam seams to portal apertures; isolate beam buffers and use typed node accessors.
- Remove whole-BE center culling so a partly visible sign/chest can be clipped by geometry.

## [0.5.2]

### Fixed

- Preserve Sable snapshot timestamps and interpolation history across portal handoffs;
  rebase the running, previous and current poses without clearing or reseeding the buffer.
- Stamp each handoff with the exact tracking-tick boundary and coordinate-frame generation,
  including same-dimension loops and ordered multi-portal residual sweeps.
- Scope active/retired visual sessions to their coordinate frame; retire tails by both finite
  aperture geometry and authoritative completion tick instead of an unbounded half-space.
- Require finite trailing-edge exit, distinguish it from sideways withdrawal, and retain
  completed leading rigid members until an atomic whole-group handoff is ready.
- Keep prior-frame render samples identical across nested portal passes.
- Align Staff visual routes with delayed poses while control uses server coordinates;
  replay recorded body transits for picks made on older frames, and isolate re-grabs by seed ID.
- Clear transit/session/Staff visual state on removal and world cleanup; refresh moved portal
  surrogate payloads rather than keeping an obsolete transform under the same UUID.

The Java sources were compiled against Sable 2.0.3 and the project dependencies. Native
binaries and an in-game stress test are not included in this source release.

## [Unreleased Changes]

Atlas hardening since the 0.5.0 merge (PR #15):

### Fixed

- Multi-portal straddle: contact clipping is aperture-bounded again — the 0.5.0
  full-plane clip let two sessions' half-spaces swallow a ship, and parts beside a
  free-standing frame lost ground collision.
- Ships whose parent dimension is unloaded (e.g. a nether ship while everyone is in
  the overworld) fell through the world. They now go dormant (native Fixed body,
  zero tick cost) and wake in place when a player loads the area; no chunks are
  force-loaded.
- Ships in different parent dimensions collided when their coordinates overlapped
  (per-body parent-frame check in the native dispatcher).
- Connected ships got stuck on the wrong logical side of a portal: the per-member
  transit gate deadlocked once the first member crossed (gate removed).
- A roped partner no longer teleports through together with the crossing ship.
- Modded packet handlers resolve hosted-ship positions correctly: deferred
  world-frames wrap payload dispatch, arming on first hosted-plot resolution
  (fixes Simulated assembly interactions and Photomancy blueprint capture).
- Disassembly desync: block-restore notifications on a hosted ship routed to the
  hosting level (no chunk holder) and vanished; the client never saw the ship
  turn back into blocks.
- Teleports targeting the hosting dimension (e.g. Waystones placed on ships)
  redirect to the ship's parent dimension; Waystones' own distance check resolves
  ship-frame waystone positions through the delegate dimension.
- Entities parked at plot coordinates in a parent level (Simulated's ship-attached
  plungers) were garbage-collected by vanilla's entity-chunk unload within
  seconds, orphaning their physics joints. Three layers: plot-ticking memberships
  ignore the id-aliased client copy (vanilla Entity.equals compares by id, so
  singleplayer client lerps corrupted server state), plot-parked entities are
  exempt from chunk store/unload (player semantics), and entity-chunk visibility
  downgrades on live plot chunks re-assert ENTITY_TICKING.
- Client copies of plot-parked entities never ticked (no real parent client chunk
  at plot coordinates), freezing attach animations and orientation — the plunger's
  rope spline rendered permanently mid-connect. The client now mirrors the server's
  plot-chunk entity-ticking bridge.
- Plunger rope visuals: paired plungers no longer draw their rope to the world
  origin when the partner's client entity is transiently missing (Simulated zeroes
  its synced target client-side every tick; the synced value is restored), and
  client-side kicks through a not-yet-synced sub-level pose are skipped instead of
  teleporting the entity to ~(0,0,0).

### Changed

- Rigid assemblies (swivel bearings, fixed couplings — joints locking an angular
  axis) cross portals atomically as one unit.
- Ropes span portals: a crossed ship's rope routes through the aperture and pulls
  the trailing body toward it; the chain follows once both ends cross, and backing
  out unwinds. Ropes overstretched past 1.75× natural length (snagged trailing
  body, cross-dimension splits) break, vanilla-lead-style.

### Known Issues

- Rare missed rope re-unification when both ends cross the same portal within a
  tick (diagnostics in place).

## [6.0.7] - 2025-06-18

### Fixed

- Oritech animations not displaying ([#13](https://github.com/iPortalTeam/ImmersivePortalsModForNeo/issues/13)).
- ComputerCraft monitors not displaying anything ([#31](https://github.com/iPortalTeam/ImmersivePortalsModForNeo/issues/31)).

## [6.0.6] - 2024-12-22

### Updated

- Sync upstream (v6.0.6)
- Sodium compat (v0.6.0)
- Iris compat (v1.8.0) (experimental)

### Fixed

- Default config values being wrong

## [6.0.3] - 2024-10-20

### Added

- Initial port to NeoForge 1.21.1

### Known Issues

- Iris compatibility is not fully functional
- Crash with SecurityCraft

[Unreleased Changes]: https://github.com/iPortalTeam/ImmersivePortalsModForNeo/compare/v6.0.7...HEAD
[6.0.7]: https://github.com/iPortalTeam/ImmersivePortalsModForNeo/releases/tag/v6.0.7
[6.0.6]: https://github.com/iPortalTeam/ImmersivePortalsModForNeo/releases/tag/v6.0.6
[6.0.3]: https://github.com/iPortalTeam/ImmersivePortalsModForNeo/releases/tag/v6.0.3

