//! IPSable extensions to the sable_rapier natives (portal-physics spec phase 4),
//! rebased onto sable 2.0.3 (scene handles are raw `Arc<PhysicsScene>` pointers; sable
//! data lives behind `RwLock`, so hook-time reads are properly synchronized).
//!
//! Exposed under the `ipl.sable.natives.IplRapierNatives` Java class — sable's own JNI
//! surface is untouched.

use jni::JNIEnv;
use jni::objects::{JClass, JDoubleArray};
use jni::sys::{jboolean, jint, jlong};
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
