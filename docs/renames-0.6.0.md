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
| Широкая область сессии: `WIDE_EXTENT`, `IplAtlasStraddleSession.isWide`, `IplStraddleSessionStore.isWide`, `withoutCoveredSplits`, флаг `wide` в `onStraddleTick`, `onChainTick`, `Promotion`, `IplProjectionTree.Entry` | удалена: область сессии — призма прохода `transit/PortalPrism`. Вместо флага — якорь прохода: `onStraddleTick(…, @Nullable Vec3 anchorAt)`, `onChainTick(…, anchorAt)`, `Promotion(…, Vec3 anchor)`, `Entry(path, via, portal, @Nullable Vec3 anchor)`, `IplAtlasStraddleSession.sessionAnchor`, `IplStraddleSessionStore.anchor` |
| Ключ синхронизации `ipl_session_wide` | `ipl_session_anchor`: plot-local якорь, список из трёх double |
| `IplProjectionTree.SKIN`, `LATERAL_MARGIN`, `Node.halfW`, `Node.halfH`; `IplAtlasStraddleSession.CLIP_SKIN`, `LATERAL_MARGIN`; `PortalOrder.LATERAL_MARGIN` | `PortalPrism.SKIN`, `PortalPrism.LATERAL_MARGIN`, `IplProjectionTree.Node.prism` |
| `PortalOrder.downstreamOf(q, p, pWide)`, `strictlyDownstreamOf(q, qWide, p, pWide)` | `downstreamOf(q, PortalPrism p)`, `strictlyDownstreamOf(q, qPrism, p, pPrism)` |
| `PortalOrder.coveredBy` | удалён вместе с широкой областью: кусок, целиком уступленный соседу, пуст и не рисуется |
| `PortalCrossingDetector.retainsAdmittedSession` | удалён: сессию держит материал в призме, `PortalCrossingDetector.pieceHoldsMaterial` |
| `PortalCrossingDetector.noMaterialBeyondOtherPortals` | `PortalCrossingDetector.flipReadyThrough`: в родном куске нет блоков, из нескольких корней тело берёт тот, где больше блоков; без сессии — `crossedWithinAperture`. Якорь новой сессии — `entryAnchor` |
| `IplAtlasStraddleSession.isInMappedHalf`, `IplStraddlePoseMap.isInMappedHalf`, `getSourceHalfKeepFilter`, `frameCuts`, `RealHalfSpace`, `LocalHalfSpace`, `clipLocalBoxKeeping`, `clipBoxKeeping` | удалены вместе с делением по полупространствам: `IplStraddlePoseMap.frameRegion`, `blockFilter` и `transit/IplCollisionRegion` (выпуклые куски, резка коробок на части) |
| `IplStraddlePoseMap.getBlockCollisionKeepPlanes` | `getBlockCollisionRegion` (`IplCollisionRegion`) |
| `IplStraddleCollisionClip.setPlanes(List<LocalHalfSpace>)` | `setRegion(IplCollisionRegion)`; одна коробка может дать несколько частей |
| `IplClipPortalFinder.ClipDecision(portal, plane)`, `secondPlaneFor`, `findStraddlingPortalPlanes` | `ClipDecision(portal, List<Plane> planes)`: первая ячейка родного куска из `IplClientProjections.RenderPlan` |
| `IplClientProjections.StraddleProjection(…, destPlane, via, mapping, exclude)`, `computeMappedPose`, `keepsAnything` | `StraddleProjection(sub, portal, mappedPose, List<Plane> planes, via, mapping)`, по проекции на ячейку (`isNative` — ещё одна ячейка родного куска); план кадра — `renderPlan(sub)` → `RenderPlan(nativeDecision, projections)`, пустые ячейки отсеивает `render/IplClipCells.hasInterior` |
| `IplStraddleRenderCache.decision`, `cacheDecision`, `hasDecision`, `allDecisions`, `cacheAllDecisions` | `plan`, `cachePlan` |
| `IplStraddleRenderState.getPlaneFor`, `getPlane2For` | `getPlanesFor` |
| `IplClipUniformPatcher.patchForSubLevel(sub, plane, plane2)`, `getCurrentSubLevelEqWorld/Eye/VanillaInput` и их `…2` | `patchForSubLevel(sub, List<Plane>)`, `getCurrentSubLevelEq…(int slot)`, `SLOTS = 6`, `disableSlots`, `hasSubLevelClip` |
| `IplSubLevelClipShader.ipl$getSubLevelClipUniform()`, `ipl$getSubLevelClipUniform2()` | `ipl$getSubLevelClipUniform(int slot)`; GLSL `ipl_subLevelClipEquation[2]` → `ipl_subLevelClipEquation[6]`, `gl_ClipDistance[1..6]`; имя элемента — `IplProgramBindHook.subLevelUniformName` |
