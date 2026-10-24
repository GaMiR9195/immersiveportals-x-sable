//! Real Rapier fixtures; no JVM, no Minecraft stubs, no alternate solver.
use crate::*;

pub(crate) fn scene(chart: scene::ChartId) -> PhysicsScene {
    let sable_data = Arc::new(RwLock::new(SableSceneData {
        charts: HashMap::new(),
        joint_set: SableJointSet::new(),
        rope_map: RopeMap::default(),
        level_colliders: HashMap::new(),
        rigid_bodies: HashMap::new(),
        bodies_by_chart: HashMap::new(),
        ipl_excluded_pairs: Default::default(),
    }));
    let manifold_info_map = Arc::new(SableManifoldInfoMap::default());
    let chart_buffers = Arc::new(scene::ChartBuffers::default());
    let dispatcher = SableDispatcher {
        sable_data: sable_data.clone(),
        manifold_info_map: manifold_info_map.clone(),
    };
    let mut sim = SimulationSceneData {
        pipeline: PhysicsPipeline::new(),
        rigid_body_set: RigidBodySet::new(),
        collider_set: ColliderSet::new(),
        island_manager: IslandManager::new(),
        broad_phase: DefaultBroadPhase::new(),
        narrow_phase: NarrowPhase::with_query_dispatcher(dispatcher.chain(DefaultQueryDispatcher)),
        impulse_joint_set: ImpulseJointSet::new(),
        multibody_joint_set: MultibodyJointSet::new(),
        ccd_solver: CCDSolver::new(),
        physics_hooks: SablePhysicsHooks {
            sable_data: sable_data.clone(),
            manifold_info_map: manifold_info_map.clone(),
            current_step_vm: None,
        },
        event_handler: SableEventHandler {
            sable_data: sable_data.clone(),
            chart_buffers: chart_buffers.clone(),
        },
    };
    let ground = sim.rigid_body_set.insert(RigidBodyBuilder::fixed());
    let sim_data = Arc::new(RwLock::new(sim));
    let world = Arc::new(scene::WorldCore {
        sim_data: sim_data.clone(),
        sable_data: sable_data.clone(),
        manifold_info_map: manifold_info_map.clone(),
        chart_buffers,
        ground_handle: ground,
        world_gravity: Vec3::ZERO,
        next_chart: std::sync::atomic::AtomicUsize::new(0),
    });
    PhysicsScene {
        world,
        sim_data,
        sable_data,
        reported_collisions: Arc::new(ReportedCollisionBuffer::new()),
        manifold_info_map,
        current_step_vm: None,
        ground_handle: Some(ground),
        gravity: Vec3::ZERO,
        universal_drag: 0.0,
        chart,
        terrain_collider: None,
    }
}

pub(crate) fn body(
    scene: &PhysicsScene, id: scene::LevelColliderID, chart: scene::ChartId, center: DVec3,
) -> RigidBodyHandle {
    let mut sim = scene.sim_data.write().unwrap();
    let handle = sim.rigid_body_set.insert(RigidBodyBuilder::dynamic());
    let collider = sim.collider_set.insert(ColliderBuilder::ball(0.5));
    drop(sim);
    let mut data = scene.sable_data.write().unwrap();
    let mut info = ActiveLevelColliderInfo::new(collider, chart);
    info.center_of_mass = Some(center);
    data.level_colliders.insert(id, info);
    data.rigid_bodies.insert(id, handle);
    data.bodies_by_chart.entry(chart).or_default().insert(id);
    handle
}

#[test]
fn environment_is_independent_of_storage_and_images() {
    let scene = scene(9);
    body(&scene, 1, 4, DVec3::ZERO);
    let mut data = scene.sable_data.write().unwrap();
    let info = data.level_colliders.get_mut(&1).unwrap();
    assert_eq!(info.environment_chart.unwrap_or(info.chart), 4);
    info.environment_chart = Some(2);
    assert_eq!(info.environment_chart.unwrap_or(info.chart), 2);
    assert_eq!(info.chart, 4); // no body migration
    // A parent flip changes only the sample chart; portal image order is irrelevant.
    info.environment_chart = Some(7);
    assert_eq!(info.environment_chart.unwrap_or(info.chart), 7);
    assert_eq!(info.chart, 4);
}
#[test]
fn buoyancy_reads_parent_water_but_storage_materials() {
    use marten::level::VoxelColliderData;
    PHYSICS_STATE.get_or_init(|| {
        let mut map = VoxelColliderMap::new();
        map.voxel_colliders.push(Some(VoxelColliderData {
            collision_boxes: vec![(0., 0., 0., 1., 1., 1.)],
            is_fluid: false, friction: 0.5, volume: 1., restitution: 0.,
            contact_events: None, contact_method: None, dynamic: false,
        }));
        RwLock::new(PhysicsState {
            integration_parameters: IntegrationParameters::default(),
            voxel_collider_map: map,
        })
    });
    let scene = scene(4);
    let plot = IVec3::new(20_000_000, 0, 20_000_000);
    let handle = body(&scene, 1, 4, plot.as_dvec3() + 0.5);
    {
        let mut data = scene.sable_data.write().unwrap();
        let info = data.level_colliders.get_mut(&1).unwrap();
        info.local_bounds_min = Some(plot);
        info.local_bounds_max = Some(plot + IVec3::ONE);
        let mut tree = SubLevelOctree::new(1);
        tree.insert(0, 0, 0, 0);
        info.octree = Some(tree);
        let mut material = ChunkSection::new(vec![(0, VoxelPhysicsState::Empty); 4096]);
        material.set_block(0, 0, 0, (1, VoxelPhysicsState::Face));
        data.chart_mut(4).main_level_chunks.insert(
            pack_section_pos(plot.x >> 4, 0, plot.z >> 4), material);
        let mut water = OctreeChunkSection::new();
        water.liquid_octree.insert(0, 0, 0, 0);
        data.chart_mut(2).octree_chunks.insert(pack_section_pos(0, 0, 0), water);
    }
    scene.sim_data.write().unwrap().rigid_body_set[handle]
        .set_translation(Vec3::splat(0.5), true);
    crate::buoyancy::compute_buoyancy(&scene);
    assert_eq!(scene.sim_data.read().unwrap().rigid_body_set[handle].user_force(), Vec3::ZERO);
    scene.sable_data.write().unwrap().level_colliders.get_mut(&1).unwrap()
        .environment_chart = Some(2);
    crate::buoyancy::compute_buoyancy(&scene);
    assert!(scene.sim_data.read().unwrap().rigid_body_set[handle].user_force().y > 0.0,
        "water in parent chart must lift the material stored at the 20M plot");
    scene.sable_data.write().unwrap().level_colliders.get_mut(&1).unwrap()
        .environment_chart = Some(7);
    crate::buoyancy::compute_buoyancy(&scene);
    assert_eq!(scene.sim_data.read().unwrap().rigid_body_set[handle].user_force(), Vec3::ZERO,
        "a live parent flip must stop sampling the previous parent's water");
}
