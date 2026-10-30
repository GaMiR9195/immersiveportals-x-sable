# v0.6.0
1) New foundation: the `ipl_sable:sublevels` hosting dimension is gone. Ships live natively in one level
   (stock Sable model); the far side of a portal is an Atlas **projection** of the same body.
2) Multi-portal / chained straddling, attachment gate, seamless same-dim flips and cross-dim moves.
3) Linux/macOS natives build script, multi-platform natives loader, much less console spam.

Full description of the 0.6.0 model: `README060NEW.md`. Old worlds with ships stored in
`ipl_sable:sublevels` are not migrated (see README060NEW.md → Upgrade).

# Immersive Portals × Sable Compatibility Fork
Version: NeoForge 1.21.1; Targets: Sable 2.0.3+ · Create Aeronautics 1.3.0+ · Create 6.0.10

This is a fork of Immersive Portals for people that want Sable + physics-sublevels + (maybe) Aeronautics
and got tired of portals working like shit.

Goal: ships (and other physics-assembled sub-levels) can:
- render correctly when they’re halfway through a portal (clipped at the frame, both sides)
- keep working physics on both sides while straddling
- move through dimensions as one continuous motion (not a hard teleport)
- have portals on sub-levels, bound to them like a monolith
- portals can traverse through protals

Not a general IP release. It’s experimental, and built for this stack, with love.

Upstream IP (NeoForge): https://github.com/iPortalTeam/ImmersivePortalsModForNeo
Sable: https://github.com/ryanhcode/sable

# What’s the trick?
Sable keeps each sub-level’s blocks in a far-away “plot” of the level the ship lives in.
Since 0.6.0 the fork keeps exactly that native model and adds **projections**:

- All dimensions share ONE Rapier world (“Atlas”); every level is a chart of it.
- A straddling ship gets an *image collider* of the SAME rigid body in the destination chart,
  placed at `portal isometry × body pose`. No clone, no sync: one body, one source of truth.
  Contacts beyond the portal plane are clipped away (requires patched natives; see below).
- Rendering: the same render data is drawn again at the mapped pose, clipped at the portal plane.
- Chains: an image that straddles another portal opens a child projection (depth ≤ 8).
- Transit: once fully through, the body flips (same dim: pose teleport; other dim: native move
  into that level). Attached ships (world constraints, joints, ropes) keep the session open instead.
- Interaction: targeting/outlines/break/place/redstone/BE logic and the physics staff work on the
  projected part.

Deep nerd notes live in: `README060NEW.md` (the `docs/` specs describe the removed 0.5.x model).

# What works
- Sub-levels save/load natively in any dimension (stock Sable storage).
- Portal straddle rendering (including portals-in-portals).
- Straddle physics: the “through” part collides with the destination world like it actually exists there.
  Standing/walking/riding on the through-part behaves like it should (including friction).
- Block interaction on through-part: break/place + outlines + prediction + redstone + pistons + block entities.
- Create stuff on ships (drills/deployers/etc) can interact with the parent world (world-frame routing).
- Physics staff: lock/drag works on the through-part from either side.
- Dimension stack seams: IP vertical stacking portals behave (scaled/inverted stacks: transit works).

# Known limitations
- Performance (Sub Level stuttering)
- DH + Voxy Support
- Bugs

# Build
NeoForge 1.21.1, JDK 21: `./gradlew jarJar` → jar in `build/libs/`

Patched physics natives live under `natives/` (Sable Rust workspace fork + aperture clipping extension + atlas approach).
- Windows x86_64: `natives/build-windows.ps1`
- Linux / macOS (x86_64 or aarch64, built on that machine): `natives/build-linux.sh`
- Universal jar: put every `sable_rapier_<arch>_<os>` library at the project root, then build the jar.

If you don’t build natives, the mod still runs on stock Sable natives — portal collision
(projections, aperture clipping) is then disabled.

# About Immersive Portals (upstream)
Immersive Portals does see-through portals, portals-in-portals, seamless teleportation, and non-euclidean weirdness.
If you like this fork, please consider starring the upstream project too — they did the heavy lifting.

- CurseForge: https://www.curseforge.com/minecraft/mc-mods/immersive-portals-mod
- Modrinth:  https://modrinth.com/mod/immersiveportals
- Website:   https://qouteall.fun/immptl/
- Wiki:      https://qouteall.fun/immptl/wiki/

![immptl.png](https://i.loli.net/2021/09/30/chHMG45dsnZNqep.png)
