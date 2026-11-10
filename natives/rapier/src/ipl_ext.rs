//! IPSable extensions to the sable_rapier natives, rebased onto sable 2.0.x (scene
//! handles are raw `Arc<PhysicsScene>` pointers; sable data lives behind `RwLock`, so
//! hook-time reads are properly synchronized).
//!
//! Exposed under the `ipl.sable.natives.IplRapierNatives` Java class — sable's own JNI
//! surface is untouched.
//!
//! Piece clipping (ABI 3): a body straddling portals is ONE rigid body with one collider
//! per piece (the native collider plus an image collider per session). Which piece a
//! point of the body belongs to is decided by [`IplPieces::classify`]: a per-voxel label
//! (history: the piece a block went to when it crossed an aperture) and, close to an
//! aperture, the side of its plane. A solver contact survives only on the collider of the
//! piece its point belongs to.

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JDoubleArray};
use jni::sys::{jboolean, jdouble, jint, jlong};
use marten::Real;
use rapier3d::glamx::DVec3;
use rapier3d::math::Vec3;
use rapier3d::prelude::ColliderHandle;
use std::collections::HashMap;

use crate::scene::{LevelColliderID, PhysicsScene};

/// Native ABI level probed by Java (`IplRapierNatives.nativeAbiLevel()`):
/// 3 = piece labels + aperture edges (`setPieceSection`, `setPieceEdges`, `setImageNode`).
/// 5 = label-time plot plane. 6 = complete reference aperture + observed material sweep.
/// 7 = internal joint warm-start reset after a group changes its native frame.
/// 8 = rope point routing (`ropeIds`, `ropePointPositions`, `routeRopePoint`) and
///     piece-aware voxel pair culling in the dispatcher.
pub const IPL_NATIVE_ABI_LEVEL: jint = 8;

/// Doubles per edge in `setPieceEdges`:
/// `[parent child  ox oy oz  nx ny nz  wx wy wz halfW  hx hy hz halfH  zone slack
///   pox poy poz pnx pny pnz pwx pwy pwz phalfW phx phy phz phalfH]` (`po`/`pn`: the plane in plot coordinates when the labels
/// were made).
pub const IPL_EDGE_STRIDE: usize = 32;

#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_nativeAbiLevel<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jint {
    IPL_NATIVE_ABI_LEVEL
}

/// One live portal crossing of a body: material of piece `parent` that passes the aperture
/// becomes piece `child`. Geometry is in the PARENT piece's scene frame (where the portal
/// itself lives); `normal` points to the parent side.
#[derive(Debug, Clone, PartialEq)]
pub struct IplPieceEdge {
    pub parent: u8,
    pub child: u8,
    pub origin: DVec3,
    pub normal: DVec3,
    pub axis_w: DVec3,
    pub half_w: f64,
    pub axis_h: DVec3,
    pub half_h: f64,
    /// Geometric split depth in the ABI-3 prefix. ABI 6 derives the current voxel
    /// support radius from transformed plot axes, not a predicted-motion slab.
    pub zone: f64,
    /// How far a voxel centre may lie on the wrong side of [`Self::plot_normal`] for its
    /// label and still be this edge's material (0 from the server: exact).
    pub slack: f64,
    /// The aperture plane in PLOT coordinates at the time the labels were made. A voxel of
    /// `parent` whose centre lay behind it, or of `child` whose centre lay in front of it,
    /// reached that side without passing this rectangle (around the frame, through the
    /// opposite face of a doorway): this edge must not cut it, whatever the plane says now.
    pub plot_origin: DVec3,
    pub plot_normal: DVec3,
    pub plot_axis_w: DVec3,
    pub plot_half_w: f64,
    pub plot_axis_h: DVec3,
    pub plot_half_h: f64,
}

impl IplPieceEdge {
    /// Whether the voxel of `label` centred at `centre` (plot coordinates) is this edge's material.
    #[inline]
    pub fn owns_voxel(&self, label: u8, centre: DVec3) -> bool {
        let s = (centre - self.plot_origin).dot(self.plot_normal);
        if label == self.parent {
            s >= -self.slack
        } else if label == self.child {
            s <= self.slack
        } else {
            false
        }
    }
}

/// Piece data of one body (see the module docs).
#[derive(Debug, Default)]
pub struct IplPieces {
    /// 16³ label arrays by section coordinate, index `x | y << 4 | z << 8`; absent = 0.
    pub sections: HashMap<(i32, i32, i32), Box<[u8]>>,
    pub edges: Vec<IplPieceEdge>,
    /// Piece of each image collider; the native collider is piece 0.
    pub image_nodes: HashMap<ColliderHandle, u8>,
    /// Labels changed since the last atomic edge publication.
    pub dirty_contacts: bool,
}

impl IplPieces {
    #[inline]
    pub fn label(&self, x: i32, y: i32, z: i32) -> u8 {
        match self.sections.get(&(x >> 4, y >> 4, z >> 4)) {
            Some(s) => s[((x & 15) | ((y & 15) << 4) | ((z & 15) << 8)) as usize],
            None => 0,
        }
    }

    /// Cheap conservative pre-test for the dispatcher: can `voxel` (plot coordinates) be
    /// classified as piece `node` by [`Self::classify_voxel`] this step? True when its
    /// label already is `node`, or when it lies near the label-time aperture of an edge
    /// that may relabel it. Everything else (the bulk of a body buried in the far-side
    /// terrain behind a portal) cannot produce a surviving contact for that collider, so
    /// no manifold is generated for it at all.
    #[inline]
    pub fn voxel_may_be(&self, node: u8, voxel: rapier3d::glamx::IVec3) -> bool {
        const BAND: f64 = 8.0;
        let label = self.label(voxel.x, voxel.y, voxel.z);
        if label == node {
            return true;
        }
        let centre = voxel.as_dvec3() + DVec3::splat(0.5);
        self.edges.iter().any(|e| {
            if label != e.parent && label != e.child {
                return false;
            }
            let d = centre - e.plot_origin;
            d.dot(e.plot_normal).abs() <= BAND
                && d.dot(e.plot_axis_w).abs() <= e.plot_half_w.max(e.half_w) + BAND
                && d.dot(e.plot_axis_h).abs() <= e.plot_half_h.max(e.half_h) + BAND
        })
    }

    /// Classifies the body's OWN surface witness, not the midpoint between colliders.
    /// `in_parent` maps any plot point to an edge's current parent frame. The observed
    /// centre segment supplies membership between label updates; no prediction distance
    /// or portal slab limits a piece that already crossed the finite aperture.
    pub fn classify(&self, plot: DVec3, in_parent: impl Fn(u8, DVec3) -> Option<DVec3>) -> u8 {
        let voxel = plot.floor();
        self.classify_voxel(plot, voxel.as_ivec3(), in_parent)
    }

    /// The dispatcher knows the material voxel even when its surface witness lies exactly
    /// on an integer edge, or a collision shape extends outside the nominal block cube.
    pub fn classify_voxel(
        &self,
        plot: DVec3,
        voxel: rapier3d::glamx::IVec3,
        in_parent: impl Fn(u8, DVec3) -> Option<DVec3>,
    ) -> u8 {
        let label = self.label(voxel.x, voxel.y, voxel.z);
        let centre = voxel.as_dvec3() + DVec3::splat(0.5);
        let mut piece = label;
        let mut after = -1.0;
        // Order by observed crossing time, not session/node allocation order. This also
        // supports already-armed nested edges within a single native substep.
        for _ in 0..self.edges.len() {
            let mut next: Option<(f64, u8)> = None;
            for e in &self.edges {
                if piece != e.parent && piece != e.child {
                    continue;
                }
                let Some(p) = in_parent(e.parent, centre) else {
                    continue;
                };
                let old = centre - e.plot_origin;
                let now = p - e.origin;
                let s0 = old.dot(e.plot_normal);
                let s1 = now.dot(e.normal);
                let forward = piece == e.parent && s0 >= 0.0 && s1 < 0.0;
                let backward = piece == e.child && s0 < 0.0 && s1 >= 0.0;
                if !forward && !backward {
                    continue;
                }
                let t = s0 / (s0 - s1);
                if t <= after {
                    continue;
                }
                let u = old.dot(e.plot_axis_w) * (1.0 - t) + now.dot(e.axis_w) * t;
                let v = old.dot(e.plot_axis_h) * (1.0 - t) + now.dot(e.axis_h) * t;
                let margin = if backward { 0.5 } else { 0.0 };
                if u.abs() > e.plot_half_w * (1.0 - t) + e.half_w * t + margin
                    || v.abs() > e.plot_half_h * (1.0 - t) + e.half_h * t + margin
                {
                    continue;
                }
                if next.is_none_or(|(best, _)| t < best) {
                    next = Some((t, if forward { e.child } else { e.parent }));
                }
            }
            let Some((t, target)) = next else {
                break;
            };
            after = t;
            piece = target;
        }
        for e in &self.edges {
            if piece != e.parent && piece != e.child {
                continue;
            }
            let (Some(p), Some(c)) = (in_parent(e.parent, plot), in_parent(e.parent, centre))
            else {
                continue;
            };
            let rel = p - e.origin;
            let centre_rel = c - e.origin;
            let cs = centre_rel.dot(e.normal);
            let mut reach = 0.0;
            for axis in [DVec3::X, DVec3::Y, DVec3::Z] {
                if let Some(p) = in_parent(e.parent, centre + axis) {
                    reach += (p - c).dot(e.normal).abs() * 0.5;
                }
            }
            // Only material on its label's side can be geometrically split. A U arm
            // that went around the frame cannot be stolen by the opposite face.
            let consistent = if piece == e.parent {
                cs >= 0.0
            } else {
                cs < 0.0
            };
            if !consistent
                || (piece == label && !e.owns_voxel(label, centre))
                || cs.abs() > reach + 0.05
                || centre_rel.dot(e.axis_w).abs() > e.half_w
                || centre_rel.dot(e.axis_h).abs() > e.half_h
                || rel.dot(e.axis_w).abs() > e.half_w
                || rel.dot(e.axis_h).abs() > e.half_h
            {
                continue;
            }
            let target = if rel.dot(e.normal) >= 0.0 {
                e.parent
            } else {
                e.child
            };
            if target != piece {
                return target;
            }
        }
        piece
    }
}

/// Marker test for contacts neutralized by `SablePhysicsHooks::ipl_clip_contacts`
/// (large finite separation with zero friction -- never produced by the narrow phase itself,
/// whose prediction distance is a few centimetres).
pub const IPL_NEUTRAL_CONTACT_GAP: Real = 1.0e12;

#[inline]
pub fn ipl_contact_neutralized(c: &rapier3d::geometry::SolverContact) -> bool {
    c.dist >= IPL_NEUTRAL_CONTACT_GAP * 0.99 && c.friction == 0.0
}

fn ipl_read_doubles(env: &JNIEnv, data: &JDoubleArray) -> Option<Vec<f64>> {
    if data.is_null() {
        return Some(Vec::new());
    }
    let len = env.get_array_length(data).ok()? as usize;
    let mut values = vec![0.0f64; len];
    if len > 0 && env.get_double_array_region(data, 0, &mut values).is_err() {
        return None;
    }
    Some(values)
}

fn ipl_unpack_handle(packed: jlong) -> ColliderHandle {
    ColliderHandle::from_raw_parts((packed >> 32) as u32, (packed & 0xFFFF_FFFF) as u32)
}

fn ipl_parse_edges(values: &[f64]) -> Vec<IplPieceEdge> {
    let mut out = Vec::with_capacity(values.len() / IPL_EDGE_STRIDE);
    for c in values.chunks_exact(IPL_EDGE_STRIDE) {
        // A NaN frame would make every comparison false and silently keep the label.
        if c.iter().any(|v| !v.is_finite())
            || !(0.0..=255.0).contains(&c[0])
            || !(0.0..=255.0).contains(&c[1])
        {
            continue;
        }
        let normal = DVec3::new(c[5], c[6], c[7]);
        if normal.length_squared() < 1.0e-12 {
            continue;
        }
        out.push(IplPieceEdge {
            parent: c[0] as u8,
            child: c[1] as u8,
            origin: DVec3::new(c[2], c[3], c[4]),
            normal,
            axis_w: DVec3::new(c[8], c[9], c[10]),
            half_w: c[11],
            axis_h: DVec3::new(c[12], c[13], c[14]),
            half_h: c[15],
            zone: c[16],
            slack: c[17].max(0.0),
            plot_origin: DVec3::new(c[18], c[19], c[20]),
            plot_normal: DVec3::new(c[21], c[22], c[23]),
            plot_axis_w: DVec3::new(c[24], c[25], c[26]),
            plot_half_w: c[27],
            plot_axis_h: DVec3::new(c[28], c[29], c[30]),
            plot_half_h: c[31],
        });
    }
    out
}

/// Piece data is not part of Rapier's shape revision. Without this explicit
/// invalidation a sleeping manifold can retain its old source-ground contacts until
/// the session disappears. Never hold the scene-data lock while acquiring sim_data:
/// solver hooks acquire those locks in the opposite direction.
fn invalidate_piece_contacts(scene: &PhysicsScene, body_id: LevelColliderID) {
    let (handles, body) = {
        let data = scene
            .sable_data
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let Some(info) = data.level_colliders.get(&body_id) else {
            return;
        };
        let mut handles = Vec::with_capacity(1 + info.image_colliders.len());
        handles.push(info.collider);
        handles.extend_from_slice(&info.image_colliders);
        (
            handles,
            data.rigid_bodies
                .get(&body_id)
                .copied()
                .or(info.static_mount),
        )
    };
    let mut sim = scene
        .sim_data
        .write()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Some(body) = body.and_then(|h| sim.rigid_body_set.get_mut(h)) {
        body.wake_up(true);
    }
    for handle in handles {
        if let Some(collider) = sim.collider_set.get_mut(handle) {
            // set_position marks POSITION even for an unchanged transform: re-run the
            // narrow phase and the piece hook, without changing shape/mass properties.
            collider.set_position(*collider.position());
        }
    }
}

/// Replace the aperture edges of a body (N x [`IPL_EDGE_STRIDE`] doubles, see
/// [`IplPieceEdge`]). An empty array keeps the labels but disables the geometric split.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_setPieceEdges<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    data: JDoubleArray<'local>,
) {
    if scene_handle == 0 {
        return;
    }
    let Some(values) = ipl_read_doubles(&env, &data) else {
        return;
    };
    let edges = ipl_parse_edges(&values);
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene
        .sable_data
        .write()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let Some(info) = sable_data
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    else {
        return;
    };
    let pieces = info.pieces.get_or_insert_with(Default::default);
    let changed = pieces.dirty_contacts || pieces.edges != edges;
    pieces.edges = edges;
    pieces.dirty_contacts = false;
    drop(sable_data);
    if changed {
        invalidate_piece_contacts(scene, body_id as LevelColliderID);
    }
}

/// Replace the labels of one 16³ plot section (4096 bytes, index `x | y << 4 | z << 8`);
/// `null` or an all-zero array drops the section (everything native).
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_setPieceSection<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    sx: jint,
    sy: jint,
    sz: jint,
    labels: JByteArray<'local>,
) {
    if scene_handle == 0 {
        return;
    }
    let mut data: Option<Box<[u8]>> = None;
    if !labels.is_null() {
        let Ok(bytes) = env.convert_byte_array(&labels) else {
            return;
        };
        if bytes.len() != 4096 {
            return;
        }
        if bytes.iter().any(|b| *b != 0) {
            data = Some(bytes.into_boxed_slice());
        }
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene
        .sable_data
        .write()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let Some(info) = sable_data
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    else {
        return;
    };
    let pieces = info.pieces.get_or_insert_with(Default::default);
    pieces.dirty_contacts = true;
    match data {
        Some(d) => {
            pieces.sections.insert((sx, sy, sz), d);
        }
        None => {
            pieces.sections.remove(&(sx, sy, sz));
        }
    }
}

/// Assign the piece of an image collider (`node` 1..255).
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_setImageNode<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    packed_handle: jlong,
    node: jint,
) {
    if scene_handle == 0 || packed_handle < 0 || !(1..=255).contains(&node) {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene
        .sable_data
        .write()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let Some(info) = sable_data
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    else {
        return;
    };
    let handle = ipl_unpack_handle(packed_handle);
    info.pieces
        .get_or_insert_with(Default::default)
        .image_nodes
        .insert(handle, node as u8);
    // Mirror the node on the shape so the dispatcher can cull foreign material early.
    let mut sim_data = scene
        .sim_data
        .write()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Some(collider) = sim_data.collider_set.get_mut(handle)
        && let Some(existing) = collider.shape().as_shape::<crate::collider::LevelCollider>().copied()
        && existing.piece != node as u8
    {
        collider.set_shape(rapier3d::prelude::SharedShape::new(crate::collider::LevelCollider {
            piece: node as u8,
            ..existing
        }));
    }
}

/// Drop all piece data of a body (no straddle left): contacts are no longer clipped.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_clearPieces<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
) {
    if scene_handle == 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene
        .sable_data
        .write()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let changed = if let Some(info) = sable_data
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    {
        info.pieces.take().is_some()
    } else {
        false
    };
    drop(sable_data);
    if changed {
        invalidate_piece_contacts(scene, body_id as LevelColliderID);
    }
}

// ---------------------------------------------------------------------------
// Atlas M2 (spec v3 §2.2): image colliders — the body's geometry projected into
// a far chart through a translation-only portal isometry (Tier 1). Contacts on
// an image act on the parent body EXACTLY via the engine's mapped-COM lever
// arms; there is no clone body, no servo, no feedback.
// ---------------------------------------------------------------------------

/// Create an image collider for `body_id` in the CALLING view's chart, with the
/// portal isometry `P = (R, t)`: translation `(dx, dy, dz)` and rotation quat
/// `(qx, qy, qz, qw)` (identity for translation-only portals — Tier 1). The
/// prefix maps the body's pose into the far chart. Returns the packed collider
/// handle (index << 32 | generation), or -1 if the body is unknown.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_createImageCollider<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    dx: jdouble,
    dy: jdouble,
    dz: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
) -> jlong {
    use rapier3d::prelude::*;

    if scene_handle == 0 || body_id < 0 {
        return -1;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene
        .sable_data
        .write()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let mut sim_data = scene
        .sim_data
        .write()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let sim_data = &mut *sim_data;

    let Some(body_handle) = sable_data
        .rigid_bodies
        .get(&(body_id as LevelColliderID))
        .copied()
    else {
        eprintln!("[ipl-natives] createImageCollider MISS: body {body_id} unknown");
        return -1;
    };
    let Some(info) = sable_data
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    else {
        eprintln!("[ipl-natives] createImageCollider MISS: body {body_id} unknown");
        return -1;
    };

    // Mirror the native collider's shape (same id → same info lookups in the
    // hooks/dispatcher), but tagged with the CALLING chart so it pairs with the
    // far chart's terrain and bodies.
    let native = sim_data
        .collider_set
        .get(info.collider)
        .and_then(|c| c.shape().as_shape::<crate::collider::LevelCollider>())
        .copied();
    let Some(native_shape) = native else {
        eprintln!("[ipl-natives] createImageCollider: body {body_id} has no LevelCollider shape");
        return -1;
    };
    let image_shape = crate::collider::LevelCollider {
        chart: scene.chart,
        ..native_shape
    };

    let prefix = rapier3d::math::Pose {
        translation: Vec3::new(dx as Real, dy as Real, dz as Real),
        rotation: rapier3d::math::Rotation::from_xyzw(
            qx as Real, qy as Real, qz as Real, qw as Real,
        )
        .normalize(),
    };
    let collider = ColliderBuilder::new(SharedShape::new(image_shape))
        .friction(0.525)
        .active_events(ActiveEvents::CONTACT_FORCE_EVENTS)
        .active_hooks(ActiveHooks::MODIFY_SOLVER_CONTACTS)
        .density(0.0)
        .collision_groups(crate::groups::image_group(scene.chart))
        .position(rapier3d::math::Pose::IDENTITY)
        .build();

    let handle = sim_data.collider_set.insert_with_parent(
        collider,
        body_handle,
        &mut sim_data.rigid_body_set,
    );
    sim_data
        .collider_set
        .get_mut(handle)
        .unwrap()
        .set_portal_prefix(Some(prefix));
    let parent_pose = sim_data.rigid_body_set[body_handle].position().clone();
    sim_data
        .collider_set
        .get_mut(handle)
        .unwrap()
        .refresh_portal_prefixed_pose(&parent_pose);
    // `insert_with_parent` composes before the Atlas prefix is attached. Recompose now so
    // this image never enters broad phase at identity and leaves a one-step real/image twin.

    info.image_colliders.push(handle);

    let (idx, generation) = handle.into_raw_parts();
    ((idx as jlong) << 32) | (generation as jlong)
}

/// Remove an image collider previously created by `createImageCollider`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_removeImageCollider<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    packed_handle: jlong,
) {
    if scene_handle == 0 || packed_handle < 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene
        .sable_data
        .write()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let mut sim_data = scene
        .sim_data
        .write()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let sim_data = &mut *sim_data;

    let handle = ipl_unpack_handle(packed_handle);

    if let Some(info) = sable_data
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    {
        info.image_colliders.retain(|h| *h != handle);
        if let Some(pieces) = info.pieces.as_mut() {
            pieces.image_nodes.remove(&handle);
        }
    }

    sim_data.collider_set.remove(
        handle,
        &mut sim_data.island_manager,
        &mut sim_data.rigid_body_set,
        true,
    );
}

/// Atlas M5 (spec v3 §2.8): update an image collider's portal prefix — moving
/// portals re-derive P = (R, t) per tick (animated portals, portals anchored to
/// physics structures). The engine recomposes the collider pose and re-runs
/// broad/narrow phase from the PARENT change flag.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_setImagePrefix<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    packed_handle: jlong,
    dx: jdouble,
    dy: jdouble,
    dz: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
) {
    if scene_handle == 0 || packed_handle < 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sim_data = scene
        .sim_data
        .write()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let handle = ipl_unpack_handle(packed_handle);
    let Some(collider) = sim_data.collider_set.get_mut(handle) else {
        return; // image already retired — a moving-portal refresh racing session end
    };
    collider.set_portal_prefix(Some(rapier3d::math::Pose {
        translation: Vec3::new(dx as Real, dy as Real, dz as Real),
        rotation: rapier3d::math::Rotation::from_xyzw(
            qx as Real, qy as Real, qz as Real, qw as Real,
        )
        .normalize(),
    }));
}

#[cfg(test)]
mod piece_routing_tests {
    use super::*;

    fn edge(parent: u8, child: u8, x: f64) -> IplPieceEdge {
        IplPieceEdge {
            parent,
            child,
            origin: DVec3::new(x, 0.0, 0.0),
            normal: DVec3::X,
            axis_w: DVec3::Y,
            half_w: 5.0,
            axis_h: DVec3::Z,
            half_h: 5.0,
            zone: 3.0,
            slack: 0.0,
            plot_origin: DVec3::new(x, 0.0, 0.0),
            plot_normal: DVec3::X,
            plot_axis_w: DVec3::Y,
            plot_half_w: 5.0,
            plot_axis_h: DVec3::Z,
            plot_half_h: 5.0,
        }
    }

    fn exact(parent: u8, child: u8, x: f64) -> IplPieceEdge {
        IplPieceEdge {
            slack: 0.0,
            ..edge(parent, child, x)
        }
    }

    #[test]
    fn dispatcher_cull_keeps_every_voxel_classification_can_reach() {
        use rapier3d::glamx::IVec3;
        let mut pieces = IplPieces::default();
        pieces.edges = vec![edge(0, 1, 0.0)];
        let mut labels = vec![0u8; 4096].into_boxed_slice();
        labels[3] = 1; // voxel (3,0,0) already labelled as the image's piece
        pieces.sections.insert((0, 0, 0), labels);
        // Own label always passes; near the aperture either piece may own it.
        assert!(pieces.voxel_may_be(1, IVec3::new(3, 0, 0)));
        assert!(pieces.voxel_may_be(1, IVec3::new(-2, 0, 0)));
        assert!(pieces.voxel_may_be(0, IVec3::new(3, 0, 0)));
        // Native material buried far from the rectangle can never be the image's: no manifold.
        assert!(!pieces.voxel_may_be(1, IVec3::new(-40, 0, 0)));
        assert!(!pieces.voxel_may_be(1, IVec3::new(0, 30, 0)));
        // ...and whatever could cross the plane this step is a superset of `classify_voxel`.
        for x in -12..12 {
            let voxel = IVec3::new(x, 0, 0);
            let centre = voxel.as_dvec3() + DVec3::splat(0.5);
            for shift in [-3.0, -1.0, 1.0, 3.0] {
                let piece = pieces.classify_voxel(centre, voxel, |_, q| Some(q + DVec3::new(shift, 0.0, 0.0)));
                assert!(pieces.voxel_may_be(piece, voxel), "x={x} shift={shift} piece={piece}");
            }
        }
    }

    #[test]
    fn edge_does_not_cut_material_that_went_around_it() {
        // Native voxel [0,1)^3 behind the plane x = 1 of edge 0 -> 1 (came around the frame):
        // it stays native. In front of the plane x = 0 it is that edge's material and is cut.
        let mut pieces = IplPieces::default();
        pieces.edges = vec![exact(0, 1, 1.0)];
        let p = DVec3::new(0.9, 0.5, 0.5);
        assert_eq!(pieces.classify(p, |_, q| Some(q)), 0);
        pieces.edges = vec![exact(0, 1, 0.2)];
        let behind = DVec3::new(0.1, 0.5, 0.5);
        assert_eq!(pieces.classify(behind, |_, q| Some(q)), 1);
        // Two faces of one doorway (opposite normals): a native voxel in front of face A is
        // never handed to face B.
        let mut b = exact(0, 2, 0.2);
        b.normal = -DVec3::X;
        b.plot_normal = -DVec3::X;
        pieces.edges = vec![b, exact(0, 1, 0.2)];
        let front = DVec3::new(0.9, 0.5, 0.5);
        assert_eq!(pieces.classify(front, |_, q| Some(q)), 0);
    }
    #[test]
    fn observed_crossings_have_no_velocity_cutoff_or_prediction_slab() {
        for speed in [1.0, 32.0, 33.0, 64.0, 1_000.0, 1_000_000.0] {
            let mut pieces = IplPieces::default();
            pieces.edges = vec![exact(0, 1, 0.0)];
            let p = DVec3::splat(0.5);
            assert_eq!(pieces.classify(p, |_, q| Some(q - DVec3::X * speed)), 1);
            let miss = DVec3::new(0.5, 7.5, 0.5);
            assert_eq!(pieces.classify(miss, |_, q| Some(q - DVec3::X * speed)), 0);
        }
    }

    #[test]
    fn transferred_material_does_not_regain_source_terrain_outside_aperture() {
        let mut pieces = IplPieces::default();
        pieces
            .sections
            .insert((0, 0, 0), vec![1; 4096].into_boxed_slice());
        pieces.edges = vec![exact(0, 1, 0.0)];
        let p = DVec3::new(0.5, 12.5, 0.5);
        assert_eq!(pieces.classify(p, |_, q| Some(q)), 1);
        pieces.edges.clear();
        assert_eq!(pieces.classify(p, |_, q| Some(q)), 1);
    }

    #[test]
    fn nested_edges_are_applied_in_time_order_not_node_order() {
        let mut pieces = IplPieces::default();
        pieces.edges = vec![exact(1, 2, -2.0), exact(0, 1, 0.0)];
        assert_eq!(
            pieces.classify(DVec3::splat(0.5), |_, q| Some(q - DVec3::X * 4.0)),
            2
        );
    }

    #[test]
    fn reverse_crossing_returns_material_immediately() {
        let mut pieces = IplPieces::default();
        pieces
            .sections
            .insert((0, 0, 0), vec![1; 4096].into_boxed_slice());
        pieces.edges = vec![exact(0, 1, 1.0)];
        assert_eq!(
            pieces.classify(DVec3::splat(0.5), |_, q| Some(q + DVec3::X)),
            0
        );
    }
    #[test]
    fn surface_on_integer_edge_uses_dispatcher_material_voxel() {
        let mut pieces = IplPieces::default();
        let mut labels = vec![0; 4096].into_boxed_slice();
        labels[0] = 1;
        pieces.sections.insert((0, 0, 0), labels);
        let surface = DVec3::new(1.0, 0.0, 0.5);
        assert_eq!(pieces.classify(surface, |_, p| Some(p)), 0);
        assert_eq!(
            pieces.classify_voxel(surface, rapier3d::glamx::IVec3::ZERO, |_, p| Some(p)),
            1
        );
    }
}
