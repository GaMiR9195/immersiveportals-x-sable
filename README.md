# Immersive Portals Mod

It's a Minecraft mod that provides see-through portals and seamless teleportation. It also can create "Non-Euclidean" (Uneuclidean) space effect.

![immptl.png](https://i.loli.net/2021/09/30/chHMG45dsnZNqep.png)

[On CurseForge](https://www.curseforge.com/minecraft/mc-mods/immersive-portals-mod)     [On Modrinth](https://modrinth.com/mod/immersiveportals)     [Website](https://qouteall.fun/immptl/)

This mod changes a lot of underlying Minecraft mechanics. This mod allows the client to load multiple dimensions at the same time and synchronize remote world information(blocks/entities) to client. It can render portal-in-portals. The portal rendering is roughly compatible with some versions of Sodium and Iris. The portal can transform player scale and gravity direction.  [Implementation Details](https://qouteall.fun/immptl/wiki/Implementation-Details)

(This is the Fabric version of Immersive Portals. [The Forge version](https://github.com/iPortalTeam/ImmersivePortalsModForNeo))

---

# Sable Compatibility Fork

This fork adds compatibility between Immersive Portals and the
[Sable](https://github.com/ryanhcode/sable) physics/sub-level mod, letting
airships and other physics-assembled sub-levels interact with portals.

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

## API

This mod also provides some API for:

* Manage see-through portals
* Dynamically add dimensions
* Synchronize remote chunks to client
* Render the world into GUI
* Other utilities

[API description](https://qouteall.fun/immptl/wiki/API-for-Other-Mods.html).

## How to run this code
https://fabricmc.net/wiki/tutorial:setup

## Other

[Wiki](https://qouteall.fun/immptl/wiki/)

[Discord Server](https://discord.gg/BZxgURK)

[Support qouteall on Patreon](https://www.patreon.com/qouteall)

