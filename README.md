# Immersive Portals × Sable Compatibility Fork

**Platform: NeoForge 1.21.1.** This is a fork of
[Immersive Portals](https://github.com/iPortalTeam/ImmersivePortalsModForNeo)
that adds compatibility with the
[Sable](https://github.com/ryanhcode/sable) physics/sub-level mod, letting
airships and other physics-assembled sub-levels interact with portals — clip
correctly at portal frames and travel between dimensions.

It is **not** a general-purpose Immersive Portals release: it targets the
Sable + Create stack on NeoForge 1.21.1 and carries experimental gameplay code
(see [Known Limitations](#known-limitations)). For vanilla portal use, prefer
the upstream releases linked at the bottom of this file.

## Approach

Two largely independent systems make this work:

- **Rendering — clip planes in the vertex stage.** When a sub-level straddles a
  portal, the half of it that's logically on the other side must not poke through
  the frame. We inject a second clip plane (`gl_ClipDistance[1]`, alongside IP's
  existing portal clip) into the relevant vertex shaders — vanilla, Iris-rewritten,
  Sodium-terrain, and Create/Flywheel-compiled programs — and feed it a plane
  equation each frame. Geometry past the portal is culled by the GPU, so cogs,
  block entities, and terrain on an airship clip cleanly at the opening.

- **Gameplay — server-side transit with a preview mirror.** As an airship
  approaches a portal we spawn a *kinematic mirror*: a transient, server-side
  copy of the sub-level in the destination dimension, posed through the portal
  transform so you see where the ship is about to emerge. Mirrors are deliberately
  **not** physics bodies (no native rigid body — their pose is driven entirely by
  the source), are scoped to the server lifecycle, and are never written to disk.
  When the ship's center crosses the plane, an atomic transit moves the real
  sub-level (blocks, block-entity data, riders) into the destination dimension.

## What Works

- Sub-level clipping at portal planes — cogs, block entities, and terrain on an
  airship are culled correctly at the frame, including under Iris shaderpacks.
- Portal traversal — flying an assembled airship through a portal moves it (and
  standing/mounted-free riders) to the destination dimension; the mirror gives a
  live preview during approach. Repeated round-trips, quit/reload, and
  quit-to-title are stable.

## Known Limitations

This is compat between two mods that each rewrite large parts of the engine;
some interactions aren't solved yet:

- **Physics interactions mid-transit.** Standing on a sub-level *while it
  traverses* a portal isn't fully handled — you may be left behind or dismounted
  rather than carried through. Sitting on Create contraptions (seats) across a
  transit has the same gap.
- **Source ↔ mirror update propagation.** Changes on one copy of a straddling
  ship don't propagate to the other. Most visibly, interacting with a block
  entity (e.g. a chest) that exists on both the source and its mirror can
  **duplicate items**. Treat chests on a portal-straddling airship as unsafe
  until this is addressed.

Contributions and bug reports are welcome — the gameplay logic lives under
`ipl.sable` and the shader injection under `assets/immersive_portals/shaders`.

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

