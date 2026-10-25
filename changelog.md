# 0.6.0 — hosted API ownership boundary

Public parent-world sublevel enumeration is unscoped; internal physics/render/storage ownership remains explicit. Removed the PYM-specific clip scope hook. Corrected position-specific storage height gates, actual Flywheel visual liveness, global-only BER pass gating, retained entity identity/bounds migration, KinematicContraption enrollment and entity section lifecycle. See `060NOTES.MD` for file-level details, verification and unresolved cases.

# Changelog

All notable changes to this project will be documented in this file, per sometimes.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project tries to adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

