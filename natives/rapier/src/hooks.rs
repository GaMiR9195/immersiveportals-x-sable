use jni::JavaVM;
use jni::objects::{JDoubleArray, JObject, JValue};
use jni::signature::ReturnType;
use jni::sys::{jboolean, jdouble, jint, jvalue};
use marten::Real;
use marten::level::VoxelColliderData;
use rapier3d::geometry::{Collider, SolverContact};
use rapier3d::glamx::DVec3;
use rapier3d::math::{Pose, Vec3};
use rapier3d::pipeline::{ContactModificationContext, PhysicsHooks};
use std::sync::{Arc, RwLock};

use crate::collider::LevelCollider;
use crate::scene::{LevelColliderID, SableManifoldInfoMap, SableSceneData};

#[derive(Clone)]
pub struct SablePhysicsHooks {
    pub sable_data: Arc<RwLock<SableSceneData>>,
    pub manifold_info_map: Arc<SableManifoldInfoMap>,
    pub current_step_vm: Option<Arc<JavaVM>>,
}

impl PhysicsHooks for SablePhysicsHooks {
    fn modify_solver_contacts(&self, context: &mut ContactModificationContext) {
        // IPSable piece clipping: runs for EVERY manifold that reaches the hook,
        // independent of the NEEDS_HOOKS block-property gate below. Reads go through
        // this hook's own RwLock'd scene data (2.0's threading model). A/B kill switch:
        // IPL_DISABLE_CLIP=1.
        if !ipl_clip_disabled() {
            self.ipl_clip_contacts(context);
            if context.solver_contacts.is_empty() {
                return;
            }
        }

        if !VoxelColliderData::needs_hooks(*context.user_data) {
            return;
        }

        let mut remove = false;
        for contact in context.solver_contacts.iter_mut() {
            // IPSable: a contact neutralized by the aperture clip above belongs to geometry
            // that is on the OTHER side of a portal. It must not reach the block-property
            // path: that path fires Java contact events (block collision callbacks, sounds,
            // damage), can clear the whole manifold via `remove`, and re-applies block
            // restitution / conveyor tangent velocity to a contact that does not exist.
            if crate::ipl_ext::ipl_contact_neutralized(contact) {
                continue;
            }
            let Some(collider_a) = context.colliders.get(context.collider1) else {
                panic!("No collider A!");
            };

            let Some(collider_b) = context.colliders.get(context.collider2) else {
                panic!("No collider B!");
            };

            let level_collider_a = collider_a.shape().as_shape::<LevelCollider>();
            let level_collider_b = collider_b.shape().as_shape::<LevelCollider>();

            if level_collider_a.is_none() && level_collider_b.is_none() {
                continue;
            }

            let mut tangent_velo = Vec3::ZERO;

            let mut velocity = 0.0;
            let mut friction_multiplier = 1.0;

            if let Some(handle) = context.rigid_body1 {
                let mut velo_1 = context
                    .bodies
                    .get(handle)
                    .unwrap()
                    .velocity_at_point(contact.point);
                velo_1 += self.get_fake_velocity(contact, collider_a, level_collider_a);
                velocity += velo_1.dot(*context.normal);
            }
            if let Some(handle) = context.rigid_body2 {
                let mut velo_2 = context
                    .bodies
                    .get(handle)
                    .unwrap()
                    .velocity_at_point(contact.point);
                velo_2 += self.get_fake_velocity(contact, collider_b, level_collider_b);
                velocity -= velo_2.dot(*context.normal);
            }

            velocity = velocity.abs();

            let mut restitution: Real = 0.0;

            let manifold_index = (*context.user_data >> 1) as usize;

            if let Some(level_collider_a) = level_collider_a {
                let (add_velo, remove_a, friction_mult, block_restitution) = self
                    .handle_block_params(
                        collider_a.position(),
                        collider_a,
                        Some(level_collider_a),
                        &contact.point,
                        velocity,
                        manifold_index,
                        true,
                    );
                tangent_velo += add_velo;
                remove |= remove_a;
                friction_multiplier *= friction_mult;
                restitution = restitution.max(block_restitution);
            }

            if let Some(level_collider_b) = level_collider_b {
                let (add_velo, remove_b, friction_mult, block_restitution) = self
                    .handle_block_params(
                        collider_b.position(),
                        collider_b,
                        Some(level_collider_b),
                        &contact.point,
                        velocity,
                        manifold_index,
                        false,
                    );
                tangent_velo -= add_velo;
                remove |= remove_b;
                friction_multiplier *= friction_mult;
                restitution = restitution.max(block_restitution);
            }

            tangent_velo -= *context.normal * tangent_velo.dot(*context.normal);

            contact.tangent_velocity = tangent_velo;
            contact.friction *= friction_multiplier;
            contact.restitution = contact.restitution.max(restitution);
        }

        if remove {
            context.solver_contacts.clear()
        }
    }
}

static IPL_CLIP_DISABLED: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
static IPL_CLIP_FIRST_DROP: std::sync::Once = std::sync::Once::new();

fn ipl_clip_disabled() -> bool {
    *IPL_CLIP_DISABLED.get_or_init(|| {
        std::env::var("IPL_DISABLE_CLIP")
            .map(|v| v == "1")
            .unwrap_or(false)
    })
}

/// Keep SIMD lane layout stable, but clear ALL cached impulses as well as material
/// properties. A neutralized point must not warm-start a phantom terrain impulse.
fn neutralize_contact(c: &mut SolverContact) {
    c.dist = crate::ipl_ext::IPL_NEUTRAL_CONTACT_GAP;
    c.friction = 0.0;
    c.restitution = 0.0;
    c.tangent_velocity = Vec3::ZERO;
    c.warmstart_impulse = 0.0;
    c.warmstart_tangent_impulse = Default::default();
    c.warmstart_twist_impulse = 0.0;
}

impl SablePhysicsHooks {
    /// Piece clipping (ABI 3, see `ipl_ext`): a contact of a straddling body survives only
    /// on the collider of the piece its point belongs to. The point is taken a hair inside
    /// the body's own material (against the manifold normal), then classified by the voxel
    /// label and, near an aperture, by the side of that aperture's plane.
    fn ipl_clip_contacts(&self, context: &mut ContactModificationContext) {
        let normal = context.normal.as_dvec3();
        for (handle, inward) in [(context.collider1, -1.0f64), (context.collider2, 1.0f64)] {
            let Some(collider) = context.colliders.get(handle) else {
                continue;
            };
            let Some(level_collider) = collider.shape().as_shape::<LevelCollider>() else {
                continue;
            };
            let Some(id) = level_collider.id else {
                continue; // the static world collider is never clipped
            };
            let sable_data = self
                .sable_data
                .read()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            let Some(info) = sable_data.level_colliders.get(&(id as LevelColliderID)) else {
                continue;
            };
            let (Some(pieces), Some(com)) = (info.pieces.as_deref(), info.center_of_mass) else {
                continue;
            };
            let own = if handle == info.collider {
                0u8
            } else {
                match pieces.image_nodes.get(&handle) {
                    Some(node) => *node,
                    None => continue,
                }
            };
            let pose = *collider.position();
            let pose_of = |node: u8| -> Option<Pose> {
                let h = if node == 0 {
                    info.collider
                } else {
                    *pieces.image_nodes.iter().find(|(_, n)| **n == node)?.0
                };
                context.colliders.get(h).map(|c| *c.position())
            };
            // NEUTRALIZE in place — do NOT retain()/shrink the list. Partially removing
            // solver contacts is a state stock code never produces, and the fork's SIMD
            // constraint batcher lane-gathers contacts unchecked (every partially shrunk
            // manifold died with 0xc0000005 on solver worker threads). A far-separated
            // zero-friction contact, with cached impulses reset, cannot warm-start a ghost contact.
            let mut clipped = 0u64;
            for c in context.solver_contacts.iter_mut() {
                if crate::ipl_ext::ipl_contact_neutralized(c) {
                    continue;
                }
                let Some(witness) = context.manifold.points.get(c.contact_id[0] as usize) else {
                    continue;
                };
                let (local_point, subshape) = if handle == context.collider1 {
                    (witness.local_p1, context.manifold.subshape_pos1)
                } else {
                    (witness.local_p2, context.manifold.subshape_pos2)
                };
                // Manifold witnesses are subshape-local. Lift through the block shape
                // BEFORE the collider pose. Midpoints lie in terrain under penetration,
                // causing an adjacent unlabelled voxel to resurrect the source ground.
                let surface = subshape.map_or(local_point, |p| p * local_point);
                let world =
                    pose.transform_point(surface) + normal.as_vec3() * (inward as f32 * 0.001);
                let local = pose.inverse_transform_point(world);
                let plot = local.as_dvec3() + com;
                let voxel = self
                    .manifold_info_map
                    .list
                    .get(&((*context.user_data >> 1) as usize))
                    .and_then(|m| {
                        if handle == context.collider1 {
                            (m.col_a != 0).then_some(m.pos_a)
                        } else {
                            (m.col_b != 0).then_some(m.pos_b)
                        }
                    })
                    .unwrap_or_else(|| plot.floor().as_ivec3());
                let piece = pieces.classify_voxel(plot, voxel, |node, point| {
                    let local_point = (point - com).as_vec3();
                    if node == own {
                        Some(pose.transform_point(local_point).as_dvec3())
                    } else {
                        pose_of(node).map(|p| p.transform_point(local_point).as_dvec3())
                    }
                });
                if piece != own {
                    neutralize_contact(c);
                    clipped += 1;
                }
            }
            if clipped > 0 {
                IPL_CLIP_FIRST_DROP.call_once(|| {
                    eprintln!(
                        "[ipl-natives] piece clip ACTIVE: first neutralize, {clipped} contact(s) for body {id}"
                    );
                });
            }
        }
    }

    fn get_fake_velocity(
        &self,
        contact: &SolverContact,
        collider_a: &Collider,
        level_collider_a: Option<&LevelCollider>,
    ) -> Vec3 {
        if let Some(level_collider_a) = level_collider_a
            && level_collider_a.id.is_some()
        {
            let sable_data = self
                .sable_data
                .read()
                .unwrap_or_else(std::sync::PoisonError::into_inner);

            let collider_info =
                &sable_data.level_colliders[&(level_collider_a.id.unwrap() as LevelColliderID)];

            if let Some(fake_velo) = collider_info.fake_velocities {
                let transform = collider_a.position();
                return transform.transform_vector(fake_velo.velocity_at_point(
                    transform.inverse_transform_point(contact.point),
                    Vec3::ZERO,
                ));
            };
        }
        Vec3::ZERO
    }

    fn handle_block_params(
        &self,
        isometry: &Pose,
        _collider: &Collider,
        level_collider: Option<&LevelCollider>,
        global_point: &Vec3,
        velocity: Real,
        manifold_index: usize,
        body_a: bool,
    ) -> (Vec3, bool, Real, Real) {
        let state = crate::get_physics_state();

        let (tangent_velo, center_of_mass, skip_contact_events) = {
            let sable_data = self
                .sable_data
                .read()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            let collider_info =
                level_collider.and_then(|lc| lc.id.map(|id| &sable_data.level_colliders[&(id)]));

            let mut tangent_velo = Vec3::ZERO;
            if let Some(fake_velo) = collider_info.and_then(|info| info.fake_velocities) {
                tangent_velo += isometry.transform_vector(fake_velo.velocity_at_point(
                    isometry.inverse_transform_point(*global_point),
                    Vec3::ZERO,
                ));
            }

            let center_of_mass = collider_info.map_or(DVec3::ZERO, |b| b.center_of_mass.unwrap());
            let skip_contact_events = collider_info.is_some_and(|info| info.has_own_chunks());

            (tangent_velo, center_of_mass, skip_contact_events)
        };

        let Some(manifold_info) = self.manifold_info_map.list.get(&manifold_index) else {
            return (tangent_velo, false, 1.0, 0.0);
        };

        let (block_coord, block_id, other_block_coord, has_other_block) = if body_a {
            (
                manifold_info.pos_a,
                manifold_info.col_a as u32,
                manifold_info.pos_b,
                manifold_info.col_b != 0,
            )
        } else {
            (
                manifold_info.pos_b,
                manifold_info.col_b as u32,
                manifold_info.pos_a,
                manifold_info.col_a != 0,
            )
        };

        let local = isometry.inverse_transform_point(*global_point);
        let block_coord_d: DVec3 = local.as_dvec3() + center_of_mass;

        if block_id == 0 {
            return (tangent_velo, false, 1.0, 0.0);
        }

        let voxel_collider_data = &state
            .voxel_collider_map
            .voxel_colliders
            .get((block_id - 1) as usize);
        let mut friction_multiplier = 1.0;
        let mut restitution = 0.0;

        if voxel_collider_data.is_none() {
            return (tangent_velo, false, friction_multiplier, restitution);
        }

        let voxel_collider_data = voxel_collider_data.unwrap().as_ref().unwrap();
        friction_multiplier *= voxel_collider_data.friction;
        restitution = voxel_collider_data.restitution;

        let Some(contact_events) = voxel_collider_data.contact_events.as_ref() else {
            return (tangent_velo, false, friction_multiplier, restitution);
        };

        if skip_contact_events {
            return (tangent_velo, false, friction_multiplier, restitution);
        }

        let Some(current_step_vm) = &self.current_step_vm else {
            panic!("No current step env!");
        };

        let Some(method) = &voxel_collider_data.contact_method else {
            panic!("No contact method!");
        };

        let args = &[
            JValue::Int(block_coord.x as jint),
            JValue::Int(block_coord.y as jint),
            JValue::Int(block_coord.z as jint),
            JValue::Int(other_block_coord.x as jint),
            JValue::Int(other_block_coord.y as jint),
            JValue::Int(other_block_coord.z as jint),
            JValue::Double(block_coord_d.x),
            JValue::Double(block_coord_d.y),
            JValue::Double(block_coord_d.z),
            JValue::Double(velocity as jdouble),
            JValue::Bool(has_other_block as jboolean),
        ];

        let args: Vec<jvalue> = args.iter().map(|v| v.as_jni()).collect();

        let mut env = current_step_vm.get_env().unwrap();
        let result =
            unsafe { env.call_method_unchecked(contact_events, method, ReturnType::Array, &args) }
                .unwrap();
        let arr = JDoubleArray::from(JObject::try_from(result).unwrap());

        let mut velo_arr: [jdouble; 4] = [0.0, 0.0, 0.0, 0.0];
        env.get_double_array_region(arr, 0, &mut velo_arr).unwrap();

        let velo = Vec3::new(
            velo_arr[0] as Real,
            velo_arr[1] as Real,
            velo_arr[2] as Real,
        );

        (
            tangent_velo + isometry.transform_vector(velo),
            velo_arr[3] > 0.0,
            friction_multiplier,
            restitution,
        )
    }
}

#[cfg(test)]
mod portal_contact_tests {
    use super::*;

    #[test]
    fn neutralized_contact_keeps_lane_but_loses_all_cached_impulses() {
        // SolverContact is a repr(C) struct made entirely of floating-point scalars,
        // vectors and integer contact ids; all-zero is a valid test initialization.
        let mut c: SolverContact = unsafe { std::mem::zeroed() };
        c.dist = -3.0;
        c.friction = 0.8;
        c.restitution = 0.7;
        c.tangent_velocity = Vec3::ONE;
        c.warmstart_impulse = 123.0;
        c.warmstart_tangent_impulse = rapier3d::math::Vec2::ONE.into();
        c.warmstart_twist_impulse = 8.0;
        c.contact_id = [7];
        neutralize_contact(&mut c);
        assert!(crate::ipl_ext::ipl_contact_neutralized(&c));
        assert_eq!(c.contact_id, [7]);
        assert_eq!(c.warmstart_impulse, 0.0);
        assert_eq!(c.warmstart_tangent_impulse.norm_squared(), 0.0);
        assert_eq!(c.warmstart_twist_impulse, 0.0);
        assert_eq!(c.tangent_velocity, Vec3::ZERO);
        assert_eq!(c.restitution, 0.0);
    }
    #[test]
    fn deep_ground_manifold_is_removed_only_from_source_not_image() {
        use crate::ipl_ext::IplPieces;
        use crate::scene::{SableManifoldInfo, SableSceneData};
        use rapier3d::dynamics::RigidBodySet;
        use rapier3d::geometry::{ColliderBuilder, ColliderSet, ContactManifold, SharedShape};
        use rapier3d::glamx::IVec3;
        use rapier3d::parry::query::TrackedContact;
        use rapier3d::parry::shape::PackedFeatureId;
        use std::collections::HashMap;

        let mut colliders = ColliderSet::new();
        let native = colliders.insert(ColliderBuilder::new(SharedShape::new(LevelCollider::new(
            Some(7),
            false,
            0,
        ))));
        let image = colliders.insert(ColliderBuilder::new(SharedShape::new(LevelCollider::new(
            Some(7),
            false,
            1,
        ))));
        let terrain = colliders.insert(ColliderBuilder::new(SharedShape::new(LevelCollider::new(
            None, true, 0,
        ))));
        let bodies = RigidBodySet::new();
        let mut info = crate::ActiveLevelColliderInfo::new(native, 0);
        info.center_of_mass = Some(DVec3::ZERO);
        let mut pieces = IplPieces::default();
        let mut labels = vec![0; 4096].into_boxed_slice();
        labels[0] = 1;
        pieces.sections.insert((0, 0, 0), labels);
        pieces.image_nodes.insert(image, 1);
        info.pieces = Some(Box::new(pieces));
        let data = SableSceneData {
            charts: HashMap::new(),
            joint_set: crate::joints::SableJointSet::new(),
            rope_map: crate::rope::RopeMap::default(),
            level_colliders: HashMap::from([(7, info)]),
            rigid_bodies: HashMap::new(),
            bodies_by_chart: HashMap::new(),
        };
        let map = Arc::new(SableManifoldInfoMap::default());
        map.list.insert(
            0,
            SableManifoldInfo {
                pos_a: IVec3::ZERO,
                pos_b: IVec3::ZERO,
                col_a: 1,
                col_b: 1,
            },
        );
        let hooks = SablePhysicsHooks {
            sable_data: Arc::new(RwLock::new(data)),
            manifold_info_map: map,
            current_step_vm: None,
        };
        for (ship, should_clip) in [(native, true), (image, false)] {
            for swapped in [false, true] {
                let surface = Vec3::new(1.0, 0.0, 0.5);
                let ground = Vec3::new(1.0, -4.0, 0.5);
                let mut manifold = ContactManifold::new();
                manifold.points.push(TrackedContact::new(
                    if swapped { ground } else { surface },
                    if swapped { surface } else { ground },
                    PackedFeatureId::face(0),
                    PackedFeatureId::face(0),
                    -4.0,
                ));
                // Scalar/vector/integer-only repr(C) contact, see the test above.
                let mut contact: SolverContact = unsafe { std::mem::zeroed() };
                contact.point = surface.midpoint(ground); // floor lies in the wrong voxel!
                contact.dist = -4.0;
                contact.friction = 0.8;
                contact.warmstart_impulse = 12.0;
                let mut contacts = vec![contact];
                let mut normal = if swapped { Vec3::Y } else { -Vec3::Y };
                let mut user_data = 0;
                let mut context = ContactModificationContext {
                    bodies: &bodies,
                    colliders: &colliders,
                    collider1: if swapped { terrain } else { ship },
                    collider2: if swapped { ship } else { terrain },
                    rigid_body1: None,
                    rigid_body2: None,
                    manifold: &manifold,
                    solver_contacts: &mut contacts,
                    normal: &mut normal,
                    user_data: &mut user_data,
                };
                hooks.ipl_clip_contacts(&mut context);
                assert_eq!(contacts.len(), 1); // preserve SIMD lane layout
                assert_eq!(
                    crate::ipl_ext::ipl_contact_neutralized(&contacts[0]),
                    should_clip
                );
                assert_eq!(
                    contacts[0].warmstart_impulse,
                    if should_clip { 0.0 } else { 12.0 }
                );
            }
        }
    }
}
