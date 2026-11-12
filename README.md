# v0.6.0 (WIP)
1) New foundation: the `ipl_sable:sublevels` hosting dimension is gone. Ships live natively in one level
   (stock Sable model); the far side of a portal is an Atlas **projection** of the same body.
2) Multi-portal and chained straddling, seamless same-dimension flips and cross-dimension moves.
3) Jointed builds: every body crosses on its own, the linked group flips together. Ropes collide
   through portals.
4) Linux/macOS natives build script, multi-platform natives loader, far less console spam.
5) Creative Physics Staff through portals: grab and lock through any chain of portals (blocks behind
   a doorway no longer block it), drags survive cross-dimension moves, less tangling.
6) Optimization, caching, less useless work.

The full list of changes is in `changelog.md`; the deep dive is `README060NEW.md`.

# Immersive Portals × Sable Compatibility Fork
Version: NeoForge 1.21.1; Targets: Sable 2.0.3+ · Create Aeronautics 1.3.0+ · Create 6.0.10

This is a fork of Immersive Portals for people who want Sable + physics sub-levels + (maybe) Aeronautics
and got tired of portals working like shit.

Goal: ships (and other physics-assembled sub-levels) can:
- render correctly when they’re halfway through a portal (clipped at the frame, on both sides)
- keep original working physics on both sides while straddling
- move through dimensions as one continuous motion (not a hard teleport)
- carry portals on themselves, bound like a monolith
- pass portals through portals

Not a general IP release. It’s experimental, built for this stack, with love.

Upstream IP (NeoForge): https://github.com/iPortalTeam/ImmersivePortalsModForNeo
Sable: https://github.com/ryanhcode/sable

# What’s the trick?
Sable keeps each sub-level’s blocks in a far-away “plot” of the level the ship lives in.
The fork keeps exactly that native model and adds **projections**:

- All dimensions share ONE Rapier world (“Atlas”); every level is a chart of it.
- A straddling ship gets an *image collider* of the SAME rigid body in the destination chart,
  placed at `portal isometry × body pose`. No clone, no sync: one body, one source of truth.
- Every block near a portal carries a *piece* label: it switches piece only when its centre really
  passes the portal rectangle (passing beside the frame changes nothing). A contact survives only
  on the collider of its block’s piece (requires patched natives; see below).
- Rendering: the same render data is drawn again at the mapped pose; only blocks inside the thin
  zone at the portal are clipped by its plane.
- Chains: a piece that straddles another portal opens a child projection, as deep as you like
  (255 pieces per body).
- Transit: once a body is fully through, it flips (same dim: pose teleport; other dim: native move
  into that level). Bodies linked through Sable (bearings, springs, rope holders, any constraint)
  each cross on their own and flip together once all of them made it; until then the passed ones
  live on their projections. Ships anchored to the world keep the session open instead.
- Ropes: rope points follow the pieces of the ship they hang on, so a half-passed ship never rests
  on rope points stuck behind the portal.
- Interaction: targeting/outlines/break/place/redstone/BE logic and the physics staff work on the
  projected part.

Deep nerd notes live in `README060NEW.md`. `docs/atlas_portal_physics_spec.md` is the Atlas design;
the other `docs/` specs describe the removed 0.5.x model.

# What works
- Portal straddle rendering (including portals-in-portals).
- Straddle physics: the “through” part collides with the destination world as if it really were there.
  Standing/walking/riding on the through-part behaves as it should (friction included).
- Block interaction on the through-part: break/place + outlines + prediction + redstone + pistons + block entities.
- Physics staff: lock/drag works on the through-part from either side.
- Jointed builds, springs and ropes cross portals without blocking each other.
- Dimension stack seams: IP vertical stacking portals behave (scaled/inverted stacks: transit works).

# Known limitations
- Projections are pointing to original, many blocks will behave strange
- DH, Voxy, Iris, Entity Culling support (right now, not forever)
- Standing on projections sucks, sublevel tp could trigger player tp.
- Shading is broken, sometimes "in ground" shading is applied
- Bugs...

# Build
NeoForge 1.21.1, JDK 21: `./gradlew jarJar` → jar in `build/libs/`

Patched physics natives live under `natives/` (Sable Rust workspace fork + piece clipping + Atlas).
- Windows x86_64: `natives/build-windows.ps1`
- Linux / macOS (x86_64 or aarch64, built on that machine): `natives/build-linux.sh`
- Any target from any host, with Docker (the same containers Sable uses): `natives/build-cross.sh <windows|linux|macos> <x86_64|aarch64>`
- Every `sable_rapier_<arch>_<os>` library at the project root is packed into the jar as
  `natives_ipl/sable_rapier_binaries.zip.l4z`, the way Sable packs its own. `-PrequireAllNatives` fails the
  build unless all six are present.

If you don’t build natives, the mod still runs on stock Sable natives — portal collision
(projections, piece clipping) is then disabled.

# Release
Push a tag `vX.Y.Z` matching `fork_version`. `.github/workflows/release.yml` builds all six natives,
builds the jar and opens a draft pre-release with it and the changelog section. Running the workflow
by hand only produces the jar as a workflow artifact (tick “draft release” to also create the draft).

# About Immersive Portals (upstream)
Immersive Portals does see-through portals, portals-in-portals, seamless teleportation and non-Euclidean weirdness.
If you like this fork, please consider starring the upstream project too — they did the heavy lifting.

- CurseForge: https://www.curseforge.com/minecraft/mc-mods/immersive-portals-mod
- Modrinth:  https://modrinth.com/mod/immersiveportals
- Website:   https://qouteall.fun/immptl/
- Wiki:      https://qouteall.fun/immptl/wiki/

![immptl.png](https://i.loli.net/2021/09/30/chHMG45dsnZNqep.png)
