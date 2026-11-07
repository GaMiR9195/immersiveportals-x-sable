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
| `IplDimSwitchGuard` | удалён: сглаживание позы и удержание света при смене измерения не нужны, причина рывка исправлена в шаге физики и переезде |
| `IplRopePortalSeam`, `IplRopeObjectAccess` (`RopeObjectAccessor`) | удалены вместе со швом верёвок через портал и свойствами `ipl.sable.ropePortalSeam`, `ipl.sable.ropeBreakStretch` |
| `IplPortalRimManager`, `IplStraddlePortalRim` | удалены, рамки портала были отключены и ничего не делали |
| `IplSubLevelUniformRegistry` | удалён, не использовался |
| JNI `createPortalRim`, `removePortalRim`, `setPortalRimTransform`, `remapRope`, `overstretchedRopes`, `setRopePortalPrefix`, `setBodyDormant`, `setBodyPairExclusion`, `setParentFrame` | удалены из `natives/` вместе с состоянием сцены и проверками в диспетчере и хуках, Java их не вызывала |
| `PortalCrossingDetector`, `PortalSweepMath`, `PortalAperture`, `PortalOrder`, `PortalSectionMath`, `IplPortalVolumeCache` | удалены вместе с детектором заметаемого объёма: что прошло через портал, решают метки кусков `transit/IplPieceTracker` (зоны апертур, рёбра, открытие и закрытие сессий) и `transit/IplPieceMap` (метки по секциям); `LevelPlotMixin` при правке блока обновляет метки, а не кэш объёма |
| `IplTransitController.endSession`, `getEarlyOpenSegments`, `setEarlyOpenSegments`, `DEFAULT_EARLY_OPEN_SEGMENTS`, команда `early_open_segments` | удалены: сессии открывает и закрывает `IplPieceTracker`, все сессии тела закрывает `IplStraddleSessionSync.endAllFor` |
| `IplAtlasStraddleSession.isEnabled`, `isWide`, `isAdmitted`, `markAdmitted`, `isCompleted`, `markCompleted`, `revokeCompletion`, `outranks`, `downstreamOfSibling`, `onStraddleTick`, `onChainTick`, `forEachSessionFrom`, `isInMappedHalf` | `open`, `refreshShip`, `sessionNode`, `supports`: сессия — узел дерева кусков, без трубок, допуска и приоритета соседей |
| `IplProjectionTree.keepInterval`, `beyondInterval`, `beyondContains`, `yieldedIntervals`, `yieldsAt`, `yieldsAnything`, `WIDE_EXTENT`, `LATERAL_MARGIN`, `SKIN` | куски: `pieces`, `node`, `parentId`, `labelOf`, `pieceOfPlot`, `edges`, `edgeInto`, `hasMaterial` |
| `IplStraddlePoseMap.getBlockCollisionKeepFilter`, `getBlockCollisionKeepPlanes`, `getSourceHalfKeepFilter`, `frameCuts`, `RealHalfSpace`, `isInMappedHalf` | `getBlockCollisionCut` (`PieceCut`: кусок кадра и плоскости зон), `keeps`, `planeFor` |
| `IplStraddleCollisionClip.setPlanes`, `noteBlock` | `install`, `filter`, `clip`, `clear`, `hasCut`: блоки фильтруются по куску, обрезаются только блоки зон |
| `IplVoxelShapeClipMixin` (`VoxelShapeMixin`) | удалён: срез коллизий сущностей по куску делает `SubLevelEntityCollisionMixin` через `IplStraddleCollisionClip` |
| `SourceClipPortalFinder` (`IplClipPortalFinder`), `IplRenderCullMath`, `IplClientVisualTransitLatch`, решения `IplStraddleRenderCache` (`decision`, `cacheDecision`, `allDecisions`, `cacheAllDecisions`, `renderPortals`, `cacheRenderPortals`, `portalCandidates`, `cachePortalCandidates`, `canonicalPortalFace`, `cacheCanonicalPortalFace`, `hasDecision`) | удалены: клиент не ищет портал и не предсказывает вход, а рисует куски по меткам и узлам сессий с сервера (`render/IplPieceRender`, `render/IplSectionRanges`, миксины `BufferBuilderAccessor`, `MeshDataMixin`, `VertexBufferMixin`) |
| `IplStraddleRenderState.getPortalFor`, `getPlaneFor`, `getPlane2For` | `getNodeFor`, `isActive` |
| `IplStraddleSessionStore.resolvePortal`, `resolveAllPortals`, `resolveRenderPortal`, `resolveAllRenderPortals`, `resolveChains`, `resolveRenderChains`, `isWide`, `captureGhostTails`, `clearGhostTails`, `beginHandoffVisual`, `clearHandoffVisual`, `clearRetiredForHandoff` | `session`, `sessions`, `label`, `labels`, `pieces`, `treeEntries`, `lookupPortal`, `renderView` (`RenderView`, `ResolvedSession`), `captureGhostView`, `clearGhostView` |
| `IplStraddleSessionSync.retainOnly` | `forgetAllFor`; метки уходят через `sendLabels`, не больше 64 секций в пакете |
| `IplStaffBeamRoutes.isPastPlane` и гистерезис у плоскости | удалены: представление якоря луча выбирает кусок, которому принадлежит якорь |
| `IplPerfDiagnostics.CLIENT_PORTAL_SCAN` (`client-portal-scan`) | `CLIENT_PIECE_DRAW` (`client-piece-draw`): время отрисовки кусков |
| `IplEnteringVolumeVisualization`, `IplEnteringVolumeRenderer` с RPC `setMode`, команда `visualize_sweep_detector` | удалены вместе с оверлеем объёма входа |
| `-Dipl.sable.clip.seamSupportMargin` | удалено: шов — плоскость апертуры внутри зоны |
| JNI `setClipRegions`, `setClipRegionsV2`, `setImageClipRegions`, `setImageClipRegionsV2`, константы `REGION_KEEP`, `REGION_EXCLUDE`, `REGION_STRIDE_V2` | ABI 3: `setPieceSection`, `setPieceEdges`, `clearPieces`, `setImageNode`, `EDGE_STRIDE`, `PIECE_ABI`; `IplRapierNatives.piecesSupported()` сообщает, умеют ли нативы куски |
| `tests/portal`, `tests/render/GeometryTest` (проверял `PortalSectionMath`), фикстура `IplClipPortalFinder` в `tests/render` | удалены вместе с детектором; модель кусков проверяет `tests/pieces` |
| `MAX_CHAIN_DEPTH`, `MAX_SESSIONS_PER_SHIP`, `MAX_TRANSIT_GROUP`, `MAX_CHAIN_LINKS` | прежние лимиты удалены; остаются 255 меток кусков на тело и бюджет флипов на тик |
| RPC посоха со строкой всей цепи | seed и snapshot передают список звеньев в NBT; клиент и сервер должны совпадать по версии |
| JNI ABI 4 | добавлены `canMoveGroupRopes`, `moveGroupRopes` и `ROPE_TRANSIT_ABI` для переноса подходящих верёвок в пределах измерения |
