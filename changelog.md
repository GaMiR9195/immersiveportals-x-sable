# Changelog

All notable changes to this project are documented in this file (most of the time).
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project tries to adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.6.0] — WIP

Rewrite of the foundation: the `ipl_sable:sublevels` hosting dimension is gone. Sub-levels live
natively in exactly one level (stock Sable model); the far side of a portal is an Atlas
**projection** of the same body. Deep notes: `README060NEW.md`.

### Changed

- **Projections instead of clones.** A straddling body gets an image collider of the SAME rigid
  body in the destination level's Atlas chart (`image pose = portal isometry × body pose`), and
  its render data is drawn again at the mapped pose. One body, one source of truth; forces on
  the image act on the real body.
- **Pieces instead of clip volumes.** Which material went through which portal is a property of
  the material: every plot voxel near a portal carries a piece label (`IplPieceMap`, one byte).
  `IplPieceTracker` samples every body at each physics substep boundary (stock natives: tick
  tail). A voxel whose centre passes the finite rectangle front to back joins the session's
  piece, back to front returns to the parent; passing the plane beside the frame changes
  nothing. Crossings of sibling and nested apertures apply in observed time order with a
  per-voxel cursor; observed rotation is split into short slerp arcs (≤ 128 per sample). No
  tick-ahead forecast, no 32-block teleport cutoff; only voxels touching the aperture zone are
  split by the plane. Natives, entity collision, raycasts, staff, outlines and rendering all use
  this one material rule.
- **Sessions.** Keyed by portal path (`StraddleKey`), one piece each. A piece crossing a portal of
  its destination level opens a child session with the composite isometry; a piece may have
  several child portals and a body several root portals. The depth-16 and 64-session caps are
  gone (the label byte still limits a body to 255 pieces). Both faces of a doorway may own
  sibling pieces; same-normal duplicates of one mapping are canonicalized. A session opens on an
  observed crossing or on real contact with the aperture.
- **Settlement per body** (`IplSessionLifecycle`, tick tail). Labels are history and only the
  body's own material is ever moved: an aperture wholly in front of or behind the body proves
  the direction; two sides that meet only around the frame (a ring spun around a two-sided
  portal) rejoin the larger side; a path without material and without a block in its aperture
  slab closes. Once no aperture holds anything, the body is collected into the frame of most of
  its material. No timeouts, no velocity heuristics.
- **Linked groups** (`IplLinks`). Links come from Sable only: the actor dependency graph
  (`sable$getConnectionDependencies`: bearings, springs, rope holders, connectors) and every
  constraint created through Sable's pipeline between two sub-levels, so mods that publish no
  dependency are covered too. The graph is undirected and resolved once per tick, only when a
  flip is pending. Links never touch entry: each body opens its own sessions, so a jointed build
  goes into a portal exactly like a single body. A group flips through one isometry once every
  member with blocks completed the same crossing; zero-block connectors follow. Until then the
  passed members live on their projections and nobody is pulled into another member's frame.
  A rigid constraint to the static world anchors the group (no flip); free constraints to the
  world (staff, handles) do not.
- **Ropes** (`IplRopeRouting`, native ABI 8). While a body a rope hangs on is split by a portal,
  every physics sample walks the rope from its attachment: the attached block's piece gives the
  end point's frame, a segment passing one of the body's finite apertures enters that frame,
  passing back returns. Point colliders live in their frame's chart through the composed
  isometry, like a body image; a rope bending around the frame stays put. No per-point state.
  A same-dimension group flip moves eligible rope particles with the group (ABI 4 preflight:
  a rope tied to the world or to a body outside the group holds the flip); a group with ropes
  does not change dimension.
- **Flip.** Same dimension: pose teleport of the live body plus client interpolation rebase
  (`handoff`); internal joint warm starts are reset after a group move (ABI 7). Other dimension:
  native move into the destination level, keeping the same global plot slot when free; the
  client shows a ghost until the successor is compiled (3 s timeout). Up to 16 flips per body per
  tick; promoted child sessions keep their labels.
- **Rendering** (`IplPieceRender`). Every draw is one piece; per section and layer SKIP, FULL (one
  plain draw, the common case), RANGES (one multi-draw over the piece's quads, recorded per block
  at compile by `IplSectionRanges`) or SPLIT (zone blocks drawn by both pieces, cut by the
  aperture plane). Plans are cached per buffer and label revision; translucent RANGES/SPLIT draws
  follow the sorted quad order (`VertexBufferMixin`). Block entities draw once per piece plus one
  cut pass per edge; Flywheel keeps instancing everything but block entities at an aperture.
- **Interaction.** Entity collision clips per piece (`PieceCut`: zone blocks trimmed at the plane,
  step-up included; an entity's frame is the nearest piece material). Raycasts walk only the
  piece's blocks; entity lookups only touch pieces overlapping the query. Staff picks walk the
  whole ray range and store the full image path in NBT; invalid path seeds are rejected.
  Staff, picks and outlines route to the owner level of a projected sub-level.
- **Clients.** Sessions and labels are kept per coordinate frame: payloads of a frame not entered
  yet wait and replace the old frame at entry, so nothing blinks at a flip. Payloads carry their
  server tick and apply when the shared interpolation clock shows it. Label payloads are partial
  (changed sections, ≤ 64 per packet) and are sent only when labels or edges change. Transit
  ghosts draw their successor's pieces re-rooted through the crossing portal. Per-tick client
  caches are keyed by the client tick, not by the clock of whichever dimension IP has bound for
  the current portal pass.
- **Natives.** ABI 3: piece labels and edges per body (`setPieceSection`, `setPieceEdges`,
  `setImageNode`, `clearPieces`); a contact survives only on the collider of its point's piece.
  ABI 5/6: label-time plane, then the full label-time rectangle (32-double edges). ABI 7: joint
  warm-start reset. ABI 8: rope point routing and piece-aware voxel pair culling in the
  dispatcher. Older or stock natives open sessions without image colliders (split, drawn and
  flipped; the far side does not collide).
- **Physics stepping** (`atlas/IplFusedStep`). With IPSable natives one fused step per server tick
  drives every level, and levels that do not drive it update `lastPose` before the step; with
  stock natives each level steps itself, as in Sable. Ship-anchored portals follow their ships
  after the step in both modes.
- **Plots.** Slots are allocated over the plots of all levels, so plot coordinates stay unique.
  A plot read falls back to a foreign level's plot only while its owner is projected into the
  querying level; server writes into it run in the owner level, client writes are rejected.
- **Tracking and time.** Cross-dimension tracking follows what IP streams to the player (any
  loaded chunk under the ship's bounds, from any level, or proximity to an image). Sub-level
  packets are dimension-tagged; remote viewers and the singleplayer host get movement over TCP.
  All IP client levels tick their sub-level containers once per client tick (not while paused).
  Every dimension stamps snapshots, clock packets and start-tracking with one server-wide tick
  (`IplTimeline`); the client runs one interpolation clock for all levels (`IplSharedClock`),
  merging clock packets of all levels.
- **Create: Pocket Sized.** The scale handshake covers sub-levels of all IP client levels, a
  repeated scale no longer resets interpolation, and the sub-level frustum filter tests the drawn
  (mapped) box against the current pass.
- **Natives packaging.** The loader picks `sable_rapier_<x86_64|aarch64>_<windows.dll|linux.so|macos.dylib>`
  like Sable and falls back to stock natives with one INFO line (kill switch
  `-Dipl.sable.customNatives=false`). Libraries at the project root are packed as
  `natives_ipl/sable_rapier_binaries.zip.l4z` and extracted to `.sable/natives-ipl/`. New
  `natives/build-linux.sh`; release jars bundle all six platforms via `natives/build-cross.sh`.
- **Diagnostics.** Per-event logs are DEBUG; per-draw diagnostics need
  `-Dipl.portalDiagnostics=true`. `-Dipl.perfDiagnostics=true` logs every 5 s the client and
  server spatial-query splice, client projection-tree builds, client piece draws, the transit
  sample at every solver boundary, the Sable container tick and the transit controller.
- **Code.** Accessors instead of reflection, narrowed `catch` blocks, imports instead of qualified
  names, comments describe current behavior. Moved, merged and removed classes:
  `docs/renames-0.6.0.md`.

### Removed

- `ipl_sable:sublevels`, its datapack and the hosting/clone/pin/UUID-bridge code (~70 classes and
  mixins); `IplDimSwitchGuard` (it masked a cross-dimension stall fixed at its cause).
- The swept-volume crossing detector and everything built on it: per-session tubes and wide
  half-spaces, sibling priority, client entry prediction, early-open look-ahead, two-plane
  clipping of whole bodies, the entering-volume overlay, the commands `early_open_segments` and
  `visualize_sweep_detector`, `-Dipl.sable.clip.seamSupportMargin`, native clip regions of ABI
  1 and 2, and `tests/portal`.
- The shared group path context, the group-wide frame vote and the native joint-component scan:
  linked bodies are coupled only at the flip (`IplLinks`). The per-point rope crossing tracker
  is replaced by stateless routing (`IplRopeRouting`). `tests/lifecycle` went with them.
- The rope portal seam (`ipl.sable.ropePortalSeam`, `ipl.sable.ropeBreakStretch`), no-op portal
  rim stubs, `IplTrackingStamp` and the clock offset of the `move` RPC, `-Dipl.sable.clip.forceAll`,
  unused classes, native declarations and JNI exports Java never called, pass-through wrappers.

### Fixed

- **Client lag while a body touches a portal into another dimension** (worse with every body of a
  jointed build that has an image there). Immersive Portals animate-ticks the remote level around
  the portal every other tick; Sable's sub-level animate tick got the portal images from the
  spatial-query splice and sampled them with their native pose. The query point landed far off
  the plot and `BoundingBox3i` turned the empty intersection into the gap box between them:
  thousands of bridged plot reads per image per tick on the client thread (vanilla profiler: ~75%
  of client time in `imm_ptl_client_tick`, 13 TPS). Animate ticks now run without images
  (`ClientLevelMixin`, `IplClientProjections.withoutImages`); the body keeps its own level's.
- **Jointed builds.** A spring (or any link) across a portal no longer teleports the partner
  that has not crossed, and its session holds until both sides crossed. Linked bodies that do
  not touch an aperture no longer get images, sessions, sweeps and label traffic: in a chain at a
  portal only the links that touch it do that work. A body jointed to the world no longer flips
  away from its joint.
- **Ropes.** Rope points left behind the portal plane no longer hold a ship on phantom ground;
  they follow the frames of the body they hang on, also when the rope bends around the frame.
- **Cross-dimension projections.** Client projection trees and render views were rebuilt on every
  switch between portal passes (each dimension has its own clock). Destination terrain behind
  a portal no longer produces thousands of discarded manifolds for a buried image.
- **Several portals at once.** An edge cuts, holds and flips only its own material (a parent voxel
  on the entry side or a child voxel on the far side); material that reached the other side
  without passing the rectangle is not split and does not hold the session. Both faces of a
  doorway may carry sessions together, so a U leaving through the front while entering through
  the back keeps both arms. A piece with two child portals is cut by both, and coplanar siblings
  agree between physics and rendering.
- **Timing.** A quick dip no longer removes the far half of blocks still drawn in the doorway, and
  blocks switch sides at the drawn pose, not ahead of it.
- **Phantom ground after a portal.** The natives get the new frame's labels right after a flip
  and drop the data of a body that changed id or scene; sessions do not linger after the last
  touch, and leftovers that went around the frame neither hold a session nor hang at an offset.
  A removed block resets its label.
- **Cross-dimension moves.** No stop-and-jerk at the exit (the successor starts from the right
  pose, with its previous pose mapped through the portal); no skip-ahead when the successor
  reaches the client; no loss of pace (one timeline and clock for all levels, the successor
  inherits the ghost's older snapshots).
- **Stepping.** Levels that did not drive the fused step kept `lastPose == pose` (entities were not
  carried, start-tracking sent a frozen interval); with stock natives only the last level
  stepped. Dedicated servers sent untagged UDP movement that could land in the wrong container.
- **Rendering.** Sections compiled during another level's portal pass rendered twice as dark (wrong
  shading flag). FPS no longer halves while a projection enters a portal (single-piece sections
  draw as before, mixed ones as one multi-draw, the plane only inside the aperture zone, no
  client entry prediction). No stutter from spatial queries scanning every client entity:
  portals are looked up by UUID, the server splice skips ships without an image into the level,
  and broken surrogate portals are not re-decoded per query.
- **Create: Pocket Sized.** No stop-and-jerk of a ship leaving into another dimension, no freeze when
  entering that dimension, no invisible projections from some angles, and correct portal cuts
  of scaled ships under vanilla shaders.
- A ring pushed through a portal is no longer cut by the infinite plane. After a same-dimension
  flip the old frame no longer announces an empty session list. `tests/render` and `tests/gl`
  follow the production API.

### Known limitations

- Worlds saved with ships inside `ipl_sable:sublevels` are NOT migrated: disassemble such ships
  with 0.5.x before upgrading.
- Self-collision through a portal is not implemented (image and body are one Rapier body).
- A spring tied to the world, rather than to another sub-level, is invisible to Sable's link
  contract; its body flips alone.
- A group with ropes stays on its projection instead of changing dimension; ropes are still
  drawn by their mod as a straight segment in the source level.
- Multiple aperture cuts through one block are not fully represented by the one-plane
  renderer/entity collision. Range draws use triangles, so Iris packs that tessellate terrain may
  not draw piece sections at a portal.
- Not verified in game in this pass: test jointed chains, springs, ropes, rotating rings and
  staff loops on a copy of a world (`-Dipl.perfDiagnostics=true` for timings).

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
