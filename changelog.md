# Changelog

All notable changes to this project will be documented in this file, per sometimes.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project tries to adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.6.0]

Rewrite of the foundation: the `ipl_sable:sublevels` hosting dimension is gone. Sub-levels
live natively in exactly one level (stock Sable model); portals are handled by Atlas
projections. See `README060NEW.md`.

### Changed

- Straddling no longer clones or synchronizes bodies. The far side is a **projection**: an
  image collider of the SAME rigid body registered in the destination level's Atlas chart
  (`image pose = portal isometry × body pose`), plus the same render data drawn at the
  mapped pose. One body, one source of truth; forces on the image act on the real body.
- Sessions are keyed by the portal path (`StraddleKey`). Chains (an image straddling another
  portal) open child sessions with composite isometries, depth ≤ 16, at most 64 sessions per
  body. Multi-portal straddling works with no extra limits.
- A session owns only what went through its aperture: its **through prism** (`PortalPrism`) is
  the aperture rectangle (plus a 0.25-block lateral margin) swept from the portal plane along
  the direction the material actually passed, the chord to a plot-local anchor captured at entry
  (slope ≤ 1.5). The native clip regions, the projection tree, raycasts, entity collisions and
  the client clip all use this one shape; the half-space ("wide") region after admission is
  gone. A chained image keeps the intersection of its path's prisms.
- Flip condition: the whole body must be through apertures (no block left in the native
  piece). A body pushed into two portals at once enters the one whose piece holds more of its
  blocks (ties: sibling priority) and takes the rest along; a body that crosses within one tick
  must cross the plane inside the aperture with every block.
- Sibling priority is geometric on prisms (`PortalOrder`: Q yields to P when Q's aperture lies
  beyond P's plane inside P's prism), then by path; the client tree applies the same order as
  the server.
- Render: every piece is cut into convex cells (`IplClipCells`) with up to six clip planes
  (`ipl_subLevelClipEquation[6]` → `gl_ClipDistance[1..6]`, in IP's shader transformation and
  in Flywheel). The first native cell is the body's own draw; further cells (a body sticking
  out beside an aperture, pieces with child portals or siblings) are extra passes.
- Entity collision with a straddling body clips the block boxes to the piece of the chosen
  frame (`IplCollisionRegion`): a block cut by a prism face collides in each frame with its own
  part only.
- Chained straddle detection reuses the existing swept trail mapped through the session
  isometry instead of running a second sweep per image; flips re-process the body in the
  same tick so infinite portal loops stay seamless.
- Attachment gate: non-Free constraints to the world, joints outside the rigid group and
  ropes keep the session open instead of flipping; jointed groups never change dimension
  (they keep working through the projection). Bodies linked by actors (springs, connectors,
  rope holders, swivel plates) cross together as one transit group of up to 16 bodies.
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
  `-Dipl.portalDiagnostics=true`.
- Code cleanup: redundant `require = 0` (the config default) and member-level
  `remap = false` dropped, imports instead of fully qualified names, accessors instead of
  reflection (`StaffActionPacketAccessor`, `SectionOcclusionGraphAccessor`),
  `catch (Throwable)` narrowed to `RuntimeException` and kept only at native, optional-mod,
  persisted-data and per-tick isolation boundaries, no blanket try/catch around RPC
  handlers. `IplClipUniformPatcher.patchForSubLevel` enables all sub-level clip slots
  itself. Comments describe current behavior. Moved, merged and removed classes:
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
  and pass-through wrappers, the unused shape argument of the entering-volume overlay RPC.
- `-Dipl.sable.clip.forceAll` and debug logging in per-draw render paths.
- Half-space session regions (`WIDE_EXTENT`, `isWide`, sync key `ipl_session_wide`, replaced by
  `ipl_session_anchor`), `PortalOrder.coveredBy`, `PortalCrossingDetector.retainsAdmittedSession`
  and `noMaterialBeyondOtherPortals`, the second clip plane API (`secondPlaneFor`,
  `findStraddlingPortalPlanes`, `getPlane2For`, `ipl$getSubLevelClipUniform2`) and the half-space
  collision helpers (`frameCuts`, `LocalHalfSpace`, `getSourceHalfKeepFilter`,
  `isInMappedHalf`). Full list: `docs/renames-0.6.0.md`.
- `IplTrackingStamp` and the clock offset argument of the `move` RPC, clock alignment of the
  destination level and ghost extrapolation: one timeline makes them unnecessary.
- Native JNI exports Java never called (`createPortalRim`, `removePortalRim`,
  `setPortalRimTransform`, `remapRope`, `overstretchedRopes`, `setRopePortalPrefix`,
  `setBodyDormant`, `setBodyPairExclusion`, `setParentFrame`) with their scene state and the
  dispatcher and hook checks that read it; unused Rust helpers `get_rigid_body` and
  `ChartData::get_octree_chunk(_mut)`.

### Fixed

- The Atlas clip cut a straddling ship along the portal's infinite plane: material that crossed
  the plane beside the frame (next to the portal, behind the wall) was clipped out of the source
  world, drawn and colliding behind the exit, and carried along by the flip. Only material inside
  the session's through prism belongs to the portal now; the rest keeps moving, colliding and
  rendering in the source world.
- A body wedged into two portals at once never flipped while both pieces held material.
- A piece with more than one child portal was cut only by the first one (two clip slots), and
  siblings in one wall could render differently from their physics.
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
- `tests/render` and `tests/gl` fixtures follow the production API again; all four test
  suites pass.

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
- Chained images (depth ≥ 2) cannot be dragged with the physics staff; picks and block
  interaction work.
- Self-collision through a portal is not implemented (image and body are the same Rapier
  body; Rapier filters same-body pairs).
- `sable_rapier_x86_64_windows.dll` in the source tree predates the native ABI probe: local
  builds that bundle it clip images in ABI-1 mode (exclusion regions only, coarse
  multi-portal and chain collisions). Release builds compile fresh natives in CI. Prisms need
  no native rebuild: the native region test already takes unnormalized axes.
- Through prisms are straight (slanted along the anchor chord): material that leaves its prism
  sideways after a strong turn inside the aperture counts as native again. There is no aperture
  rim collision yet, so a body wider than the portal stays stitched and never flips.
- Verified by compiling against the real dependencies (`cargo check` for the natives) and by
  the four test suites in `tests/`; not tested in game. The stutter fix above and the
  through-prism, flip, six-slot clip and collision-piece changes were checked without the
  dependencies (symbol and flow check against the mod's own sources, the test suites, a
  randomized equivalence test of the box pre-reject); build it before release.

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

