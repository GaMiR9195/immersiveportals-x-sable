//! Changing the common world frame preserves local anchors, axes, limits and motors.
//! Cached solver impulses do not have that guarantee (especially after a rotation).
//! Never rebuild joints, and never reset a world driver outside the moving component.

use crate::scene::{LevelColliderID, PhysicsScene};
use jni::JNIEnv;
use jni::objects::{JClass, JIntArray};
use jni::sys::{jboolean, jlong};
use rapier3d::prelude::{ImpulseJointSet, RigidBodyHandle, RigidBodySet};
use std::collections::HashSet;

pub(crate) fn reset_group(
    bodies: &mut RigidBodySet,
    joints: &mut ImpulseJointSet,
    group: &HashSet<RigidBodyHandle>,
) -> bool {
    // Preflight the complete set before changing anything.
    if group.is_empty() || group.iter().any(|h| !bodies.contains(*h)) {
        return false;
    }
    for (_, joint) in joints.iter_mut() {
        if !group.contains(&joint.body1) || !group.contains(&joint.body2) {
            continue;
        }
        joint.impulses = Default::default();
        for limit in &mut joint.data.limits {
            limit.impulse = 0.0;
        }
        for motor in &mut joint.data.motors {
            motor.impulse = 0.0;
        }
    }
    for handle in group {
        bodies.get_mut(*handle).unwrap().wake_up(true);
    }
    true
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_resetGroupJointImpulses<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    ids: JIntArray<'local>,
) -> jboolean {
    if scene_handle == 0 {
        return 0;
    }
    let Ok(length) = env.get_array_length(&ids) else {
        return 0;
    };
    let mut raw = vec![0; length as usize];
    if env.get_int_array_region(&ids, 0, &mut raw).is_err() || raw.iter().any(|id| *id < 0) {
        return 0;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let data = scene
        .sable_data
        .read()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let group: Option<HashSet<_>> = raw
        .into_iter()
        .map(|id| data.rigid_bodies.get(&(id as LevelColliderID)).copied())
        .collect();
    let Some(group) = group else {
        return 0;
    };
    let mut sim = scene
        .sim_data
        .write()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let crate::scene::SimulationSceneData {
        rigid_body_set,
        impulse_joint_set,
        ..
    } = &mut *sim;
    reset_group(rigid_body_set, impulse_joint_set, &group) as jboolean
}

#[cfg(test)]
mod tests {
    use super::*;
    use marten::Real;
    use rapier3d::prelude::*;

    fn chain_joint() -> GenericJoint {
        GenericJointBuilder::new(JointAxesMask::LOCKED_SPHERICAL_AXES)
            .local_anchor1(Vec3::new(0.3, 0.0, 0.0))
            .local_anchor2(Vec3::new(-0.3, 0.0, 0.0))
            .limits(JointAxis::AngX, [-0.4, 0.7])
            .motor_velocity(JointAxis::AngY, 2.0, 0.5)
            .contacts_enabled(false)
            .build()
    }

    #[test]
    fn flexible_chain_resets_every_cache_and_preserves_configuration() {
        let mut bodies = RigidBodySet::new();
        let handles: Vec<_> = (0..4)
            .map(|_| bodies.insert(RigidBodyBuilder::dynamic().build()))
            .collect();
        let mut joints = ImpulseJointSet::new();
        let links: Vec<_> = (0..3)
            .map(|i| joints.insert(handles[i], handles[i + 1], chain_joint(), true))
            .collect();
        for (_, joint) in joints.iter_mut() {
            joint.impulses[0] = 8.0;
            joint.data.limits[3].impulse = 5.0;
            joint.data.motors[4].impulse = -3.0;
        }
        let before: Vec<_> = links
            .iter()
            .map(|h| joints.get(*h).unwrap().clone())
            .collect();
        assert!(reset_group(
            &mut bodies,
            &mut joints,
            &HashSet::from([handles[0], handles[1], handles[2]])
        ));
        for i in 0..2 {
            let mut expected = before[i].clone();
            expected.impulses = Default::default();
            for l in &mut expected.data.limits {
                l.impulse = 0.0;
            }
            for m in &mut expected.data.motors {
                m.impulse = 0.0;
            }
            assert_eq!(*joints.get(links[i]).unwrap(), expected);
        }
        // External endpoint/driver is not part of the transit transaction.
        assert_eq!(*joints.get(links[2]).unwrap(), before[2]);
        assert_eq!(joints.len(), 3);
    }

    #[test]
    fn missing_member_does_not_partially_mutate_the_chain() {
        let mut bodies = RigidBodySet::new();
        let a = bodies.insert(RigidBodyBuilder::dynamic().build());
        let b = bodies.insert(RigidBodyBuilder::dynamic().build());
        let mut joints = ImpulseJointSet::new();
        let link = joints.insert(a, b, chain_joint(), true);
        joints.get_mut(link, false).unwrap().impulses[0] = 9.0;
        let before = joints.get(link).unwrap().clone();
        assert!(!reset_group(
            &mut bodies,
            &mut joints,
            &HashSet::from([a, b, RigidBodyHandle::from_raw_parts(99999, 0)])
        ));
        assert_eq!(*joints.get(link).unwrap(), before);
        assert!(!reset_group(&mut bodies, &mut joints, &HashSet::new()));
    }

    #[test]
    fn rotated_ball_chain_stays_coherent_in_real_solver_steps() {
        let mut bodies = RigidBodySet::new();
        let mut colliders = ColliderSet::new();
        let handles: Vec<_> = (0..5)
            .map(|i| {
                let body = bodies.insert(
                    RigidBodyBuilder::dynamic()
                        .translation(Vec3::new(i as Real * 2.0, 0.0, 0.0))
                        .build(),
                );
                colliders.insert_with_parent(
                    ColliderBuilder::ball(0.25).build(),
                    body,
                    &mut bodies,
                );
                body
            })
            .collect();
        let mut joints = ImpulseJointSet::new();
        for i in 0..handles.len() - 1 {
            let data = GenericJointBuilder::new(JointAxesMask::LOCKED_SPHERICAL_AXES)
                .local_anchor1(Vec3::X)
                .local_anchor2(-Vec3::X)
                .build();
            let joint = joints.insert(handles[i], handles[i + 1], data, true);
            joints.get_mut(joint, false).unwrap().impulses[0] = 100.0;
        }
        let prefix = rapier3d::math::Pose {
            translation: Vec3::new(40.0, 30.0, -20.0),
            rotation: rapier3d::math::Rotation::from_rotation_z(
                std::f64::consts::FRAC_PI_2 as Real,
            ),
        };
        let expected: Vec<_> = handles
            .iter()
            .map(|h| prefix * *bodies.get(*h).unwrap().position())
            .collect();
        for (handle, pose) in handles.iter().zip(&expected) {
            bodies.get_mut(*handle).unwrap().set_position(*pose, true);
        }
        assert!(reset_group(
            &mut bodies,
            &mut joints,
            &handles.iter().copied().collect()
        ));
        let mut pipeline = PhysicsPipeline::new();
        let mut islands = IslandManager::new();
        let mut broad = DefaultBroadPhase::new();
        let mut narrow = NarrowPhase::new();
        let mut multibody = MultibodyJointSet::new();
        let mut ccd = CCDSolver::new();
        let params = IntegrationParameters::default();
        for _ in 0..30 {
            pipeline.step(
                Vec3::ZERO,
                &params,
                &mut islands,
                &mut broad,
                &mut narrow,
                &mut bodies,
                &mut colliders,
                &mut joints,
                &mut multibody,
                &mut ccd,
                &(),
                &(),
            );
        }
        for (handle, pose) in handles.iter().zip(expected) {
            assert!(
                (bodies.get(*handle).unwrap().translation() - pose.translation).length() < 0.001
            );
        }
        assert_eq!(joints.len(), 4);
    }
}
