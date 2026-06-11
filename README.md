# Immersive Portals × Sable Compatibility Fork

**Platform: NeoForge 1.21.1.** This is a fork of
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
- **Physics** is both-side aware while straddling: the destination dimension's
  terrain is cloned into the physics scene through the inverse portal
  transform, so the through-portion of the hull collides with dest terrain
  before any transition; entities stand on, walk on, and ride the through-part
  exactly where it renders.
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
- Straddle physics: hull contact with both dimensions' terrain; standing,
  walking, jumping (correct friction — ice is slippery) on the through-part;
  riders carried across the transition seamlessly.
- Full block interaction on the through-part: targeting, outlines,
  break/place with clean client prediction, redstone components including
  multi-cycle pistons, furnaces and other block entities.
- Physics staff: lock and drag on the through-part from either side of the
  portal.
- Cross-portal block interaction (IP's feature) extended to ship blocks.
- Repeated round-trips, quit/reload, and quit-to-title are stable.

## Known Limitations

- **Ship ↔ ship interaction across dimensions** is the current frontier: two
  ships in the same dimension interact normally, but a straddling ship and a
  ship native to the destination side don't collide with each other at the
  portal mouth yet, and ships from different dimensions can phantom-interact
  when their coordinate spaces overlap.
- **Translation-only portal pairs.** The straddle physics/interaction mapping
  supports the standard same-orientation nether-portal link; rotated portal
  pairs render but skip the cross-seam physics.
- **Sodium/Iris render backend** for the hosted render path is untested in v2
  (the slot-1 clip plumbing from v1 exists; the gather splice does not yet).
- Polish items: the staff drag beam doesn't visually connect across the portal
  and the drag releases when the ship completes its transition; Jade's
  "looking at" overlay doesn't resolve targets through portals (an upstream
  IP+Jade integration gap); Flywheel-compiled components (cogs) use a buggy
  eye-space clip during straddles in portal-containing scenes.

A `-Dipl.sable.dimAgnostic=false` JVM flag reverts to the legacy
mirror/copy-based model.

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
written to `build/libs/`. Note that the Flywheel compile dependency is supplied
as a local file under `libs/` (extracted from Create's bundled jar) and is
gitignored, so a fresh clone needs that jar dropped in before `compileJava` will
succeed.

## Other

[Wiki](https://qouteall.fun/immptl/wiki/)

[Discord Server](https://discord.gg/BZxgURK)

[Support qouteall on Patreon](https://www.patreon.com/qouteall)
