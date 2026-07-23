# Immersive Portals × Sable Compatibility Fork

**Platform: NeoForge 1.21.1 · Sable 2.0.3+ · Create Aeronautics 1.3.0+ ·
Create 6.0.10.** This is a fork of
[Immersive Portals](https://github.com/iPortalTeam/ImmersivePortalsModForNeo)
that adds compatibility with the
[Sable](https://github.com/ryanhcode/sable) physics/sub-level mod, letting
airships and other physics-assembled sub-levels interact with portals — render
clipped at portal frames, straddle a portal with working physics on **both**
sides, and travel between dimensions as a continuous motion rather than a
teleport.

It is **not** a general-purpose Immersive Portals release: it targets the
Sable + Simulated + Create Aeronautics stack on NeoForge 1.21.1 and carries
experimental gameplay code (see [Known Limitations](#known-limitations)). For
vanilla portal use, prefer the upstream releases linked at the bottom of this
file.

## The Unifying Move

Sable embeds each sub-level's blocks ("plot chunks") at a far offset inside its
parent dimension. Immersive Portals renders and simulates multiple dimensions
at once. This fork unifies the two models — **dimension-agnostic sub-levels**:

- Every sub-level's plot chunks live once, in a dedicated hosting dimension
  (`ipl_sable:sublevels`). The "parent dimension" becomes metadata: a pose
  frame, not a container.
- **Rendering** gathers hosted sub-levels per camera dimension. A ship
  straddling a portal renders in *both* dimensions — the source side clipped at
  the portal plane, the through-portion drawn at the portal-mapped pose with
  the complementary clip (`gl_ClipDistance` slot-1, alongside IP's portal
  clip; covers vanilla and Iris-rewritten programs).
- **Physics** (v0.3.0, "per-scene"): every dimension keeps its own Rapier scene
  at natural coordinates, and a hosted ship's body lives in its *parent*
  dimension's scene — no terrain copying, no height-profile translation, and
  cross-dimension phantom interactions are structurally impossible. While
  straddling, a **clone body** (same voxel colliders, mirrored mass) exists in
  the destination scene at the portal-mapped pose, pinned there every physics
  substep; whatever pose/velocity correction the destination solver applies to
  it — real contacts, real torques, real friction — is copied back onto the
  ship. Contacts are **clipped at the portal aperture inside the solver** (a
  patch to Sable's Rust natives, bundled for Windows x86_64): the through-part
  stops colliding with source-side geometry, and only the through-part is
  physically present on the far side. Entities stand on, walk on, and ride the
  through-part exactly where it renders.
- **Transit** is a parent flip. When the ship's center crosses the plane, its
  parent reference changes and its pose is remapped through the portal — no
  block copying, no mirror entity, no duplicate inventories. Riders are carried
  through.
- **Interaction** is frame-mapped end to end: crosshair targeting, hit
  outlines, block breaking/placing, redstone (including pistons and other
  block entities), and Simulated's physics staff (lock + drag) all work on the
  through-portion from either side, and through the portal via IP's
  cross-portal block manipulation.

The implementation notes, architecture decisions, and a catalog of the
cross-dimensional frame bugs encountered along the way live in
[`REFACTOR_SPEC.md`](REFACTOR_SPEC.md) (see §20).

## What Works

- Assembly, persistence (save/reload), and physics for hosted sub-levels in
  any dimension.
- Straddle rendering: a ship halfway through a portal draws coherently on both
  sides, clipped at the frame, in direct view and through the portal.
- Straddle physics (position-based, v0.3.0): the through-part collides with
  the destination dimension's real terrain — resting, pushing, sliding — with
  solver-quality contact response; source-side terrain no longer collides with
  the clipped part of the hull; standing, walking, jumping (correct friction —
  ice is slippery) on the through-part; riders carried across the transition
  seamlessly.
- Full block interaction on the through-part: targeting, outlines,
  break/place with clean client prediction, redstone components including
  multi-cycle pistons, furnaces and other block entities.
- Create drills, deployers, saws and harvesters on hosted ships interact with
  the parent world's terrain (the "world-frame router").
- Physics staff: lock and drag on the through-part from either side of the
  portal.
- Cross-portal block interaction (IP's feature) extended to ship blocks.
- **Dimension-stack seams**: IP's global vertical connecting portals are full
  straddle/transit citizens (default scale-1 stacks; scaled/inverted stacks
  transit without cross-seam physics).
- Repeated round-trips, quit/reload, and quit-to-title are stable.

## Known Limitations

- **Ship ↔ ship interaction across the portal mouth** is the remaining
  frontier of the per-scene design (a straddler and a destination-native ship
  meeting at the seam). Same-dimension ship pairs interact normally, and
  cross-dimension phantom interactions are gone as of v0.3.0.
- **Translation-only portal pairs.** The straddle physics/interaction mapping
  supports the standard same-orientation nether-portal link; rotated portal
  pairs render but skip the cross-seam physics.
- **Custom natives are bundled for Windows x86_64 only.** Other platforms fall
  back to Sable's stock natives: everything works except aperture contact
  clipping (the clipped hull part collides with source terrain again). Build
  your own via `natives/build-windows.ps1` or its cargo equivalent.
- Slight positional drift can accumulate while resting hard against far-side
  walls/floors (pin/solve float mismatch — polish).
- **Iris shaders** on the hosted render path are untested. (Sodium itself is
  supported as of v0.3.1 — hosted sub-levels render through Sable's
  reach-around backend with Sodium 0.8.)
- Polish items: the staff drag beam doesn't visually connect across the portal
  and the drag releases when the ship completes its transition; Jade's
  "looking at" overlay doesn't resolve targets through portals (an upstream
  IP+Jade integration gap); Flywheel-compiled components (cogs) use a buggy
  eye-space clip during straddles in portal-containing scenes.

Experimental hosted-world mode: `-Dipl.sable.dimAgnostic=true`; default is the stable
in-world mirror/copy model. Other kill switches: `-Dipl.sable.perScene=false` (single hosting scene),
`-Dipl.sable.perScene=false` (single hosting scene), `-Dipl.sable.cloneBodies=false`
(phantom terrain instead of clone bodies), `-Dipl.sable.customNatives=false`
(sable's stock natives), env `IPL_DISABLE_CLIP=1` (aperture clipping off).

Contributions and bug reports are welcome — the gameplay logic lives under
`ipl.sable`, the dimension-agnostic core under `ipl.sable.transit` /
`ipl.sable.dim`, and the shader injection under
`assets/immersive_portals/shaders`.

---

## About Immersive Portals

Immersive Portals provides see-through portals and seamless teleportation, and
can create "Non-Euclidean" space effects. It lets the client load multiple
dimensions at once and synchronize remote world information (blocks/entities)
to the client, render portals-in-portals, and transform player scale and
gravity direction. Portal rendering is roughly compatible with some versions of
Sodium and Iris. [Implementation Details](https://qouteall.fun/immptl/wiki/Implementation-Details)

![immptl.png](https://i.loli.net/2021/09/30/chHMG45dsnZNqep.png)

Upstream Immersive Portals: [On CurseForge](https://www.curseforge.com/minecraft/mc-mods/immersive-portals-mod) · [On Modrinth](https://modrinth.com/mod/immersiveportals) · [Website](https://qouteall.fun/immptl/) · [NeoForge source](https://github.com/iPortalTeam/ImmersivePortalsModForNeo)

## API

This mod also provides some API for:

* Manage see-through portals
* Dynamically add dimensions
* Synchronize remote chunks to client
* Render the world into GUI
* Other utilities

[API description](https://qouteall.fun/immptl/wiki/API-for-Other-Mods.html).

## Building

This fork builds against NeoForge 1.21.1. Run `./gradlew build`; the mod jar is
written to `build/libs/`. All dependencies (Sable, Veil, Create, Aeronautics,
Sodium, …) resolve from public mavens — a fresh clone builds with no manual
steps. JDK 21 is the only prerequisite.

The patched Sable physics natives live under `natives/` (a fork of sable's
Rust workspace at the matching release tag, plus our aperture-clipping
extension). `natives/build-windows.ps1` builds the Windows x86_64 DLL and
stages it into the mod's resources; without it the mod still builds and runs,
falling back to Sable's stock natives (no contact clipping). Prereqs: rustup
with the pinned toolchain from `natives/rust-toolchain.toml` (GNU host) plus
the `rust-mingw` and `llvm-tools` components.

## Other

[Wiki](https://qouteall.fun/immptl/wiki/)

[Discord Server](https://discord.gg/BZxgURK)

[Support qouteall on Patreon](https://www.patreon.com/qouteall)
