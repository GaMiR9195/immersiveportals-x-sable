# Переименования и удаления 0.6.0

Таблицы — для переноса локальных правок на текущий код. Миксины названы по целевому классу, мелкие
миксины одного класса объединены.

## Миксины и классы

| Было | Стало |
|---|---|
| `IplClipContextAccessor` | `ClipContextAccessor` |
| `IplForeignPlotWriteGuardMixin` | `LevelMixin` |
| `IplPortalVolumeCacheInvalidationMixin` | `LevelPlotMixin` |
| `IplServerStopCleanupMixin` | `MinecraftServerMixin` |
| `IplProjectionProjectileHitMixin` | `ProjectileMixin` |
| `SableSeatMountDimensionStampMixin` | `ServerCommonPacketListenerMixin` |
| `IplServerEntityFrameEncodeMixin` | `ServerEntityMixin` |
| `IplServerPlayerFrameStickMixin` | `ServerPlayerMixin` |
| `IplMovePlayerPacketAccessor` | `MovePlayerPacketMixin` |
| `SableStartTrackingDedupeMixin` | `StartTrackingPacketMixin` |
| `IplPlungerClientTargetFixMixin` | `PlungerEntityMixin` |
| `IplStaffServerHandlerAccessorMixin` | `StaffServerHandlerAccessor` |
| `IplStaffServerStateMixin` | `StaffServerHandlerMixin` |
| `IplStaffDragSessionOverwriteMixin` | `StaffDragSessionMixin` |
| `IplDisassemblyPortalRestoreMixin` | `SimAssemblyHelperMixin` |
| `IplStaffPacketRoutingMixin` | `StaffPacketsMixin` |
| `IplProjectionIntersectionMixin` | `CompanionIntersectMixin` |
| `IplStraddleProjectOutMixin` | `CompanionProjectOutMixin` |
| `IplStraddleCanFallMixin` | `CanFallHelperMixin` |
| `IplStraddleOnPosMixin` | `EntityOnPosMixin` |
| `IplProjectionBlockContactMixin` | `EntityBlockContactMixin` |
| `IplStraddleInBlockPoseMixin` | `EntityInBlockMixin` |
| `SableCollisionInfoInitMixin` | `EntityCollisionInfoMixin` |
| `IplClientKickPoseGuardMixin` | `EntitySubLevelUtilMixin` |
| `SableLevelAcceleratorMixin` | `LevelAcceleratorMixin` |
| `IplMergedMassBaselineAccessor` | `MergedMassTrackerAccessor` |
| `IplProjectionTerrainTicketMixin` | `ChunkTicketManagerMixin` |
| `IplStraddleInteractDistanceMixin` | `PlayerMixin` |
| `IplNativesOverrideMixin` | `Rapier3DMixin` |
| `IplRapier3DInvoker` | `Rapier3DInvoker` |
| `IplRapierConstraintHandleAccessor` | `RapierConstraintHandleAccessor` |
| `IplRapierPipelineAccess` | `RapierPipelineAccessor` |
| `IplRopeObjectAccess` | `RopeObjectAccessor` |
| `IplServerProjectionClipMixin` | `ServerLevelMixin` |
| `SableSubLevelTransitMixin` | `ServerSubLevelContainerMixin` |
| `IplSubLevelPreviousPoseAccessor` | `SubLevelAccessor` |
| `IplAssemblyPortalAttachMixin` | `AssemblyHelperMixin` |
| `IplSubLevelContainerOriginAccessor` | `SubLevelContainerAccessor` |
| `IplProjectionEntityGetterMixin` | `EntityGetterProjectionMixin` |
| `SableSubLevelGetterBridgeMixin` | `EntityGetterBridgeMixin` |
| `IplFusedStepMixin` | `PhysicsSystemMixin` |
| `IplPhysicsSystemFusionAccess` | `PhysicsSystemAccessor` |
| `SableCrossDimTrackingMixin` | `TrackingSystemMixin` |
| `IplVoxelShapeClipMixin` | `VoxelShapeMixin` |
| `IplLevelRendererAccessor` | `LevelRendererAccessor` |
| `IplSectionOcclusionGuardMixin` | `LevelRendererOcclusionMixin` |
| `IplProjectionRenderMixin` | `LevelRendererProjectionMixin` |
| `IplStraddleOutlineMixin` | `LevelRendererOutlineMixin` |
| `IplLocalPlayerFrameEncodeMixin` | `LocalPlayerMixin` |
| `SableClientTickMixin` | `MinecraftMixin` |
| `IplRenderSectionGlobalsAccessor` | `RenderSectionAccessor` |
| `IplSectionRenderDispatcherAccessor` | `SectionRenderDispatcherAccessor` |
| `IplFlywheelCompilationMixin` | `FlywheelCompilationMixin` |
| `IplGhostStopTrackingMixin` | `StopTrackingPacketMixin` |
| `IplStaffClientHandlerAccessor` | `StaffClientHandlerAccessor` |
| `IplStaffBeamAccessor` | `StaffBeamAccessor` |
| `IplStaffBeamMixin` | `StaffBeamMixin` |
| `IplStaffBeamNodeAccessor` | `StaffBeamNodeAccessor` |
| `IplStaffMouseFrameMixin` | `StaffMouseHandlerMixin` |
| `IplStaffRenderPoseMixin` | `StaffRenderPoseMixin` |
| `IplProjectionRenderSodiumMixin` | `SodiumWorldRendererMixin` |
| `IplIrisShaderClipMixin` | `IrisExtendedShaderMixin` |
| `IplRopePacketRoutingMixin` | `RopePacketsMixin` |
| `IplClusterOutlineAnchorMixin` | `BlockClusterOutlineMixin` |
| `IplProjectionAwareClipMixin` | `ClientLevelMixin` |
| `IplInterpolationClockAccessor` | `InterpolationClockAccessor` |
| `IplInterpolationClockMixin` | `InterpolationClockMixin` |
| `IplClientSubLevelRenderPoseAccessor` | `ClientSubLevelAccessor` |
| `IplClientTransitDisconnectMixin` | `ClientWorldLoaderMixin` |
| `IplFinalizeSubLevelMixin` | `FinalizeSubLevelPacketMixin` |
| `IplFlywheelEmbeddedClipMixin` | `FlywheelEmbeddedEnvironmentMixin` |
| `IplFlywheelEnvironmentStorageMixin` | `FlywheelEnvironmentStorageMixin` |
| `IplFlywheelVisualizationSupportMixin` | `FlywheelVisualizationManagerMixin` |
| `IplFlywheelLightStorageMixin` | `FlywheelLightStorageMixin` |
| `IplGlUseProgramProbeMixin` | `GlStateManagerMixin` |
| `IplProgramManagerProbeMixin` | `ProgramManagerMixin` |
| `VeilPacketContextLevelMixin` | `VeilPacketContextMixin` |
| `IplSubLevelRendererMixin` | `SubLevelRendererMixin` |
| `IplSnapshotInterpolatorAccessor` | `SnapshotInterpolatorAccessor` |
| `SableSourceClipMixin` | `ChunkedRenderDataMixin` |
| `SableVanillaSubLevelBERMixin` | `SubLevelBlockEntityRendererMixin` |
| `IplSingleBlockChunkRenderMixin` | `RenderDispatcherSingleBlockMixin` |
| `SableSubLevelBlockEntityClipMixin` | `RenderDispatcherBlockEntityClipMixin` |
| `IplClientSubLevelPoseOverrideMixin` | `ClientSubLevelMixin` |
| `IplClientTransitCleanupMixin` | `ClientSubLevelMixin` |
| `IplConstraintTrackerMixin` | `RapierPipelineMixin` |
| `IplRapierStepArmMixin` | `RapierPipelineMixin` |
| `IplStaffItemAimMixin` | `StaffItemRendererMixin` |
| `IplStaffItemFocusMixin` | `StaffItemRendererMixin` |
| `IplStaffPickMixin` | `StaffClientHandlerMixin` |
| `IplStaffFocusMixin` | `StaffClientHandlerMixin` |
| `IplShaderInstanceClipMixin` | `ShaderInstanceMixin` |
| `IplShaderUniformProbeMixin` | `ShaderInstanceMixin` |
| `IplStraddleBlockClipMixin` | `SubLevelEntityCollisionMixin` |
| `IplStraddleCollisionPoseMixin` | `SubLevelEntityCollisionMixin` |
| `IplPlotBridgeMixin` | `SubLevelContainerMixin` |
| `IplUnionPlotAllocationMixin` | `SubLevelContainerMixin` |
| `IplProjectionPacketRoutingMixin` | `ServerGamePacketListenerMixin` |
| `IplMovePlayerFrameDecodeMixin` | `ServerGamePacketListenerMixin` |
| `IplPendingHandoffSnapshotMixin` | `InterpolationClockMixin` |
| `SableTransitController` | `IplTransitController` |
| `SableTransitOps` | `IplTransitOps` |
| `SourceClipDiag` | `IplClipDiagnostics` |
| `SourceClipPortalFinder` | `IplClipPortalFinder` |
| `IplPerLevelSubLevelRenderDispatcher` | `IplLevelRenderDispatcher` |
| `SubLevelBlockEntityRenderScope` | `IplBlockEntityRenderScope` |
| `SubLevelClipUniformPatcher` | `IplClipUniformPatcher` |

## Перенесённые, объединённые и удалённые

| Было | Стало |
|---|---|
| `transit/IplTrackingStamp`, `duck/IplTrackingStamp` | удалён: снимки всех уровней штампуются одной меткой `IplTimeline`, клиент держит одни часы интерполяции `client/IplSharedClock` |
| RPC `IplParentDimSync.RemoteCallables.move(…, inverseGrabLink, clockOffset)` | `move(…, inverseGrabLink)`: при общей метке разница часов не нужна |
| `IplParentDimSync.onSuccessorTracked` | `IplParentDimSync.continueFromGhost`: преемник продолжает призрака — его позы и более старые снимки с теми же метками |
| `IplStaffPortalBeamRenderer.findHostedSubLevel` | `IplClientProjections.findSubLevelAtPlot` |
| `IplTransitOps.findFirstEmptyPlotForMirror` | `IplTransitOps.findFirstEmptyPlot` |
| `IplTransitOps.copyPlotBlocksPublic` | `IplTransitOps.copyPlotBlocks` |
| `IplTransitOps.executeTransit` | удалён, переезд выполняет `IplShipFlip` |
| RPC `IplEnteringVolumeRenderer.RemoteCallables.setMode(value, ignoredShipId, encodedShape)` | `setMode(value, shipId)`, форма приходит через `shapePart` |
| `IplDimSwitchGuard` | удалён: сглаживание позы и удержание света при смене измерения не нужны, причина рывка исправлена в шаге физики и переезде |
| `IplRopePortalSeam`, `RopeObjectAccessor` | удалены вместе со швом верёвок через портал и свойствами `ipl.sable.ropePortalSeam`, `ipl.sable.ropeBreakStretch` |
| `IplPortalRimManager`, `IplStraddlePortalRim` | удалены, рамки портала были отключены и ничего не делали |
| `IplSubLevelUniformRegistry` | удалён, не использовался |
| JNI `createPortalRim`, `removePortalRim`, `setPortalRimTransform`, `remapRope`, `overstretchedRopes`, `setRopePortalPrefix`, `setBodyDormant`, `setBodyPairExclusion`, `setParentFrame` | удалены из `natives/` вместе с состоянием сцены и проверками в диспетчере и хуках, Java их не вызывала |
