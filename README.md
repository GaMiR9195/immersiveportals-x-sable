# v0.5.0
1) Better Sodium compatibility (same dimension + recursion stuff).
2) Fixed clipping-through-walls: it used to stop at portal size. Now it doesn’t. Ever.
3) HUGE amount of rendering bug fixes.
4) Full rewrite of how we store sub-levels in another dimension.
5) Portals on sub-levels.
6) New “Physics Staff” mixin, works with recursion (still kinda WIP).
7) New, portals now have colliders.

# Immersive Portals × Sable Compatibility Fork
Version: NeoForge 1.21.1; Targets: Sable 2.0.3+ · Create Aeronautics 1.3.0+ · Create 6.0.10

This is a fork of Immersive Portals for people that want Sable + physics-sublevels + (maybe) Aeronautics
and got tired of portals working like shit.

Goal: ships (and other physics-assembled sub-levels) can:
- render correctly when they’re halfway through a portal (clipped at the frame, both sides)
- keep working physics on both sides while straddling
- move through dimensions as one continuous motion (not a hard teleport)
- have portals on sub-levels, bound to them like a monolith
- GFSGIHDIUGHDIGHDIUGHDIUGHIUFGHFDIGUHDIUGHIUGHDIUFHIGUHIUDFHGIUDHFGIHFGIUHDFIUGHIUDFHGUIHFGIUHGIGDFGHDI

Not a general IP release. It’s experimental, and built for this stack, with love.

Upstream IP (NeoForge): https://github.com/iPortalTeam/ImmersivePortalsModForNeo
Sable: https://github.com/ryanhcode/sable

# What’s the trick?
Sable normally “hides” each sub-level by shoving its blocks far away inside a dimension.
IP is like “cool, I’m rendering/simulating multiple dimensions anyway.”

So this fork basically says:
- sub-level blocks live ONCE in a dedicated hosting dimension: `ipl_sable:sublevels`
- “parent dimension” is just metadata (where the ship *belongs* / which physics scene owns it)

Then we glue the rest together:
- Rendering: straddling ships draw in both dimensions, clipped cleanly at the portal plane.
- Physics: each dimension gets its own Rapier scene. A straddling ship gets a clone body on the other side,
  pinned every substep, and the solver’s corrections get copied back.
  Also: contacts get clipped at the portal aperture (requires patched natives; see below).
- Transit: crossing the plane flips the ship’s parent + remaps pose through the portal. No block copying.
- Interaction: targeting/outlines/break/place/redstone/BE logic and cross-portal manipulation work on ship blocks.
  Also the physics staff can grab/drag through the mess.

Deep nerd notes live in: `REFACTOR_SPEC.md`

# What works
- Hosted sub-levels save/load correctly in any dimension.
- Portal straddle rendering (including portals-in-portals).
- Straddle physics: the “through” part collides with the destination world like it actually exists there.
  Standing/walking/riding on the through-part behaves like it should (including friction).
- Block interaction on through-part: break/place + outlines + prediction + redstone + pistons + block entities.
- Create stuff on ships (drills/deployers/etc) can interact with the parent world (world-frame routing).
- Physics staff: lock/drag works on the through-part from either side.
- Dimension stack seams: IP vertical stacking portals behave (scaled/inverted stacks: transit works).
- SSGHDSIUFGHDIFGHDIUGIOSJFHDSIUGHSIOGISGHIDHGIUDHGISDOFJIJSHDVOLJVIJHSDIUGHOISJFDIOHUISHFUIDFSDFSDF

# Known limitations
- First problem in ass
- Second problem in ass
- Third problem in ass

# Build
NeoForge 1.21.1, JDK 21: `./gradlew jarJar` → jar in `build/libs/`

Patched physics natives live under `natives/` (Sable Rust workspace fork + aperture clipping extension + atlas approach).
Windows x86_64 build script: `natives/build-windows.ps1`
If you don’t build natives, mod still runs — you just lose aperture contact clipping.

# About Immersive Portals (upstream)
Immersive Portals does see-through portals, portals-in-portals, seamless teleportation, and non-euclidean weirdness.
If you like this fork, please consider starring the upstream project too — they did the heavy lifting.

- CurseForge: https://www.curseforge.com/minecraft/mc-mods/immersive-portals-mod
- Modrinth:  https://modrinth.com/mod/immersiveportals
- Website:   https://qouteall.fun/immptl/
- Wiki:      https://qouteall.fun/immptl/wiki/

![immptl.png](https://i.loli.net/2021/09/30/chHMG45dsnZNqep.png)
