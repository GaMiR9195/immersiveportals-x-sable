# IPSable 0.6.0 — общая граница storage / physical world

## Установка и состав

- Minecraft 1.21.1, NeoForge; основа проверки — Sable **2.0.6**.
- Установочный файл: `mods/immersive-portals-x-sable-0.6.0-all.jar` в этом архиве. Заменить предыдущий IPSable, не держать две версии одновременно. Для общей серверной логики обновить и сервер, и клиент.
- Остальные зависимости остаются нужны. Этот JAR не заменяет установленные Sable, Create, Simulated/Aeronautics, Flywheel и т. п.
- `source/immersiveportals-x-sable-0.6.0/` — полный исходный проект, включая изменённый Atlas Rapier, скрипты и natives. Кэши Gradle, игровые runs, Rust target и чужие архивы исходников исключены.
- Версия форка (`fork_version`) и версия в mods.toml/JAR — **0.6.0**. `mod_version=6.0.7` оставлен как версия upstream IP; `sable_version=2.0.6` не понижен.
- Перед проверкой сделать резервную копию мира. Особенно важно для миров, сохранённых с прежним ошибочным height-routing.

## Что принципиально изменено

Это не набор исправлений предметов. Sub-level действительно хранится в `ipl_sable:sublevels`; его Level/Dimension не маскируются под обычный мир. Разделены: физическое владение контейнера, публичное представление мира, plot-local координаты, физический parent, native pipeline и жизненный цикл visual/entity.

В этой итерации удалён **наш PYM-specific hook** `IplScaledClipReadViewMixin`. Классы Pocket Sized/PYM и классы верёвок не изменены и не вложены в JAR. Для Flywheel изменён общий жизненный цикл хранилища/embedding, а не Mechanical Press или Swivel Bearing.

Оговорка: весь проект не стал «вообще без mod-specific compatibility». В нём сохранены старые интеграции, включая Create Redstone Link bridge и portal/staff-совместимость. Я не выдаю их за универсальный API. Новые правки 0.6.0 ниже в основном находятся в общей Sable/Level/entity границе; Flywheel — отдельно как базовый rendering API.

### 1. Публичный Sable API больше не требует временно включённого scope

Раньше `getAllSubLevels()` обычного мира мог видеть hosted ships только внутри специальных scoped вызовов. Произвольный потребитель API, например size handshake, не обязан знать о наших scopes: вне них он получал неполный список.

Теперь публичное перечисление обычного мира содержит его живые hosted sub-levels постоянно, с identity-dedup и проверкой точного parent. Storage перечисляет своих владельцев честно. Исходные ownership-списки не переписываются; составной read view — отдельный список.

Это НЕ означает, что надо дважды тикать, сохранять, отслеживать или рисовать ship. Внутренние storage/physics/tracking/close-потребители получают отдельный owned-list. Собственные циклы IPL, которым нужно именно владение, тоже переведены на него.

Файлы:
- `mixin/IplPlotBridgeMixin.java`, `dim/IplSubLevelReadViews.java`;
- `dim/IplOwnedSubLevels.java`, `mixin/IplOwnedSubLevelsAccessor.java`;
- `mixin/IplOwnedSubLevelConsumersMixin.java`, `IplOwnedContainerCloseMixin.java`;
- ownership loops в `atlas/IplFusedStep.java`, `transit/SableRehomeOps.java`, `SableTransitController.java`, `IplShipPortalAnchor.java`, `mixin/SableCrossDimTrackingMixin.java`.

#### Рендер и Mixin merger

Обычный wildcard injector не перехватывал уже merged Sable handlers в LevelRenderer. Поэтому добавлен `IplSableRenderOwnershipPlugin.java`: он меняет перечисление только в методах, чьё `MixinMerged` происхождение указывает на stock Sable sublevel-render mixins. Не по случайному generated имени, не глобально по всем аддонам.

Реальная трансформация проверена для vanilla LevelRenderer (7 owned submissions) и SodiumWorldRenderer (4). Отдельно проверены provenance, idempotence и сохранение публичного server API. Это предотвращает двойную подачу геометрии после расширения публичного списка. Iris/Veil здесь не патчились; отсутствие игрового shader-теста оговорено ниже.

### 2. Height profile: секции storage неизменны, проверки адресные

Удалены контекстные подмены `getMinBuildHeight`, `getMaxBuildHeight`, `getSectionsCount` у storage Level в `IplHostedWorldFrameRouterMixin`.

Такую подмену нельзя делать даже «только на время»: ChunkAccess использует эти значения для выделения и индексации массива секций. Например, parent Nether с minY=0 и storage с minY=-64 дают разные индексы одного Y. Это могло рассинхронизировать видимый блок, block state и физику.

Отдельно добавлен **position-specific** профиль: если Level API обращается к живой plot-позиции именно своего hosted ship, ранний height-gate использует профиль его storage. Для обычного terrain, чужого parent или удалённого plot остаётся обычное ограничение мира. Scalar-высота самого Level не меняется.

Также адресно исправлен max-height gate серверных use-item-on / block-break packets. Reach, permissions, предметы, проверка пакетов не отключались.

Файлы: `dim/IplHostedPlotHeight.java`, `mixin/IplHostedPlotHeightMixin.java`, `IplHostedInteractionHeightMixin.java`, удалённые height-overrides в `IplHostedWorldFrameRouterMixin.java`. Существующий `IplLevelAcceleratorOverrideMixin` продолжает читать секции по профилю фактического chunk.

**Важно:** это не конвертер уже испорченных сохранений. Sable сериализует sections по индексам массива. Нельзя обещать автоматическое восстановление блоков, ранее записанных с неверным смещением. Проверять отдельно свежую сборку и старую конструкцию на копии мира.

### 3. Flywheel ghosts: проверяется настоящее хранилище, а не только наша очередь

Старая weak-bookkeeping очередь не знала обо всех visuals, созданных штатными путями Sable/Flywheel. Поэтому одних событий setRemoved/queueRemove было недостаточно.

Теперь перед Flywheel frame plans проверяются keys **фактического visual storage**. Для hosting BE проверяется: ship жив, holder/chunk существует, в chunk по этой позиции лежит **тот же объект BE**, state имеет BE, тип совместим с state. Призрак удаляется и тогда, когда исчезновение прошло без ожидаемого setRemoved. Новая BE на той же позиции не должна потерять свой visual из-за удаления старой identity.

Сохранены исправления posLookup, удаления embedding, выбора фактического parent manager, scale в embedding transform и удаления старых embedding при recreateAll. F3+A для этого пути не требуется.

Файлы: `client/IplHostedBlockEntityLiveness.java`, `IplClientFlywheelReroute.java`, `mixin/client/IplHostedFlywheelStorageMixin.java`.

### 4. Невидимые верёвки: общая ошибка BER gate

Списки compiled sections исключают `shouldRenderOffScreen` block entity renderers. Предыдущий gate проверял только эти списки и пропускал весь enclosing render scope, если обычных BER нет. До уже существующего global BER прохода выполнение не доходило.

Gate теперь учитывает живые BE в фактических загруженных plot chunks. Это даёт исполниться общему off-screen/global BER проходу внутри того же ship transform / clip / buffers scope. Нет правок Simulated RopeConnectorRenderer, RopeManager или конкретных верёвочных предметов.

Файлы: `render/IplSubLevelRenderVisibility.java`, `IplSubLevelBlockEntityPresence.java`; используется общий `client/IplHostedGlobalBlockEntities.java`.

### 5. Retained entities, glue и KinematicContraption — разные виды владения

Нельзя без разбора переносить все retained entities в storage и нельзя пересоздавать их как обычный межмировой teleport.

- Retained plot volumes/auxiliary entities принадлежат plot storage.
- Actors, реализующие **Sable KinematicContraption API**, остаются настоящими entities физического parent. Их world-keyed collision/entity state должно тикать там; native voxel actor работает в pipeline владельца mount.
- Различение по Sable interface и retain tag, а не по ID мода/блока.

Перенос retained entity сохраняет сам объект, UUID и произвольный bounding volume. Это важно для glue/selection entities: обычный moveTo/setPos может пересоздать маленький стандартный AABB вместо всего glue volume. Старый manager снимается своим lifecycle callback; нормальная target join validation остаётся. При отказе или исключении join listener предусмотрен rollback, включая частично добавленную target entity. Явный rollback не должен снова попасть в наш же owner reroute.

Первичный rehome Kinematic actors больше не использует remove + NBT recreation: actor снимается с source plot/pipeline и регистрируется в destination plot/pipeline через Sable API, оставаясь тем же parent entity. Остальные retained entities переносятся с сохранением bounds. Это не новый алгоритм перемещения через портал.

Симметрично исправлен pipeline remove(actor): если mount уже исчез из lookup, удаление всё равно пробуется в storage pipeline; фактический local actor не перенаправляется.

Файлы: `dim/IplHostedEntityOwnership.java`, `mixin/IplEntityLifecycleAccessor.java`, `IplHostedRetainedEntityInsertionMixin.java`, `SableRapierPipelineOwnershipGuardMixin.java`, `transit/SableRehomeOps.java`.

### 6. Entity chunk lifecycle: предотвращать downgrade ДО callbacks

Раньше восстановление ENTITY_TICKING после downgrade было запоздалым: tracking-end callbacks уже успевали убрать contraptions из клиентского/аддонного состояния.

Теперь downgrade отменяется в HEAD для точно удерживаемых plot entity sections. Периодическая server reconciliation поднимает нужные parent entity sections для сохранённых actors, не создавая parent block chunks. Убран старый unload guard, который маскировал проблему запретом `shouldBeSaved`.

На клиенте добавлены аналогичный stopTicking guard, reconciliation entity-before-plot packet ordering и cleanup при disconnect. Это не зависит от наличия Flywheel. Последние entity memberships не должны удерживать прежний сервер после выхода.

Файлы: `dim/IplParentPlotEntityTicking.java`, `client/IplClientPlotEntityTicking.java`, `mixin/IplParentPlotEntityTickingMixin.java`, `IplPlotChunkTickingGuardMixin.java`, `client/IplClientPlotTickingGuardMixin.java`, `client/SableAltContainerTickMixin.java`, server heartbeat/stop и client disconnect cleanup. Удалён `IplPlotEntityUnloadGuardMixin`.

## Что реально проверено

Логи лежат в `checks/` архива. Это не запись игрового playtest.

| Проверка | Результат |
|---|---|
| Java/JAR 0.6.0 | BUILD SUCCESSFUL, 37 tasks; 31 настоящая target-трансформация, packaged JAR отдельно проверен по config/classes/version/native payload и CRC |
| Настоящий Sponge Mixin + MixinExtras на dependency bytecode | Vanilla, Sodium, Flywheel/Sable merged handlers, Entity/managers, packet listener, Rapier pipeline и контейнеры; плюс 2752 ownership/provenance assertions |
| Boundary fixtures | 34 callback/spatial/read-scope + 10 moving network + 8 visual context + 11 height profile |
| Исходный FragileBlockCallback из предоставленного Sable | 7 assertions на fixture мирах, без изменения upstream callback |
| Entity migration | 17 identity/bounds/rollback assertions на fixture managers |
| Visual liveness | 16 identity/state/removal assertions на fixture chunks |
| BER gate | 9 assertions, включая единственный off-screen/global BE при пустом compiled списке |
| Старые регрессии | 14 environment + dedicated-server linkage; 40 GL bind; 27 render cache; 7011 culling/section; 5523 portal sweep geometry |
| Реальный Linux JNI | ABI smoke и actual contact simulation: обычная сцена и Atlas image в другом chart, по 2052 callbacks с каждой стороны, max impact ~8.34 |
| JNI declarations | Все 53 native signatures сопоставлены с фактическим Sable 2.0.6 JAR |

Нативный contact-test доказывает прохождение callback при настоящем Rapier шаге, но сам по себе не доказывает разрушение листьев в Minecraft. Fixture со штатным FragileBlockCallback проверяет выбор мира/правила разрушения, но не заменяет GameTest.

### Явно НЕ закрыто игровым доказательством

1. Полноценный клиентский playtest вашей сборки модов здесь не выполнен. Не утверждаю, что шесть симптомов гарантированно исчезли во всех сочетаниях, или что shader/portal visuals «идеальны».
2. В upstream FragileBlockCallback есть намеренный запрет ломать LeavesBlock с `PERSISTENT=true` (обычно поставленные игроком листья), а threshold равен 4.0. Эти правила не менялись. Естественные листья и прочие FRAGILE материалы проверены в callback fixture; свежий игровой удар всё ещё нужен.
3. `Invalid packet` Staff: в предоставленном PhysicsStaffActionPacket такая строка используется, когда physics staff нет ни в main hand, ни в off hand при проверке. Это не доказательство причины вашего конкретного disconnect. Проверку не ослаблял, общий disconnect не подавлял. Для безопасного исправления нужны client/server logs и последовательность действий с предметом/сменой рук.
4. Известный отдельный portal-detector fixture по-прежнему падает на `26cm protrusion releases while most of cube still overlaps`. Это не скрыто и не объявлено исправленным; алгоритм портального release здесь не переписывался. Лог приложен. Поэтому формулировка «все тесты зелёные» была бы неверной.
5. Реальный Windows runtime в этой Linux-среде не запускался. Windows native остался из предыдущего build; новый native ABI в 0.6.0 не вводился.

## Короткий игровой checklist

На копии мира, сначала без порталов, затем отдельно с порталом:

- Свежая assembly/disassembly плота с glue, вращающимся bearing/press, контрапцией и верёвкой **между двумя ships без привязки к земле**. Повторить несколько раз; не нажимать F3+A для проверки ghost cleanup.
- Удалить последний BE/последний блок, заменить BE новым в той же позиции, покинуть render distance и вернуться. Проверить GPU instances и звук/частицы.
- Сжать/увеличить ship через Pocket Sized: внешний размер, outline/reach, опора игрока, остальные entities, Flywheel и обычная геометрия должны совпадать. Повторить после reconnect.
- Ударить естественные листья и другие FRAGILE материалы с обеих сторон: ship в terrain и fragile blocks на ship в terrain. Поставленные persistent leaves тестировать отдельно с учётом upstream правила.
- В Nether/End/мод-измерении: свежая assembly, установка/ломание/замена BE, save/reload. Старый подозрительный ship проверять отдельно — не считать успех на новом автоматическим восстановлением старого.
- Contraption на hosted ship: физическое столкновение, движение акторов, reconnect/restart, assembly/disassembly. Проверить, что она не только рисуется, но и остаётся в physics/entity registry.
- После этого portal view, смена parent, straddle, scale+portal; shader packs/Iris/Veil — отдельный visual playtest.

## Linux: что было возможно и что сделано

Linux support не выдуман с нуля: upstream Sable уже содержит OS/arch выбор Linux x86_64 `.so`. Java-мод собирается на Linux JDK 21 через включённый Gradle wrapper; Windows для Java-сборки не требуется.

Но обычная upstream `.so` для нашего изменённого Atlas engine не подходит. В предыдущем этапе были собраны natives из **изменённого vendored Rust Atlas fork**: отдельная Linux `.so` и отдельная Windows x86_64 DLL (cross-build MinGW). Это не конверсия DLL в SO и не stock бинарник, взятый вместо нашего fork. В 0.6.0 Rust/natives не пересобирались: используются те же ABI 2 библиотеки, повторно проверенные через Linux JNI и signatures.

В проекте: `natives/build-linux.sh`, `natives/build-windows.ps1`, vendored sources и Cargo.lock. `processResources` помещает обе OS-библиотеки в `natives_ipl/` JAR. Поддержка здесь — Linux/Windows **x86_64**, не обещание Mac/ARM.

Java package без запуска игры:

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assemble jarJar --no-daemon --max-workers=1
```

Полный `build` может дополнительно запрашивать игровые assets/проверки NeoGradle. Первый NeoForm проход требует больше RAM/сети, чем повторная Java-компиляция. Для native rebuild нужен закреплённый Rust toolchain из `natives/rust-toolchain.toml`; не заменять engine stock Rapier.

Команды воспроизведения из source root:

```bash
./gradlew assemble jarJar writeHostedApi2Classpath -I tests/hosted-api2/write-classpath.gradle
bash tests/hosted-api2/run-transform.sh
SABLE_SOURCES=/path/to/sable-main bash tests/hosted-api2/run.sh
bash tests/entity/run.sh
bash tests/visual-liveness/run.sh
bash tests/render/run.sh
bash tests/native/run-abi.sh "$PWD/sable_rapier_x86_64_linux.so"
bash tests/native/run-contact.sh "$PWD/sable_rapier_x86_64_linux.so"
```

Fixture runners требуют JDK 21; часть использует JOML/annotations из Gradle cache (`GRADLE_USER_HOME` или `JOML_JAR`, `ANNOTATIONS_JAR`). Optional SABLE_SOURCES нужен только для проверки именно исходного upstream FragileBlockCallback. Дополнительные старые тесты — `tests/compat`, `tests/gl`, `tests/portal`; исторические pass1/pass2 notes в docs не являются отчётом игрового тестирования 0.6.0.
