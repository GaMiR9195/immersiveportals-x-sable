# 0.6.0 — ropes through portals, cross-dimension lag, jointed cross-dimension transit, rejoin

Version stays 0.6.0. Native ABI 7 → 8 (the natives must be rebuilt; an ABI-7 library keeps
working, the new features simply stay off).

## 1. Lag while a body touches a portal to another dimension

**Cause (native).** While a body straddles a portal it has one collider per piece: the native
collider in the source chart and an image collider in the destination chart. Both see the
WHOLE voxel grid of the body. `dispatcher.rs::world_vs_world` built a cuboid manifold for every
overlapping voxel pair returned by `find_collision_pairs`; piece filtering happened only later,
in `hooks.rs::ipl_clip_contacts`, which *neutralizes* contacts in place (the solver still
iterates them, and every manifold also costs a `manifold_info_map` entry). The image of the
not-yet-passed part sits behind the exit portal; for a nether portal that is usually solid
netherrack, so a ship touching the opening produced thousands of manifolds per substep.
Same-dimension test portals usually have air behind them, which is why the lag looked
dimension-specific.

**Fix.** `LevelCollider` now carries `piece` (0 = native collider; images get their node from
`setImageNode`). `IplPieces::voxel_may_be(node, voxel)` is a conservative superset of
`classify_voxel`: true when the label already is `node`, or when the voxel lies within 8 blocks
of the label-time rectangle of an edge that can relabel it. `world_vs_world` skips every pair
whose body voxel fails it, on both sides, before any manifold exists. The contact hook still
does the exact per-contact classification, so behaviour is unchanged; only the work that was
thrown away is gone. A unit test checks the superset property against `classify_voxel`.

## 2. Ropes (Simulated `ServerRopeStrand` and any other native rope)

**Cause.** Rope points are free native bodies of their strand's chart. When the body a rope
hangs from is partly through a portal, the points that followed it stay where physics puts
them: behind the portal plane in the source level, i.e. inside the wall the portal is cut into.
They collide there (rope class vs level class of the same chart) and push the ship through the
rope joints — "phantom ground" and sessions that never settle.

**Fix (generic, no mod-specific code).** `IplRopeTransit` samples every strand's points after
each physics step. A point whose own segment between two samples passes a portal rectangle gets
a route hop; passing back through the same rectangle pops it (a way-back face of the pair is
recognised, opposite hops cancel). Its collider is re-targeted with the new native
`routeRopePoint`: collision group of the destination chart plus a portal prefix equal to the
composed isometry — exactly what a body image does. The point body, its joints, the owning mod's
logic and its rendering are untouched. Winches that add/remove points at the strand start are
followed. After a same-dimension group flip moves the ropes, routes are re-based (`onRopesMoved`).

Limitations: rope *rendering* through a portal is still the owning mod's straight segment in the
source level; a rope fixed to the world on one side still blocks the body's flip (the body keeps
living on its projection, which is now collision-correct); ropes do not move between dimensions
(they are level-bound objects of the owning mod).

## 3. Jointed groups may change dimension again

Transit groups are resolved from Sable's `SubLevelHelper.getConnectedChain()` instead of a
second native joint-component walk. Every connected member is re-created in the destination
level with the same UUID and plot contents in one server call; actor-owned joints re-attach
through Sable's normal load path. Native solver state is not carried over, avoiding duplicate
joints and stale warm starts.

## 4. Coupler → exact rejoin of a single body

The lifecycle held every path while the group's *bounding box* intersected the aperture. A U or
ring spun around a two-sided portal has the portal inside its box forever, so the session never
closed and the part that had passed kept flying with the portal offset.

Now a single body is considered cut by a portal only through its aperture: if none of its
parent-side or passed material has a voxel centre within √3/2 of the plane inside the rectangle
(grown by √3/2) — the exact condition for a unit voxel to touch the plane in any orientation —
and no descendant aperture is occupied, its two sides meet only around the frame. The minority
rejoins the majority frame and the path closes normally. A body really cut by the aperture always
has such a voxel, so legitimate straddles are unaffected. Cross-member splits (links joined by
joints or springs, not by material) keep the previous envelope rule. No timeout, no velocity
heuristic. The old regression that asserted the opposite (U keeps both arms forever) was changed.

## Group entry context

`IplGroupTransitContext` (opens the group's paths on every member at the first link's entry) is
kept: it is what lets a chain flip coherently through one root. Its cost was the empty images of
untouched members colliding with destination terrain; with §1 those produce no manifolds.

## Verification done here

- `cargo check` / `cargo test` of `natives/rapier` (13 tests incl. the new cull test).
- `tests/lifecycle/run.sh`: 4416 + 2897 assertions pass.
- Not verified in game. Use `-Dipl.perfDiagnostics=true` to compare frame/step times.
