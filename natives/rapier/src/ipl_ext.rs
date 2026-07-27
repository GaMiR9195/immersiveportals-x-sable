//! IPSable extensions to the sable_rapier natives (portal-physics spec phase 4),
//! rebased onto sable 2.0.3 (scene handles are raw `Arc<PhysicsScene>` pointers; sable
//! data lives behind `RwLock`, so hook-time reads are properly synchronized).
//!
//! Exposed under the `ipl.sable.natives.IplRapierNatives` Java class — sable's own JNI
//! surface is untouched.

use jni::JNIEnv;
use jni::objects::{JClass, JDoubleArray};
use jni::sys::{jboolean, jdouble, jint, jlong};
use std::collections::HashSet;
use marten::Real;
use rapier3d::math::Vec3;

use crate::scene::{LevelColliderID, PhysicsScene};

/// An oriented clip volume for aperture contact clipping (spec §2.5): solver contacts past
/// the plane (signed distance >= 0 along `normal`) AND within the lateral rectangle
/// (|projection on axis_w| <= half_w, |projection on axis_h| <= half_h) are dropped from
/// the owning body's manifolds. The lateral bound is load-bearing twice over: geometry
/// passing BESIDE a free-standing portal frame collides normally, and multi-portal
/// straddles union safely — unbounded half-spaces from two sessions can cover the whole
/// ship (or, facing planes, all of space), dropping every source contact.
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

/// Register (`excluded != 0`) or clear a contact exclusion between two bodies in one
/// scene. The dispatcher's dynamic-vs-dynamic path generates no manifolds for excluded
/// pairs (and drops persisted ones). Portal rims use this to exclude their carrier.
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

/// Dormancy switch for a hosted body whose parent-pointer chunks are unloaded: a Fixed
/// body skips integration entirely (no gravity, immovable, still a valid joint/rope
/// anchor), so an unloaded-area ship cannot fall through terrain that was never baked.
/// Idempotent — Java re-applies it every tick while dormant, which also re-freezes a
/// body that was recreated (rehome twin) mid-dormancy. Velocities are zeroed on freeze
/// so the ship resumes at rest.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_setBodyDormant<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    dormant: jboolean,
) {
    use rapier3d::prelude::RigidBodyType;

    if scene_handle == 0 || body_id < 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let handle = {
        let sable_data = scene.sable_data.read().unwrap();
        let Some(handle) = sable_data
            .rigid_bodies
            .get(&(body_id as LevelColliderID))
            .copied()
        else {
            return;
        };
        handle
    };
    let mut sim_data = scene.sim_data.write().unwrap();
    let Some(body) = sim_data.rigid_body_set.get_mut(handle) else {
        return;
    };
    if dormant != 0 {
        if body.body_type() != RigidBodyType::Fixed {
            body.set_linvel(Vec3::new(0.0, 0.0, 0.0), false);
            body.set_angvel(Vec3::new(0.0, 0.0, 0.0), false);
            body.set_body_type(RigidBodyType::Fixed, true);
        }
    } else if body.body_type() != RigidBodyType::Dynamic {
        body.set_body_type(RigidBodyType::Dynamic, true);
    }
}

/// Sable body ids in the full impulse-joint component containing `body_id`. Rope particles
/// participate in the walk, so two ships tied by a rope are one portal-transition group even
/// though the intermediate bodies have no Java ids.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_connectedSableBodyIds<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
) -> jni::objects::JIntArray<'local> {
    if scene_handle == 0 || body_id < 0 {
        return env.new_int_array(0).unwrap();
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let sable_data = scene.sable_data.read().unwrap();
    let Some(start) = sable_data.rigid_bodies.get(&(body_id as LevelColliderID)).copied() else {
        return env.new_int_array(0).unwrap();
    };
    let sim = scene.sim_data.read().unwrap();
    let mut connected = HashSet::from([start]);
    loop {
        let mut changed = false;
        for (_, joint) in sim.impulse_joint_set.iter() {
            if joint.body1 == scene.world.ground_handle || joint.body2 == scene.world.ground_handle {
                continue;
            }
            if connected.contains(&joint.body1) && connected.insert(joint.body2) {
                changed = true;
            }
            if connected.contains(&joint.body2) && connected.insert(joint.body1) {
                changed = true;
            }
        }
        if !changed {
            break;
        }
    }
    let mut ids: Vec<jint> = sable_data.rigid_bodies.iter()
        .filter_map(|(id, handle)| connected.contains(handle).then_some(*id as jint))
        .collect();
    ids.sort_unstable();
    let result = env.new_int_array(ids.len() as i32).unwrap();
    if !ids.is_empty() {
        env.set_int_array_region(&result, 0, &ids).unwrap();
    }
    result
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

    let Some(body_handle) = sable_data.rigid_bodies.get(&(body_id as LevelColliderID)).copied() else {
        eprintln!("[ipl-natives] createImageCollider MISS: body {body_id} unknown");
        return -1;
    };
    let Some(info) = sable_data.level_colliders.get_mut(&(body_id as LevelColliderID)) else {
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
            qx as Real,
            qy as Real,
            qz as Real,
            qw as Real,
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

    let handle =
        sim_data
            .collider_set
            .insert_with_parent(collider, body_handle, &mut sim_data.rigid_body_set);
    sim_data.collider_set.get_mut(handle).unwrap().set_portal_prefix(Some(prefix));
    sim_data.collider_set.get_mut(handle).unwrap().set_position(rapier3d::math::Pose::IDENTITY);
    // The engine's portal-prefix composition intentionally changes the collider pose only
    // after a parent pose update. Seed that update now so a persistent parent-chart image is
    // visible in the same tick it is registered.

    info.image_colliders.push(handle);
    eprintln!(
        "[ipl-natives] image collider created: body {body_id} chart {} shift ({dx:.1},{dy:.1},{dz:.1})",
        scene.chart
    );

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
/// (N x 14 doubles).
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
    use rapier3d::prelude::ColliderHandle;

    if scene_handle == 0 || packed_handle < 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sim_data = scene.sim_data.write().unwrap();
    let handle = ColliderHandle::from_raw_parts(
        (packed_handle >> 32) as u32,
        (packed_handle & 0xFFFF_FFFF) as u32,
    );
    let Some(collider) = sim_data.collider_set.get_mut(handle) else {
        return; // image already retired — a moving-portal refresh racing session end
    };
    collider.set_portal_prefix(Some(rapier3d::math::Pose {
        translation: Vec3::new(dx as Real, dy as Real, dz as Real),
        rotation: rapier3d::math::Rotation::from_xyzw(
            qx as Real,
            qy as Real,
            qz as Real,
            qw as Real,
        )
        .normalize(),
    }));
}
