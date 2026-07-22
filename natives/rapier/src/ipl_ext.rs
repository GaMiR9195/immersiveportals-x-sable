//! IPSable extensions to the sable_rapier natives (portal-physics spec phase 4),
//! rebased onto sable 2.0.3 (scene handles are raw `Arc<PhysicsScene>` pointers; sable
//! data lives behind `RwLock`, so hook-time reads are properly synchronized).
//!
//! Exposed under the `ipl.sable.natives.IplRapierNatives` Java class — sable's own JNI
//! surface is untouched.

use jni::JNIEnv;
use jni::objects::{JClass, JDoubleArray};
use jni::sys::{jboolean, jdouble, jint, jlong};
use marten::Real;
use rapier3d::math::Vec3;

use crate::scene::{ChunkMap, LevelColliderID, PhysicsScene};

/// An oriented clip volume for aperture contact clipping (spec §2.5): solver contacts past
/// the plane (signed distance >= 0 along `normal`) AND within the lateral rectangle
/// (|projection on axis_w| <= half_w, |projection on axis_h| <= half_h) are dropped from
/// the owning body's manifolds. The lateral bound is what makes geometry passing BESIDE a
/// free-standing portal frame collide normally.
#[derive(Debug, Clone)]
pub struct IplClipRegion {
    pub point: Vec3,
    pub normal: Vec3,
    pub axis_w: Vec3,
    pub half_w: Real,
    pub axis_h: Vec3,
    pub half_h: Real,
}

impl IplClipRegion {
    #[inline]
    pub fn contains(&self, p: Vec3) -> bool {
        let rel = p - self.point;
        if rel.dot(self.normal) < 0.0 {
            return false;
        }
        rel.dot(self.axis_w).abs() <= self.half_w && rel.dot(self.axis_h).abs() <= self.half_h
    }
}

/// Set (or clear, with an empty array) the clip regions of a body.
/// Layout: N regions x 14 doubles: [px py pz  nx ny nz  wx wy wz  halfW  hx hy hz  halfH].
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_setClipRegions<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    data: JDoubleArray<'local>,
) {
    if scene_handle == 0 {
        return;
    }
    let len = match env.get_array_length(&data) {
        Ok(l) => l as usize,
        Err(_) => return,
    };
    let mut values = vec![0.0f64; len];
    if len > 0 && env.get_double_array_region(&data, 0, &mut values).is_err() {
        return;
    }

    // Same handle-deref pattern as the upstream natives (with_handle).
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap();
    let Some(info) = sable_data
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    else {
        // Diagnostic (stderr -> launcher log): a silent miss here means Java is
        // clipping a body id / scene pair that native doesn't know.
        eprintln!(
            "[ipl-natives] setClipRegions MISS: body {body_id} not in scene {scene_handle:x}"
        );
        return; // body already gone — nothing to clip
    };

    info.clip_regions.clear();
    for c in values.chunks_exact(14) {
        info.clip_regions.push(IplClipRegion {
            point: Vec3::new(c[0] as Real, c[1] as Real, c[2] as Real),
            normal: Vec3::new(c[3] as Real, c[4] as Real, c[5] as Real),
            axis_w: Vec3::new(c[6] as Real, c[7] as Real, c[8] as Real),
            half_w: c[9] as Real,
            axis_h: Vec3::new(c[10] as Real, c[11] as Real, c[12] as Real),
            half_h: c[13] as Real,
        });
    }
    eprintln!(
        "[ipl-natives] setClipRegions: body {body_id} <- {} region(s) in scene {scene_handle:x}",
        info.clip_regions.len()
    );
}

/// Give a body private voxel section storage, detaching it from the scene-wide
/// `main_level_chunks`. Subsequent body-targeted `addChunk` calls store sections in the
/// body's own `chunk_map` (the storage native kinematic contraptions already use), and
/// `removeSubLevel` frees them with the body.
///
/// Required for straddle clone bodies through same-dimension portals: the clone and the
/// real body live in ONE scene but describe the same ship-local section coordinates at
/// different world poses — shared storage lets one overwrite (or, on cleanup, delete)
/// the other's collision data.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_useDedicatedChunks<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
) {
    if scene_handle == 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap();
    let Some(info) = sable_data
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    else {
        return; // body already gone
    };
    if info.chunk_map.is_none() {
        info.chunk_map = Some(ChunkMap::new());
    }
}

/// Register (`excluded != 0`) or clear a contact exclusion between two bodies in one
/// scene. The dispatcher's dynamic-vs-dynamic path generates no manifolds for excluded
/// pairs (and drops persisted ones). Used for a straddle clone vs its own real body —
/// and clone↔clone of one ship — when a same-dimension portal puts them in one scene.
///
/// Defensive by design: no body-existence check (ids are just set keys), idempotent in
/// both directions, no-op on a null scene. Entries for despawned bodies are inert
/// (`nextBodyID` never reuses ids) but Java clears them on despawn anyway.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_setBodyPairExclusion<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    id_a: jint,
    id_b: jint,
    excluded: jboolean,
) {
    if scene_handle == 0 || id_a == id_b || id_a < 0 || id_b < 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap();
    let (a, b) = (id_a as LevelColliderID, id_b as LevelColliderID);
    let key = if a <= b { (a, b) } else { (b, a) };
    if excluded != 0 {
        sable_data.ipl_excluded_pairs.insert(key);
    } else {
        sable_data.ipl_excluded_pairs.remove(&key);
    }
}

/// Diagnostics readback for the aperture clip pass: writes
/// `[contactsSeen, contactsDropped, lastContactX, lastContactY, lastContactZ]` into
/// `out` (5 doubles). Counters accumulate since body creation; the last-contact point
/// is the most recent solver contact the clip pass judged for this body. No-op (out
/// untouched) on a null scene or unknown body.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_getClipStats<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    out: JDoubleArray<'local>,
) {
    if scene_handle == 0 || body_id < 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let sable_data = scene.sable_data.read().unwrap();
    let Some(info) = sable_data
        .level_colliders
        .get(&(body_id as LevelColliderID))
    else {
        return;
    };
    use std::sync::atomic::Ordering;
    let vals = [
        info.ipl_clip_seen.load(Ordering::Relaxed) as f64,
        info.ipl_clip_dropped.load(Ordering::Relaxed) as f64,
        f64::from_bits(info.ipl_last_contact[0].load(Ordering::Relaxed)),
        f64::from_bits(info.ipl_last_contact[1].load(Ordering::Relaxed)),
        f64::from_bits(info.ipl_last_contact[2].load(Ordering::Relaxed)),
    ];
    let _ = env.set_double_array_region(&out, 0, &vals);
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
    let mut sable_data = scene.sable_data.write().unwrap();
    let mut sim_data = scene.sim_data.write().unwrap();
    let sim_data = &mut *sim_data;

    let Some(body_handle) = sable_data.rigid_bodies.get(&(body_id as LevelColliderID)).copied()
    else {
        eprintln!("[ipl-natives] createImageCollider MISS: body {body_id} has no rigid body");
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

    let collider = ColliderBuilder::new(SharedShape::new(image_shape))
        .friction(0.525)
        .active_events(ActiveEvents::CONTACT_FORCE_EVENTS)
        .active_hooks(ActiveHooks::MODIFY_SOLVER_CONTACTS)
        .density(0.0)
        .collision_groups(crate::groups::level_group(scene.chart))
        .build();

    let handle =
        sim_data
            .collider_set
            .insert_with_parent(collider, body_handle, &mut sim_data.rigid_body_set);
    let prefix = rapier3d::math::Pose {
        translation: Vec3::new(dx as Real, dy as Real, dz as Real),
        rotation: rapier3d::math::Rotation::from_xyzw(
            qx as Real,
            qy as Real,
            qz as Real,
            qw as Real,
        )
        .normalize(),
    };
    sim_data
        .collider_set
        .get_mut(handle)
        .unwrap()
        .set_portal_prefix(Some(prefix));

    info.image_colliders.push(handle);
    eprintln!(
        "[ipl-natives] image collider created: body {body_id} chart {} shift ({dx:.1},{dy:.1},{dz:.1})",
        scene.chart
    );

    let (idx, generation) = handle.into_raw_parts();
    ((idx as jlong) << 32) | (generation as jlong)
}

/// Update an image's portal prefix without replacing its collider or clip state.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_setImagePrefix<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    packed_handle: jlong,
    dx: jdouble,
    dy: jdouble,
    dz: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
) {
    use rapier3d::prelude::ColliderHandle;

    if scene_handle == 0 || body_id < 0 || packed_handle < 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let is_known_body = scene
        .sable_data
        .read()
        .unwrap()
        .level_colliders
        .contains_key(&(body_id as LevelColliderID));
    if !is_known_body {
        return;
    }
    let handle = ColliderHandle::from_raw_parts(
        (packed_handle >> 32) as u32,
        (packed_handle & 0xFFFF_FFFF) as u32,
    );
    let mut sim_data = scene.sim_data.write().unwrap();
    let Some(collider) = sim_data.collider_set.get_mut(handle) else {
        return;
    };
    collider.set_portal_prefix(Some(rapier3d::math::Pose {
        translation: Vec3::new(dx as Real, dy as Real, dz as Real),
        rotation: rapier3d::math::Rotation::from_xyzw(
            qx as Real, qy as Real, qz as Real, qw as Real,
        )
        .normalize(),
    }));
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
    use rapier3d::prelude::ColliderHandle;

    if scene_handle == 0 || packed_handle < 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap();
    let mut sim_data = scene.sim_data.write().unwrap();
    let sim_data = &mut *sim_data;

    let handle = ColliderHandle::from_raw_parts(
        (packed_handle >> 32) as u32,
        (packed_handle & 0xFFFF_FFFF) as u32,
    );

    if let Some(info) = sable_data
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    {
        info.image_colliders.retain(|h| *h != handle);
        info.image_clip.remove(&handle);
    }

    sim_data.collider_set.remove(
        handle,
        &mut sim_data.island_manager,
        &mut sim_data.rigid_body_set,
        true,
    );
}

/// Set (or clear, with an empty array) the clip regions of one IMAGE collider —
/// the far side of the half-open aperture seam. Layout matches `setClipRegions`
/// (N × 14 doubles).
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_setImageClipRegions<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    packed_handle: jlong,
    data: JDoubleArray<'local>,
) {
    use rapier3d::prelude::ColliderHandle;

    if scene_handle == 0 || packed_handle < 0 {
        return;
    }
    let len = match env.get_array_length(&data) {
        Ok(l) => l as usize,
        Err(_) => return,
    };
    let mut values = vec![0.0f64; len];
    if len > 0 && env.get_double_array_region(&data, 0, &mut values).is_err() {
        return;
    }

    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap();
    let Some(info) = sable_data
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    else {
        return;
    };

    let handle = ColliderHandle::from_raw_parts(
        (packed_handle >> 32) as u32,
        (packed_handle & 0xFFFF_FFFF) as u32,
    );
    let mut regions = Vec::with_capacity(values.len() / 14);
    for c in values.chunks_exact(14) {
        regions.push(IplClipRegion {
            point: Vec3::new(c[0] as Real, c[1] as Real, c[2] as Real),
            normal: Vec3::new(c[3] as Real, c[4] as Real, c[5] as Real),
            axis_w: Vec3::new(c[6] as Real, c[7] as Real, c[8] as Real),
            half_w: c[9] as Real,
            axis_h: Vec3::new(c[10] as Real, c[11] as Real, c[12] as Real),
            half_h: c[13] as Real,
        });
    }
    if regions.is_empty() {
        info.image_clip.remove(&handle);
    } else {
        info.image_clip.insert(handle, regions);
    }
}
