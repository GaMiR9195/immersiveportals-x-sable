use crate::event_handler::SableEventHandler;
use crate::hooks::SablePhysicsHooks;
use crate::joints::SableJointSet;
use crate::rope::RopeMap;
use crate::{ActiveLevelColliderInfo, ReportedCollision};
use dashmap::DashMap;
use jni::JavaVM;
use marten::Real;
use marten::level::{ChunkSection, OctreeChunkSection};
use rapier3d::dynamics::{
    CCDSolver, ImpulseJointSet, IslandManager, MultibodyJointSet, RigidBodyHandle, RigidBodySet,
};
use rapier3d::geometry::{ColliderSet, DefaultBroadPhase, NarrowPhase};
use rapier3d::glamx::IVec3;
use rapier3d::math::Vec3;
use rapier3d::pipeline::PhysicsPipeline;
use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, RwLock};

pub type LevelColliderID = usize;

pub trait ChunkAccess {
    #[allow(unused)]
    fn get_chunk_mut(&mut self, x: i32, y: i32, z: i32) -> Option<&mut ChunkSection>;
    fn get_chunk(&self, x: i32, y: i32, z: i32) -> Option<&ChunkSection>;
}

#[inline(always)]
pub fn pack_section_pos(i: i32, j: i32, k: i32) -> i64 {
    let mut l: i64 = 0;
    l |= (i as i64 & 4194303i64) << 42;
    l |= j as i64 & 1048575i64;
    l | (k as i64 & 4194303i64) << 20
}

pub type ChunkMap = HashMap<i64, ChunkSection>;

/// IPL fix: this was `RefCell<Vec>` with an `unsafe impl Sync` — but the event handler
/// extends it from rapier's PARALLEL island solver threads (the `parallel` feature is
/// on), so concurrent pushes raced and corrupted the Vec (0xc0000005 JVM death under
/// heavy contact-force volume, e.g. a straddle clone wedged into terrain). A Mutex keeps
/// the same call-site shape; per-batch locking is negligible next to the solve.
pub struct ReportedCollisionBuffer(Mutex<Vec<ReportedCollision>>);

impl ReportedCollisionBuffer {
    pub fn new() -> Self {
        Self(Mutex::new(Vec::with_capacity(16)))
    }

    pub fn borrow_mut(&self) -> std::sync::MutexGuard<'_, Vec<ReportedCollision>> {
        self.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

impl Default for ReportedCollisionBuffer {
    fn default() -> Self {
        Self::new()
    }
}

pub struct SimulationSceneData {
    pub pipeline: PhysicsPipeline,
    pub rigid_body_set: RigidBodySet,
    pub collider_set: ColliderSet,
    pub island_manager: IslandManager,
    pub broad_phase: DefaultBroadPhase,
    pub narrow_phase: NarrowPhase,
    pub impulse_joint_set: ImpulseJointSet,
    pub multibody_joint_set: MultibodyJointSet,
    pub ccd_solver: CCDSolver,
    pub physics_hooks: SablePhysicsHooks,
    pub event_handler: SableEventHandler,
}

pub struct SableSceneData {
    /// A 3-dimensional map of chunk sections for collision.
    /// chunk coordinates -> chunk section
    pub main_level_chunks: ChunkMap,
    pub octree_chunks: HashMap<i64, OctreeChunkSection>,

    /// The companion joint set
    pub joint_set: SableJointSet,

    /// Rope map
    pub rope_map: RopeMap,

    pub level_colliders: HashMap<LevelColliderID, ActiveLevelColliderInfo>,
    pub rigid_bodies: HashMap<LevelColliderID, RigidBodyHandle>,

    /// IPL: body pairs (normalized id order) that must never generate contacts — a
    /// straddle clone vs its own real body, or two clones of one ship, sharing a scene
    /// through a same-dimension portal.
    pub ipl_excluded_pairs: HashSet<(LevelColliderID, LevelColliderID)>,
}

/// A physics scene
pub struct PhysicsScene {
    pub sim_data: RwLock<SimulationSceneData>,
    pub sable_data: Arc<RwLock<SableSceneData>>,

    /// All collisions substantial enough to be considered for collision events.
    pub reported_collisions: Arc<ReportedCollisionBuffer>,

    pub manifold_info_map: Arc<SableManifoldInfoMap>,

    pub current_step_vm: Option<Arc<JavaVM>>,

    /// The handle to a static rigidbody
    pub ground_handle: Option<RigidBodyHandle>,

    /// The current gravity vector for all bodies. [m/s^2]
    pub gravity: Vec3,

    /// Universal linear drag applied to all bodies
    pub universal_drag: Real,
}

#[derive(Default)]
pub struct SableManifoldInfoMap {
    pub list: DashMap<usize, SableManifoldInfo>,
    pub counter: AtomicUsize,
}

impl SableManifoldInfoMap {
    pub fn clear(&self) {
        self.list.clear();
        self.counter.store(0, Ordering::Relaxed);
    }
}

pub struct SableManifoldInfo {
    pub pos_a: IVec3,
    pub pos_b: IVec3,
    pub col_a: usize,
    pub col_b: usize,
}

impl ChunkAccess for SableSceneData {
    fn get_chunk_mut(&mut self, x: i32, y: i32, z: i32) -> Option<&mut ChunkSection> {
        self.main_level_chunks.get_mut(&pack_section_pos(x, y, z))
    }

    fn get_chunk(&self, x: i32, y: i32, z: i32) -> Option<&ChunkSection> {
        self.main_level_chunks.get(&pack_section_pos(x, y, z))
    }
}

impl SableSceneData {
    pub fn get_octree_chunk(&self, x: i32, y: i32, z: i32) -> Option<&OctreeChunkSection> {
        self.octree_chunks.get(&pack_section_pos(x, y, z))
    }

    pub fn get_octree_chunk_mut(
        &mut self,
        x: i32,
        y: i32,
        z: i32,
    ) -> Option<&mut OctreeChunkSection> {
        self.octree_chunks.get_mut(&pack_section_pos(x, y, z))
    }
}
