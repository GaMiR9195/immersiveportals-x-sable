//! IPSable extensions to the sable_rapier natives (portal-physics spec phase 4).
//!
//! Exposed under the `ipl.sable.natives.IplRapierNatives` Java class — sable's own JNI
//! surface is untouched, so the ABI stays compatible with the release jar.

use jni::JNIEnv;
use jni::objects::{JClass, JDoubleArray};
use jni::sys::jint;
use marten::Real;
use rapier3d::math::Vector;

use crate::get_scene_mut;
use crate::scene::LevelColliderID;

/// An oriented clip volume for aperture contact clipping (spec §2.5): solver contacts past
/// the plane (signed distance >= 0 along `normal`) AND within the lateral rectangle
/// (|projection on axis_w| <= half_w, |projection on axis_h| <= half_h) are dropped from
/// the owning body's manifolds. The lateral bound is what makes geometry passing BESIDE a
/// free-standing portal frame collide normally — an infinite half-space gets that wrong
/// both ways.
#[derive(Debug, Clone)]
pub struct IplClipRegion {
    pub point: Vector,
    pub normal: Vector,
    pub axis_w: Vector,
    pub half_w: Real,
    pub axis_h: Vector,
    pub half_h: Real,
}

impl IplClipRegion {
    #[inline]
    pub fn contains(&self, p: Vector) -> bool {
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
    scene_id: jint,
    body_id: jint,
    data: JDoubleArray<'local>,
) {
    let len = match env.get_array_length(&data) {
        Ok(l) => l as usize,
        Err(_) => return,
    };
    let mut values = vec![0.0f64; len];
    if len > 0 && env.get_double_array_region(&data, 0, &mut values).is_err() {
        return;
    }

    let scene = get_scene_mut(scene_id);
    let Some(info) = scene
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    else {
        return; // body already gone — nothing to clip
    };

    info.clip_regions.clear();
    for c in values.chunks_exact(14) {
        info.clip_regions.push(IplClipRegion {
            point: Vector::new(c[0] as Real, c[1] as Real, c[2] as Real),
            normal: Vector::new(c[3] as Real, c[4] as Real, c[5] as Real),
            axis_w: Vector::new(c[6] as Real, c[7] as Real, c[8] as Real),
            half_w: c[9] as Real,
            axis_h: Vector::new(c[10] as Real, c[11] as Real, c[12] as Real),
            half_h: c[13] as Real,
        });
    }
}
