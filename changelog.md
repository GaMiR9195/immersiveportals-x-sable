# Changelog

All notable changes to this project are documented in this file (most of the time).
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project tries to adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.7.0]

Create contraptions on ships go through portals with their ship, particles fly through portals,
every camera works with portals, and contraptions on ships no longer vanish.

### Added

- **Create contraptions through portals** (natives ABI 10). A contraption on a ship (bearing, windmill,
  piston, pulley, gantry, cart) lives on the ship's projections, like its blocks, until the whole ship
  flips.
  - Pieces: contraption blocks carry piece labels like the ship's voxels, in the ship's label map
    (`IplPieceMap.contraptionLabels`, by contraption UUID and local block position). A block changes
    piece only when its centre passes the portal rectangle; its crossings are swept together with the
    voxels' ones at every physics sample, in time order (`IplPieceTracker`,
    `IplContraptionMaterial.crossings`). A new
    contraption starts in the piece of the block it hangs on. The labels end and flip with the ship's,
    reach clients with the voxel labels and the natives through `setContraptionLabels`. Physics,
    sessions, entity collisions and rendering all read them; no piece is guessed from a line to the
    block the contraption hangs on.
  - Physics: every image of the ship carries an image of the contraption at its place on the ship
    (created, moved and removed by the natives with the ship's images). Each collider keeps only the
    contacts of the blocks in its piece, a block lying across the opening split by its plane
    (`hooks.rs`, `IplPieces::classify_mounted`). A block changes piece the moment its centre
    passes the opening within a physics step, like the ship's voxels, not at the next label push.
    With a file `ipl-debug-mounted` in the game directory the natives log these contacts to
    `logs/ipl-natives-mounted.log`.
  - Sessions: a contraption block that touches an opening from the front or passes it opens the
    session like a ship voxel; the swept volume never opens one. A session stays open while a
    contraption block is in its piece, or while a turning contraption next to it can swing into it
    (one edge, never further), so a same-dimension portal pair cannot stack sessions. A contraption
    ends its crossing in its ship's flip: the flip waits only for its blocks that have not passed the
    portal plane yet, so none is left inside the terrain behind the exit; a block that went past the
    plane beside the frame keeps nobody waiting. Destination terrain is loaded for physics under the
    whole reach of the contraption.
  - Entities: Create's contraption collision runs once per piece, at the piece's pose, against the
    entities of the piece's level and the blocks of that piece only; a block lying across the
    opening collides on each side only with its part on that side, cut at the plane
    (`IplContraptionCollision`, `IplBoxCut`). The player collides with the part that came through a
    portal, also when the ship's level is another dimension, and is not pushed by the part that is
    gone.
  - Rendering: each piece of the ship draws its part of the contraption at its pose, cut by the plane
    of the opening from the moment a block touches it, like the ship's own blocks
    (`IplContraptionShape.split`, `IplContraptionRender`). Projections are drawn by Create's renderer
    in any dimension; the native part is handed over from Flywheel while it approaches an opening, so
    the cut never waits for the hand-over.
  - Moving to another dimension: plot entities move with the ship (`IplPlotEntityTransit`);
    contraptions keep their controller when the ship gets another plot slot (it is moved before the
    contraption is sent, so clients hang it on the same block as the server) and are driven by it at
    once, a moved contraption starts at rest at its angle, mobs riding them move with them, and the
    contraptions of a ship still hidden behind its ghost are drawn right away.
  - Performance: the per-tick portal lookup of every ship bounds its contraptions by a cached
    radius around their anchor instead of walking their blocks (`IplContraptionKind.reach`).
  - Other mods: contraptions are a registry of kinds (`IplContraptions.register`,
    `IplContraptionKind`); Create's is one. A kind reports a structure's blocks, local frame and
    reach, and pieces, sessions, collision and rendering handle it like Create's.
- **Particles through portals** (`ParticleTeleportation`). Any particle, vanilla or modded, that
  moves by `Particle.move` or by its velocity and crosses a teleportable portal from its front goes
  on beyond the exit, with its velocity turned by the portal, into the other dimension for
  cross-dimension portals. A wall behind a portal no longer stops it. Particles that follow a path
  of their own (portal swirls, enchanting glyphs) are left alone. Only portals within 64 blocks of
  the camera are checked, nothing at all when there are none.
  - A particle reaching across an opening is drawn on both sides of it: its own level shows the part
    on its side of the plane, the level beyond shows the part that passed, each quad cut exactly at
    the plane (`ClippedQuadConsumer`). It crosses without popping or losing half of its quad, the
    same in every view, and stays whole while it leaves the exit (also of a one-way portal). This
    covers every particle drawing into the particle buffer, modded ones included (those without a
    known quad size get a generous reach); particles with a renderer of their own
    (`ParticleRenderType.CUSTOM`) are left alone.
  - In a portal view, a particle is cut at the view's exit plane instead of being hidden until its
    centre is half a block past it, so the part already out of an exit is always drawn and nothing
    behind the exit pokes out of it.

### Fixed

- **Bearings, pistons and pulleys snapping back on a new client block entity** (a ship moved to
  another dimension, a chunk sent again). Create eases each update in from the client's angle,
  which is 0 for a new block entity: the contraption jumped to its assembly angle and caught up
  over the next ticks. The first update is now taken as is (`CreateBearingSyncMixin`, clockwork
  and linear actuators too; windmill, propeller and borehead bearings included).

- **Entities with extra spawn data in another dimension.** NeoForge applies that data (the
  structure of a Create contraption, data of many modded entities) to the entity of the player's
  level, but a packet from another dimension is handled with that dimension as the client level:
  the data went to an entity with the same id here, or nowhere. A contraption arriving in another
  dimension came without its structure and was discarded by Create on the client
  (`MixinClientPayloadHandler`, synced attachments too).

- **Windmills and other kinetic sources stopping after a ship moved to another dimension.** The
  neighbour pass of the copy reset every Create kinetic block: a source (windmill, water wheel,
  motor) never applied its speed again, and everything it drove paused or stood still. Copied
  kinetic blocks now keep the speed, source and network they were saved with, like blocks loaded
  from disk, so bearings keep turning through the move without a pause or a snap to a whole angle
  (`IplCreateKinetics`).
- **Ship contraptions lost at the end of a crossing.** Sable's box queries return the entities of
  ship plots too, so a flip took a ship's contraptions and seats for riders of its hull and threw
  them to a mapped plot position, and the contraption vanished. Entities living in a plot now stay
  with their body (`IplShipFlip`). A ship moving
  to another dimension also no longer drags the world entities around it along.
- **Flywheel animations freezing around contraptions.** A contraption whose structure never reached
  the client got a Flywheel visual that failed every frame and stopped the other visuals of the
  frame (bearings, shafts); such contraptions are visualized only once their structure arrives
  (`IplFlywheelFallback.refuses`).
- **Create contraptions on ships vanishing.** A contraption whose ship arrived after it (a ship moved
  into another dimension, a ship re-sent by the server), or whose ship was missing for one frame,
  lost Sable's render pose for good and was drawn at plot coordinates, far away, until it was
  reloaded. Poses are recreated as soon as the ship is there (`IplContraptionRenderPoses.ensure`).
- **Third-person camera mods and portals** (`PortalCameraClip`). The cross-portal view placed the
  camera on its own fixed 4-block ray behind the eyes, ignoring where the camera really was: shoulder
  cameras did not cross around the middle of the portal, and zooming in or out when switching the
  view stayed frozen at the portal. Now the block collision of any camera (vanilla's or a mod's)
  passes portal openings, and the view is rendered from wherever the camera itself ends up.
- **Particles missing in portal views.** A portal view drew no particles at all once four portals
  had been rendered in the frame, and dropped every particle less than half a block past the view's
  exit: what came out of a portal stayed invisible right at the exit.
- **Your own player seen through a portal and EMF / ETF.** While the first-person player is drawn
  in a portal view or as its projection beyond a portal, the camera counts as third person
  (`MixinOptions_SeenFromOutside`): animation packs for Entity Model Features (Fresh Animations and
  others) and first-person body mods no longer hide parts of the model there.
- **Camera mods in portal views** (`FrameCameraInputs`). Every portal view set its camera up again,
  running every camera mod for a camera that is not the player's: shoulder cameras (Punchy and
  others) collided it with the world beyond the portal and hid or faded your own player there and
  in the rest of the frame. A portal view now takes the player's camera of the frame, so camera
  mods run once per frame; the cross-portal camera probe is set up once per frame too.
- **Contraptions vanishing at some angles and in portal views.** Sable culls an entity of a ship
  by a small box around its position, for a contraption its anchor: sails in view disappeared
  while the bearing was off screen. Contraptions are culled by everything they cover
  (`IplContraptionRender.inView`).
- **Entities of ships in portal views.** A portal view kept an entity by the side of its exit its
  position was on; for an entity living in a ship's plot that was its plot position, far away, so
  a contraption drawn by Create's renderer (near a portal, cut by one) was missing from views into
  its ship's dimension. Such entities are tested where their ship draws them
  (`SableBridge.clientDrawnPoint`).
- **Shaders: smoothed uniforms per view** never applied: the Iris smoothing mixin failed at startup
  (a multi-target mixin needs `remap = false` on its shadows).
- **Ship collision cut by an oblique portal.** An entity against the blocks of a straddling ship
  felt a generous box around each block split by a tilted portal plane, sometimes reaching past
  the block; boxes are now cut per slice and never grow (`IplBoxCut`).
- **Particles frozen in another dimension.** A level other than the player's ticked its particles
  only while a portal into it was within 10 blocks of the player: particles there (spawned there or
  gone through a farther portal) stood still and never died. They now always move and age
  (`ClientWorldLoader`).

### Performance

- The per-frame contraption diagnostics (a walk over every entity of each rendered level) are off
  unless `-Dipl.contraptionDiagnostics=true` is set; they also trace every contraption label decision.

## [0.6.2]

Bug fixing and cleanup: nothing behind a portal leaks into its view any more, entities can be hit
and used through portals, and the third-person camera behaves around portals.

### Added

- `/ipxsable portal_shadow purple_glass|black|false` (client setting, also in the config screen):
  what portals cast into shader pack sun shadows. `purple_glass` is tinted and translucent like
  packs treat nether portal blocks, `black` is opaque like a thin plate, `false` casts nothing.
  Other `/ipxsable` subcommands are passed on to the server.

### Fixed

- **Shader packs keep their temporal state per view** (`IrisViewContexts`). Portal views render
  the Iris pipeline again inside the frame and used to overwrite what the player's view carries
  into the next frame.
  - `previousCameraPosition`, `gbufferPrevious*`, built-in smoothed uniforms and `smooth()`
    custom uniforms are kept per view and advance once per frame: motion blur no longer smears the
    whole screen with same-dimension portals, smoothing runs at the real frame rate.
  - Per-frame uniforms are uploaded for every view, so same-dimension portal views no longer reuse
    the player's matrices in their deferred and composite passes.
  - History buffers (render targets a pack never clears, e.g. TAA history), custom images that are
    not cleared, shader storage buffers and the smoothed center depth are swapped per view when
    views share a pipeline (`IrisHistoryTargets`); views on their own pipeline copy nothing.
- **Shaders: uniforms derived from the player follow the view.** Eye brightness, biome, sky color,
  eye altitude and eye position are read where the player appears behind the portal, in that
  world, just in front of the destination portal (`IrisViewSpace`): fog, sky and biome effects
  behind a portal match that side.
- **Portals are covered by the fog of the view they are seen from** (`PortalFogOverlay`), with and
  without shaders: distance fog, underwater, lava, powder snow, blindness and darkness. The content
  used to show through fog, most visibly underwater.
- **Shaders: portal content is composited opaquely.** It used to be blended with its alpha, which
  left a faint tint of the view behind it and a visible seam on entities standing in the portal.
- **Shaders: the area behind a portal no longer bleeds into bloom, TAA and translucent things in
  front of it.** Every view, nested ones included, draws its portals as opaque surfaces textured
  with last frame's content of that portal alone (`IrisPortalSurface`); the fresh content
  replaces them afterwards.
- **Shaders: the first-person hand and name tags stay in front of portals** (`IrisForegroundMask`),
  also when standing right against one: they are kept out of the portal stencil.
- **Shaders: `purple_glass` portal shadows are tiled by blocks** from the portal's center instead of
  stretching one texture over the whole portal.
- **Nothing leaks out of a portal view.** Sub-levels, entities, block entities and particles behind
  a portal's exit could show up "painted" on the portal surface, especially in portal loops.
  - Veil filled the clip uniform of every vanilla shader with zeros after it had been set, which
    disabled the portal cut. Veil's copies of that uniform are now disarmed, and the planes are
    written again at the end of every `ShaderInstance.apply()` (`ShaderInstanceClipMixin`).
  - A program made current with raw GL (Veil's uniform upload) never received the planes. The
    program that draws is now checked right before every draw while a cut is active
    (`IplProgramBindHook.beforeDraw`, one `glGetInteger` per draw, nothing otherwise).
  - The after-level render stage (Veil fixed buffers, the staff overlay, mods drawing at
    `AFTER_LEVEL`) is clipped as part of the view it belongs to, not with the planes of an
    enclosing portal or not at all.
  - An object's own cut (an entity crossing a portal, the far-side image of an entity) no longer
    replaces the portal cut; both are applied together (new clip slot `IplClipPlanes.OBJECT`).
  - Veil level-perspective renders (Sable's sky-light shadow map, other mods' cameras) take no
    portal cuts and render no portal worlds into their target.
- **Hitting and using entities through portals.** Entities on the far side are picked like vanilla
  does it (pick radius, entity reach, a block in front wins). The server then runs vanilla's own
  handling with the player standing, for that one action, at its eyes as seen from the far side, so
  reach, knockback direction, sweep attacks and enchantments work in the right place
  (`CrossPortalPresence`). Riding something across a portal is undone.
- **Through-portal targeting in third person** starts at the player's eyes, like vanilla's pick,
  instead of at the detached camera. A local entity hidden behind a portal is no longer kept as
  the crosshair target.
- **Third-person camera behind a portal collides with blocks on the far side** instead of flying
  into walls there. The camera ray is split at the opening: blocks on this side stop it before the
  portal, blocks behind the exit stop it on the far side, using vanilla's camera probe.
- **Third-person view bobbing** stays on in the cross-portal view and only fades in a thin layer
  (under 0.4 blocks) around a portal plane near the camera.
- **Particles, name tags and beams face the portal camera.** In a view rotated by portals (portal
  passes, the cross-portal view, GUI portals) the camera's orientation is the one of the rendered
  world, so billboards face the viewer (`PortalPassView`).
- **Entities crossing a portal are cut and mirrored from the first frame.** Crossings are found every
  frame at the rendered positions (`CrossPortalEntityRenderer.updateCrossings`) instead of from the
  tick collision state, which lagged up to three ticks behind and stayed stale for a tick after a
  teleport. Works with Iris shader packs: the cut is the injected clip distance of every program.
- **No backwards body for a tick after a rotating portal.** The player's body and head turn with the
  view at once, previous-tick values included. Other entities keep facing the way they move (their
  velocity was already turned), on the server and on every client at once.
- **Camera mods and portals** (camera shake, roll, sway, FOV and bobbing changes, e.g. Punchy). The
  player's view evaluates them once per frame and portal passes and camera probes reuse the result
  (`FrameCameraInputs`), so the portal image keeps lining up with the view around it.
- **Flywheel and portals.** A portal leading far away in the same level no longer makes Flywheel
  recreate every visual twice per frame (`FlywheelEngineOriginMixin`), and every client level is
  served by its own visualization manager, so block entities are no longer drawn twice or not at
  all depending on timing.
- **Shaders: no see-through slit where something crosses a portal, from either side.** The portal
  area takes over the pixels right in front of its opening (its stencil is pulled toward the camera
  against TAA jitter). A pass looking out of an opening draws whatever crosses it whole, so those
  pixels show the object instead of what lies behind it: an entity's far-side image through the
  portal it stands in (`FrontClipping.setupProjectionClipping`), the entity itself seen from the
  other side (`FrontClipping.setupCrossingClipping`) and sub-level blocks at an aperture
  (`IplPieceRender`).
- **Entities crossing two portals at once are cut by both** (object cuts now use two clip slots).
- **Render distance with Sodium.** Changing it rebuilt only the current level; the levels seen
  through portals kept the old distance and were rebuilt in the middle of a portal pass, so after
  going through the portal the world stayed at the old distance. Portal passes no longer rebuild a
  level's renderer, and the other levels follow the change before the next frame.
- **Shaders: water fog over portals is steady.** Vanilla thins its underwater fog with the time
  spent underwater, shader packs keep theirs constant. Portals take the adapted fog, so they no
  longer turn deep blue on diving in, fade out afterwards and change with every dive.
- **Shaders: `purple_glass` portal shadows are animated** with Sodium, which animates only the
  textures drawn in the frame.
- **Shaders: going through a portal keeps the shader pack's temporal state.** The view through the
  portal becomes the player's view and the old view the one back through the reverse portal:
  motion blur, TAA history and smoothed uniforms continue across the teleport, also in the
  views of portals seen through it.
- **Create contraptions on sub-levels no longer vanish when a portal is on screen** (windmill and
  other bearings, trains). Sable keeps their render poses in one map and pruned it against every
  level whose Flywheel frame started, so a level seen through a portal dropped the player's level's
  poses. The poses are now kept per level (`IplContraptionRenderPoses`).
- **Portals can no longer be pushed** by pistons or by Create contraptions, belts and ejectors.
- **Shaders: the physics staff beam starts at the staff tip.** Sodium fires the terrain layer stages
  inside Iris' shadow pass too; the beam's route for the frame was taken there, from the shadow
  map's matrices, so its start sat blocks away and jumped with the shadow grid. In first person the
  tip is read in the frame Iris draws the hand in (bobbing in the model-view, a depth-squashed
  projection), so the beam leaves the held staff instead of a point near the body.

### Performance

- Shaders: the portal layer buffers are no longer cleared every frame (only the first layer's
  stencil is), and the view of a draw is found without copying the portal path.
- **Screen-space portal culling** (`PortalScreenCulling`). Each portal is reduced to the screen
  rectangle of its opening and intersected with the rectangle of the portal it is seen through, so
  nested portals and portal loops shrink and stop on their own. Replaces the frustum box test.
  Compare with `/imm_ptl_client_debug early_cull_portal_disable` (and `_enable`).
- **Gaps between blocks no longer trigger a whole world pass.** A portal's world is rendered only
  when enough of its opening is visible (`-Dipl.portalMinVisibleFraction`, default 0.0025 % of the
  screen, at most a quarter of the portal's own rectangle). A portal seen through a gap last frame
  does not stall the GPU waiting for this frame's query.
- The mod event that filters portals runs only for portals that are actually on screen.
- **No GPU wait every frame for a hidden portal.** An opening that completed queries found hidden
  two frames in a row, while neither the view (under 0.5 blocks, 3 degrees per frame) nor the portal
  moved, is skipped without waiting for this frame's query. Teleports and camera jumps always wait,
  as before. Before, such a portal stalled the pipeline in every frame it stayed on screen.
- **Clip planes cost nothing while unchanged.** Every program remembers its clip uniform location
  and the equations it holds; the hundreds of plane changes per frame (passes, suppression around
  screen-space draws, per-piece cuts) upload only what differs, without GL queries. The clip-space
  transform is computed once per pass, and `-Dipl.portalDiagnostics` reads GL state back at most
  once a second per program instead of after every upload.
- **Sodium cave culling in the player's own view.** IP replaced Sodium's occlusion-culling decision
  with its portal-pass rule, which is "off" outside portals, so the main view drew every cave
  section. The main view now uses Sodium's own decision.
- **Cave culling in portal passes at any distance.** For flat portals Sodium's section search starts
  from the sections the opening passes through (`PortalApertureSections`), not from the camera's
  section behind the destination plane, so occlusion culling is exact there and runs at every
  distance (it used to run only within 5 blocks of the portal).
- **Portal passes are bounded in the world, not only on screen.** The four planes through the eye
  and the edges of a pass's screen rectangle, carried through the portal, cull sections, entities
  and block entities that cannot reach its pixels (`PortalScreenCulling.getRegionPlanes`). Nested,
  oblique and partly covered portals render a fraction of their world.
- **Cave culling from a portal's opening keeps every way out.** A line of sight enters an opening
  section in its middle, not through a face, so the section's face-to-face visibility no longer
  blocks it: a dead-end tunnel behind a portal used to hide what lay beyond. The search beyond is
  Sodium's own, and sections fully behind the destination plane are culled in such passes.
- **Outer culling behind several portals, also inside portal passes.** Whatever lies fully behind
  a nearby portal (up to four) and inside its opening is culled, in the player's view and in every
  pass for the portals it shows. A portal counts only if the renderer will draw its opening: its
  own visibility tests, the mod event, and the pass's clip plane (`PortalRenderer.getOccludingPortals`).
- **Sodium cloud meshes are cached per view** (`CloudGeometryCache`), the Sodium counterpart of
  `CloudContext`: portal passes into the same dimension no longer rebuild the cloud mesh every frame.
- **Sodium's entity culling stays on in portal frames.** It was off in every frame with a visible
  portal; now a pass uses it whenever it built the current section graph
  (`WorldRenderInfo.getCurrentPassId`).

### Compatibility

- **EntityCulling** is supported and no longer reported as incompatible. Its culling, traced from
  the player's camera, is paused in portal passes (which use their own culling); entities drawn in
  a portal pass count as seen (in camera and not culled), so its tick culling no longer skips the
  full tick of mobs visible only through a portal (heads twitching on a frozen body); its tracing
  thread reads the player's level instead of the level IP is rendering at that moment.
- **More Culling** works unchanged: its frustum and camera checks go through the pass's own frustum
  and camera, which now include the portal pass bounds above.

### Internal

- Dead code removed (unused occlusion query paths, no-op clipping wrappers, an empty diagnostics
  hook, commented-out blocks); fully-qualified class names replaced with imports; comments that
  described old behaviour rewritten to describe the current one.
- `-Dipl.portalDiagnostics=true` reports, for sub-level draws that must be cut, the program, its
  clip writer, the clip uniform read back from the GPU next to the expected value and the enabled
  clip distances.

## [0.6.1]

Portal routing for block-entity models: ropes, springs, chains and shafts that pass a portal are
drawn on the right side of it.

### Added

- **Visual router for block-entity models** (`render/route`). Anything drawn by a block-entity
  renderer, a Flywheel visual or a Veil shader near a portal is recorded once per frame, cut exactly
  at the opening, and each part is drawn on its own side: what went in comes out of the exit, what
  did not stays. Works with world portals, portals on bodies, dimension stacking, several portals at
  once and nested portals.
- **Parts follow their holder** (`IplRouteSolver`, `IplRouteTopology`). The side of each part is
  found by walking the model from the block that holds it; a step enters a portal only through its
  finite opening. Nothing is left hanging at the entrance or beyond the exit when the holder pulls
  it back, and a part that went around the frame never counts as passed.
- **Openings are a bit larger than their frame** (`-Dipl.sable.apertureGrace`, default 0.125
  blocks, max 0.5, same value on client and server): bodies Rapier rests slightly inside the frame,
  or that slide along the rim, pass cleanly. IP's own rendering and teleportation are unchanged.
- **One clip system for every renderer** (`IplClipPlanes`, `IplClipShaderInjector`). Portal and
  aperture cuts are evaluated in clip space from each shader's own `gl_Position`, so vanilla,
  Sodium, Iris, Flywheel, Veil, Sable sub-levels and other world shaders are cut the same way. The
  portal cut covers the whole portal pass; only screen-space work is exempt. LOD renderers can use
  `IplClipPlanes.clipSpaceEquations(viewProjection, out)`.
- `-Dipl.portalModelDiagnostics=true` logs once a second what portal routing cost and every change
  of a model's routing or Flywheel hand-over.

### Changed

- **Commands live under `/ipxsable`**: `/iplsable_portal anchor|unanchor|list` is now
  `/ipxsable link_portal_to_nearest_sublevel true|false|list`.
- **Joints hold.** A body on springs, ropes, bearings or connectors is never moved through a portal
  wholesale by geometry; only a disagreeing minority of its blocks is repaired. Side-of-plane
  evidence counts only over the opening, never over the infinite plane.
- **Flywheel hands over by the same rule** (`IplFlywheelFallback`): a visual whose model passes an
  opening is taken out of Flywheel and drawn through the router; it goes back only after 0.75 s
  unwanted, so it no longer flips every frame.
- **A routed model is released by what it really drew**, not by its renderer's (often stale)
  bounds, so models no longer drop out of routing for single frames.
- **Routing is cheap**: recordings are flat reusable arrays, cuts allocate nothing per primitive,
  the coinciding faces of one doorway are one cut, models that only come near an opening draw as
  usual, and only block entities within 32 blocks of an opening are scanned.

### Fixed

- Ropes and other long models through a portal were also drawn behind and around the entrance.
- Parts of a model vanished or switched sides near the rim of a portal; a link pushed into a portal
  by its far end was never routed.
- Models flickered while passing a portal, and a rope near a portal could cost hundreds of
  milliseconds a second.
- Fast bodies' models reached across an opening for a frame before they were routed.
- Block entities of a body (cogs, chests, signs, swivel bearings, springs) showed up on the far side
  of the exit, or vanished on the near side.
- Create cogs (any Flywheel visual) vanished near and inside portals until a block update: Sable's
  `BlockEntityStorageMixin` left removed visuals in Flywheel's lookup (`FlywheelStorageAccessor`,
  `FlywheelStorageVetoMixin`).
- Block entities drawn beside Sable's own pass, and routed models, are culled against the frustum
  of the pass.
- Ropes of a crossing group flew across the portal distance at the flip (`IplRopeChartSync`), and
  links between bodies flipped in one crossing stretched for a frame.
- Veil's dynamic-buffer recompile failed (`undefined variable "pos"`): the clip writes now go at
  the exits of `main` and Veil runs the clip preprocessor last.
- IP's pooled GL buffers and vertex arrays are real GL objects, so Veil's DSA calls work on them.
- Removed the sign/block-entity specific clipping patches.

## [0.6.0] — WIP

Rewrite of the foundation: the `ipl_sable:sublevels` hosting dimension is gone. Sub-levels live
natively in exactly one level (stock Sable model); the far side of a portal is an Atlas
**projection** of the same body. Deep notes: `README060NEW.md`.

### Changed

- **Projections instead of clones.** A straddling body gets an image collider of the SAME rigid
  body in the destination level's Atlas chart (`image pose = portal isometry × body pose`), and
  its render data is drawn again at the mapped pose. One body, one source of truth; forces on
  the image act on the real body.
- **Pieces instead of clip volumes.** Which material went through which portal is a property of
  the material: every plot voxel near a portal carries a piece label (`IplPieceMap`, one byte).
  `IplPieceTracker` samples every body at each physics substep boundary (stock natives: tick
  tail). A voxel whose centre passes the finite rectangle front to back joins the session's
  piece, back to front returns to the parent; passing the plane beside the frame changes
  nothing. Crossings of sibling and nested apertures apply in observed time order with a
  per-voxel cursor; observed rotation is split into short slerp arcs (≤ 128 per sample). No
  tick-ahead forecast, no 32-block teleport cutoff; only voxels touching the aperture zone are
  split by the plane. Natives, entity collision, raycasts, staff, outlines and rendering all use
  this one material rule.
- **Sessions.** Keyed by portal path (`StraddleKey`), one piece each. A piece crossing a portal of
  its destination level opens a child session with the composite isometry; a piece may have
  several child portals and a body several root portals. The depth-16 and 64-session caps are
  gone (the label byte still limits a body to 255 pieces). Both faces of a doorway may own
  sibling pieces; same-normal duplicates of one mapping are canonicalized. A session opens on an
  observed crossing or on real contact with the aperture.
- **Settlement per body** (`IplSessionLifecycle`, tick tail). Labels are history and only the
  body's own material is ever moved: an aperture wholly in front of or behind the body proves
  the direction; two sides that meet only around the frame (a ring spun around a two-sided
  portal) rejoin the larger side; a path without material and without a block in its aperture
  slab closes. Once no aperture holds anything, the body is collected into the frame of most of
  its material. No timeouts, no velocity heuristics.
- **Linked groups** (`IplLinks`). Links come from Sable only: the actor dependency graph
  (`sable$getConnectionDependencies`: bearings, springs, rope holders, connectors) and every
  constraint created through Sable's pipeline between two sub-levels, so mods that publish no
  dependency are covered too. The graph is undirected and resolved once per tick, only when a
  flip is pending. Links never touch entry: each body opens its own sessions, so a jointed build
  goes into a portal exactly like a single body. A group flips through one isometry once every
  member with blocks completed the same crossing; zero-block connectors follow. Until then the
  passed members live on their projections and nobody is pulled into another member's frame.
  A rigid constraint to the static world anchors the group (no flip); free constraints to the
  world (staff, handles) do not.
- **Ropes** (`IplRopeRouting`, native ABI 8). While a body a rope hangs on is split by a portal,
  every physics sample walks the rope from its attachment: the attached block's piece gives the
  end point's frame, a segment passing one of the body's finite apertures enters that frame,
  passing back returns. Point colliders live in their frame's chart through the composed
  isometry, like a body image; a rope bending around the frame stays put. No per-point state.
  A same-dimension group flip moves eligible rope particles with the group (ABI 4 preflight:
  a rope tied to the world or to a body outside the group holds the flip); a group with ropes
  does not change dimension.
- **Flip.** Same dimension: pose teleport of the live body plus client interpolation rebase
  (`handoff`); internal joint warm starts are reset after a group move (ABI 7). Other dimension:
  native move into the destination level, keeping the same global plot slot when free; the
  client shows a ghost until the successor is compiled (3 s timeout). Up to 16 flips per body per
  tick; promoted child sessions keep their labels.
- **Rendering** (`IplPieceRender`). Every draw is one piece; per section and layer SKIP, FULL (one
  plain draw, the common case), RANGES (one multi-draw over the piece's quads, recorded per block
  at compile by `IplSectionRanges`) or SPLIT (zone blocks drawn by both pieces, cut by the
  aperture plane). Plans are cached per buffer and label revision; translucent RANGES/SPLIT draws
  follow the sorted quad order (`VertexBufferMixin`). Block entities draw once per piece plus one
  cut pass per edge; Flywheel keeps instancing everything but block entities at an aperture.
- **Interaction.** Entity collision clips per piece (`PieceCut`: zone blocks trimmed at the plane,
  step-up included; an entity's frame is the nearest piece material). Raycasts walk only the
  piece's blocks; entity lookups only touch pieces overlapping the query. Staff picks walk the
  whole ray range and store the full image path in NBT; invalid path seeds are rejected.
  Staff, picks and outlines route to the owner level of a projected sub-level.
- **Clients.** Sessions and labels are kept per coordinate frame: payloads of a frame not entered
  yet wait and replace the old frame at entry, so nothing blinks at a flip. Payloads carry their
  server tick and apply when the shared interpolation clock shows it. Label payloads are partial
  (changed sections, ≤ 64 per packet) and are sent only when labels or edges change. Transit
  ghosts draw their successor's pieces re-rooted through the crossing portal. Per-tick client
  caches are keyed by the client tick, not by the clock of whichever dimension IP has bound for
  the current portal pass.
- **Natives.** ABI 3: piece labels and edges per body (`setPieceSection`, `setPieceEdges`,
  `setImageNode`, `clearPieces`); a contact survives only on the collider of its point's piece.
  ABI 5/6: label-time plane, then the full label-time rectangle (32-double edges). ABI 7: joint
  warm-start reset. ABI 8: rope point routing and piece-aware voxel pair culling in the
  dispatcher. Older or stock natives open sessions without image colliders (split, drawn and
  flipped; the far side does not collide).
- **Physics stepping** (`atlas/IplFusedStep`). With IPSable natives one fused step per server tick
  drives every level, and levels that do not drive it update `lastPose` before the step; with
  stock natives each level steps itself, as in Sable. Ship-anchored portals follow their ships
  after the step in both modes.
- **Plots.** Slots are allocated over the plots of all levels, so plot coordinates stay unique.
  A plot read falls back to a foreign level's plot only while its owner is projected into the
  querying level; server writes into it run in the owner level, client writes are rejected.
- **Tracking and time.** Cross-dimension tracking follows what IP streams to the player (any
  loaded chunk under the ship's bounds, from any level, or proximity to an image). Sub-level
  packets are dimension-tagged; remote viewers and the singleplayer host get movement over TCP.
  All IP client levels tick their sub-level containers once per client tick (not while paused).
  Every dimension stamps snapshots, clock packets and start-tracking with one server-wide tick
  (`IplTimeline`); the client runs one interpolation clock for all levels (`IplSharedClock`),
  merging clock packets of all levels.
- **Create: Pocket Sized.** The scale handshake covers sub-levels of all IP client levels, a
  repeated scale no longer resets interpolation, and the sub-level frustum filter tests the drawn
  (mapped) box against the current pass.
- **Natives packaging.** The loader picks `sable_rapier_<x86_64|aarch64>_<windows.dll|linux.so|macos.dylib>`
  like Sable and falls back to stock natives with one INFO line (kill switch
  `-Dipl.sable.customNatives=false`). Libraries at the project root are packed as
  `natives_ipl/sable_rapier_binaries.zip.l4z` and extracted to `.sable/natives-ipl/`. New
  `natives/build-linux.sh`; release jars bundle all six platforms via `natives/build-cross.sh`.
- **Diagnostics.** Per-event logs are DEBUG; per-draw diagnostics need
  `-Dipl.portalDiagnostics=true`. `-Dipl.perfDiagnostics=true` logs every 5 s the client and
  server spatial-query splice, client projection-tree builds, client piece draws, the transit
  sample at every solver boundary, the Sable container tick and the transit controller.
- **Code.** Accessors instead of reflection, narrowed `catch` blocks, imports instead of qualified
  names, comments describe current behavior. Moved, merged and removed classes:
  `docs/renames-0.6.0.md`.

### Removed

- `ipl_sable:sublevels`, its datapack and the hosting/clone/pin/UUID-bridge code (~70 classes and
  mixins); `IplDimSwitchGuard` (it masked a cross-dimension stall fixed at its cause).
- The swept-volume crossing detector and everything built on it: per-session tubes and wide
  half-spaces, sibling priority, client entry prediction, early-open look-ahead, two-plane
  clipping of whole bodies, the entering-volume overlay, the commands `early_open_segments` and
  `visualize_sweep_detector`, `-Dipl.sable.clip.seamSupportMargin`, native clip regions of ABI
  1 and 2, and `tests/portal`.
- The shared group path context, the group-wide frame vote and the native joint-component scan:
  linked bodies are coupled only at the flip (`IplLinks`). The per-point rope crossing tracker
  is replaced by stateless routing (`IplRopeRouting`). `tests/lifecycle` went with them.
- The rope portal seam (`ipl.sable.ropePortalSeam`, `ipl.sable.ropeBreakStretch`), no-op portal
  rim stubs, `IplTrackingStamp` and the clock offset of the `move` RPC, `-Dipl.sable.clip.forceAll`,
  unused classes, native declarations and JNI exports Java never called, pass-through wrappers.

### Fixed

- **Tail of a body vanished at the end of a crossing into another dimension.** The last blocks
  still on the source side were see-through for the last ~1.5–2 ticks of the crossing. The server
  moves the body once it has fully passed, but the client still draws the poses from before; the
  transit ghost switched to its successor's sessions and labels (the state after the move) as soon
  as the successor was tracked, and that frame has no piece for the tail. The `move` RPC now
  carries the frame boundary like `handoff`, and the ghost keeps the frame it left until the
  delayed render stream reaches it; the successor takes over only after that.
- **Client lag while a body touches a portal into another dimension** (worse with every body of a
  jointed build that has an image there). Immersive Portals animate-ticks the remote level around
  the portal every other tick; Sable's sub-level animate tick got the portal images from the
  spatial-query splice and sampled them with their native pose. The query point landed far off
  the plot and `BoundingBox3i` turned the empty intersection into the gap box between them:
  thousands of bridged plot reads per image per tick on the client thread (vanilla profiler: ~75%
  of client time in `imm_ptl_client_tick`, 13 TPS). Animate ticks now run without images
  (`ClientLevelMixin`, `IplClientProjections.withoutImages`); the body keeps its own level's.
- **Jointed builds.** A spring (or any link) across a portal no longer teleports the partner
  that has not crossed, and its session holds until both sides crossed. Linked bodies that do
  not touch an aperture no longer get images, sessions, sweeps and label traffic: in a chain at a
  portal only the links that touch it do that work. A body jointed to the world no longer flips
  away from its joint.
- **Ropes.** Rope points left behind the portal plane no longer hold a ship on phantom ground;
  they follow the frames of the body they hang on, also when the rope bends around the frame.
- **Cross-dimension projections.** Client projection trees and render views were rebuilt on every
  switch between portal passes (each dimension has its own clock). Destination terrain behind
  a portal no longer produces thousands of discarded manifolds for a buried image.
- **Several portals at once.** An edge cuts, holds and flips only its own material (a parent voxel
  on the entry side or a child voxel on the far side); material that reached the other side
  without passing the rectangle is not split and does not hold the session. Both faces of a
  doorway may carry sessions together, so a U leaving through the front while entering through
  the back keeps both arms. A piece with two child portals is cut by both, and coplanar siblings
  agree between physics and rendering.
- **Timing.** A quick dip no longer removes the far half of blocks still drawn in the doorway, and
  blocks switch sides at the drawn pose, not ahead of it.
- **Phantom ground after a portal.** The natives get the new frame's labels right after a flip
  and drop the data of a body that changed id or scene; sessions do not linger after the last
  touch, and leftovers that went around the frame neither hold a session nor hang at an offset.
  A removed block resets its label.
- **Cross-dimension moves.** No stop-and-jerk at the exit (the successor starts from the right
  pose, with its previous pose mapped through the portal); no skip-ahead when the successor
  reaches the client; no loss of pace (one timeline and clock for all levels, the successor
  inherits the ghost's older snapshots).
- **Stepping.** Levels that did not drive the fused step kept `lastPose == pose` (entities were not
  carried, start-tracking sent a frozen interval); with stock natives only the last level
  stepped. Dedicated servers sent untagged UDP movement that could land in the wrong container.
- **Rendering.** Sections compiled during another level's portal pass rendered twice as dark (wrong
  shading flag). FPS no longer halves while a projection enters a portal (single-piece sections
  draw as before, mixed ones as one multi-draw, the plane only inside the aperture zone, no
  client entry prediction). No stutter from spatial queries scanning every client entity:
  portals are looked up by UUID, the server splice skips ships without an image into the level,
  and broken surrogate portals are not re-decoded per query.
- **Create: Pocket Sized.** No stop-and-jerk of a ship leaving into another dimension, no freeze when
  entering that dimension, no invisible projections from some angles, and correct portal cuts
  of scaled ships under vanilla shaders.
- A ring pushed through a portal is no longer cut by the infinite plane. After a same-dimension
  flip the old frame no longer announces an empty session list. `tests/render` and `tests/gl`
  follow the production API.
- **Creative Physics Staff across a cross-dimension move.** The drag session held the old body
  object: the move removed it, the session and its grab chain ended, and the next drag packet
  opened a new session with an empty chain in the destination, so the cursor point of the
  player's world pulled the body in the other world and it flew away. Sessions now continue on
  the successor (`IplGrabChain.afterCrossMove`, plot-slot shift included), the client re-binds
  its drag to the successor instead of releasing when the ghost goes, and a frame desync releases
  the grip instead of applying a goal of the wrong chart.
- **Staff grab/lock through portals.** The vanilla pick (which ignores portals) found walls,
  ground or frames behind the doorway and beat every body seen through it; such hits are no
  candidates now, and blocks flush with a portal plane no longer stop the through-portal ray. A
  body living in another dimension is resolved in its own client level. A client that forgot a
  body restarted its coordinate frame at 0 and every later seed was rejected: full syncs now
  carry the current frame (`trackFrame`). A pick on a transit ghost names the ghost's frame, and
  a seed whose chain does not end in the body's world is rejected instead of silently desyncing.
- **Staff beam path.** The beam no longer threads the grab-chain history portal by portal like a
  rope: every stretch of the traversed chain that composes to the identity is dropped (not only
  adjacent inverse pairs), a chain that folds to nothing draws a straight beam
  (`IplBeamRouteSolver`). No portal outside the chain is ever used; physics is unchanged.

### Known limitations

- Self-collision through a portal is not implemented (image and body are one Rapier body).
- A spring tied to the world, rather than to another sub-level, is invisible to Sable's link
  contract; its body flips alone.
- A group with ropes stays on its projection instead of changing dimension; ropes are still
  drawn by their mod as a straight segment in the source level.
- Multiple aperture cuts through one block are not fully represented by the one-plane
  renderer/entity collision. Range draws use triangles, so Iris packs that tessellate terrain may
  not draw piece sections at a portal.

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