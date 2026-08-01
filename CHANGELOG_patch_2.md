# ipl_patch_2 — sodium eyespace + суб-тиковый проход портала

База: `ipl_patch_1` (rapier-файлы включены без изменений, чтобы патч был кумулятивным).
Ничего не компилировалось, `jarJar` не трогался.

## Файлы

```
src/src/main/java/ipl/sable/transit/PortalCrossingDetector.java      изменён
src/src/main/java/ipl/sable/transit/SableTransitController.java      изменён
src/src/main/java/ipl/sable/client/IplProjectionRenderSequence.java  НОВЫЙ
src/src/main/java/ipl/sable/client/IplStraddleRenderCache.java       изменён (поверх patch_1)
src/src/main/java/ipl/sable/client/IplClientVisualTransitLatch.java  изменён (поверх patch_1)
src/src/main/java/ipl/sable/mixin/client/IplHostedSubLevelRenderMixin.java        изменён
src/src/main/java/ipl/sable/mixin/client/IplHostedSubLevelRenderSodiumMixin.java  изменён
src/src/main/resources/ipl_sable.mixins.json                          изменён
rapier/**                                                             из patch_1, без изменений
```

**УДАЛИТЬ из репозитория:** `src/main/java/ipl/sable/mixin/client/SableSourceClipSodiumMixin.java`
(строка `"client.SableSourceClipSodiumMixin"` уже убрана из `ipl_sable.mixins.json`).

---

## 1. Sodium-путь eyespace

### 1.1 Мёртвый sodium-клип-миксин
`SableSourceClipSodiumMixin` целится в `dev.ryanhcode.sable.sublevel.render.sodium.SubLevelRenderSectionManager`.
В Sable 2.0.3 такого класса нет — миксин молча не применялся (`require = 0`), т.е. «содиумный путь клипа»
существовал только на бумаге.

Реальный путь: `ReachAroundSubLevelRenderDispatcher extends VanillaSubLevelRenderDispatcher`,
`createRenderData` возвращает `VanillaChunkedSubLevelRenderData` / `VanillaSingleSubLevelRenderData`.
Значит `SableSourceClipMixin` (цель — `VanillaChunkedSubLevelRenderData.renderChunkedSubLevel`)
**уже работает под Sodium**. Мёртвый файл удалён, чтобы никто больше не «чинил» несуществующий путь.

### 1.2 Одноблочные саб-левелы пропадали в проекциях (обе backend-ветки)
`SubLevelRenderDispatcher.renderAfterSections` сливает **общую** очередь `singleBlockLayers` и очищает её.
Старый код звал слив сначала для `hosted`, потом отдельно для каждой проекции — вторые и последующие
вызовы всегда шли по уже пустой очереди. Итог: одноблочные саб-левелы (концы канатов, одноблочные
контрапции) вообще не рисовались в destination-проекции — дыра в eyespace, независимая от шейдеров.

Новый `IplProjectionRenderSequence` — одна `Iterable<ClientSubLevel>`: сначала hosted, затем проекции,
причём `IplStraddleRenderState` включается внутри `next()` ровно на тот элемент, который диспетчер
в этот момент запекает в буфер (`renderSingleBlock` вызывается сразу после `next()`, и последовательность
перебирается заново на каждый слой). Один вызов `renderAfterSections` — одна очередь — всё нарисовано.
`disarm()` в `finally` на случай прерванной итерации.

### 1.3 Sodium: неверная точка отсчёта у одноблочного слива
`ipl$renderHostedSingleBlocks` брал `gameRenderer.getMainCamera().getPosition()`. Внутри IP-прохода
портала это камера игрока, а не камера прохода; у `drawChunkLayer` уже есть `x/y/z` этого прохода.
Теперь используются `x/y/z` — сдвиг одноблочной геометрии в портальных проходах убран.

### 1.4 Кадровый счётчик (пере-проверка patch_1)
`IplStraddleRenderCache.begin/end` дёргается из `IplHostedSubLevelRenderMixin` на `LevelRenderer.renderLevel`
HEAD/RETURN, и этот миксин под Sodium остаётся живым — значит `frameId()` и «одна выборка позы на кадр»
в `IplClientVisualTransitLatch` работают и на Sodium. Но `end()` снимал проход только если верхушка стека
совпала с уровнем; иначе Pass оставался в стеке навсегда, кэш отвечал из мёртвого прохода, а счётчик кадров
вставал — то есть возвращался ровно тот баг, который patch_1 чинил. Теперь при несовпадении удаляется
ближайший проход этого уровня (`descendingIterator`).

### 1.5 Утечка карт выборок
`LAST_RENDER_POSES` / `LAST_SAMPLE_FRAMES` росли вечно (корабль ушёл из зоны рендера — записи остались).
Добавлен `ipl$pruneSamples` с окном `SAMPLE_RETENTION_FRAMES = 600`.

---

## 2. Проход портала быстрее тика (петля верх/низ)

Было: детект — раз в серверный тик (`ServerSubLevelContainer.tick` TAIL), **один транзит на корабль
за тик**, а после транзита `forgetTrail` выбрасывал остаток сегмента. Быстрое тело успевало пересечь
портал и улететь дальше в том же сегменте, но «съедался» только первый переход, а хвост движения
считался в старой системе координат — визуально тело проходит СКВОЗЬ портал и уезжает в грунт.

### 2.1 Время пересечения стало данными
`CrossingState` получил компонент `entryTime` — доля текущего сегмента, на которой произошёл вход
source→destination (берётся из `recent.towardTime()`, иначе из объединённого свипа).

### 2.2 `rebaseTrailThroughPortal` вместо `forgetTrail`
После выполненного транзита след не выбрасывается, а переносится в destination-кадр:
старт = точка пересечения (`interpolate(start, end, entryTime)`, позиция — lerp, ориентация — slerp),
прогнанная через изометрию портала (`IplStraddlePoseMap.StraddleMapping.mapPose`), конец = текущая
`logicalPose`. История до шва отбрасывается (`hasOlder = false`) — её нельзя сшить с новыми позами.
То же самое делается для жёстких «мейтов».

### 2.3 Цепочка переходов внутри одного тика
`SableTransitController` после успешного транзита крутит `ipl$findCompletedCrossing` по остатку сегмента
в НОВОМ родителе и выполняет следующие переходы: `MAX_CHAINED_CROSSINGS_PER_TICK = 16` за тик, остаток
продолжится следующим тиком. Петля «верхний портал → нижний портал» может отрабатывать сколько угодно
тиков подряд — ограничение только на работу внутри одного тика, а не на общее число проходов.
При упоре в лимит — `WARN [IPL-TRANSIT] ... reached the N crossings/tick cap`.

`ipl$findCompletedCrossing` повторяет ВСЕ ворота основного скана (канонная входная грань, свой
anchor-портал, парность совпадающих граней, направленный свип, `startedBeforePortalPlane`,
`sweptIntersectsPortalAperture`) — это не упрощённый дублирующий детектор.

### 2.4 Предварительное открытие шва (почему тело уходило в землю)
Детект — свип и не промахивается, а вот сам шов (image-коллайдер + clip-регионы) открывался только
после уже наблюдённого straddle. Между двумя наблюдениями идут физические сабстепы, в которых за
плоскостью портала стоит целая source-сторона: тело на скорости бьётся в неё и уезжает под грунт.

`PortalCrossingDetector.willEnterAperture(...)` продолжает текущий сегмент вперёд на
`PREARM_LOOKAHEAD_SEGMENTS = 1.0` и проверяет тот же конечный проём тем же свипом. Если попадание
направленное (source→destination) — сессия открывается на сегмент раньше. Для тела, целиком стоящего
перед плоскостью, это физически нейтрально: clip-регионы оставляют только дальнюю половину, на ближней
стороне ничего не добавляется. Ownership совпадающих граней это тоже подтверждает — направленный
lookahead-свип и есть доказательство владения гранью (новый аргумент `predictedEntry`).

---

## 3. Вход под углом

Проверено, дубля логики нет и заплаток поверх бага нет:

- Допуск в проём (`sweepSegment`) — консервативный **AABB**-саппорт конечных поз (сумма Минковского),
  клиппинг Лианга—Барски по полосе плоскости и по конечному прямоугольнику. Косой вход при этом
  расширяет проём ровно на проекцию повёрнутого куба — пропустить нельзя, можно только чуть перепустить.
- Фаза и выбор грани (`sample`, `overlapsAperture`) — точный **OBB**-саппорт. Это разные вопросы,
  и они намеренно не смешиваются.
- Гистерезис `PORTAL_HYSTERESIS_DEPTH = 0.1` — только глубина фазы, в направление грани не входит.
- `entryTime` берётся из того же самого свипа, что и допуск, поэтому re-base сшивается ровно по той
  точке, которую видел детектор, — «раздвоения» траектории на косом входе не появляется.
- Раздвоение картинки на косом входе имело другую причину — п.1.2 (проекции рисовались не полностью)
  и п.1.3 (сдвиг одноблочных). Оба закрыты по источнику, а не маской.

---

## 4. Прочие логические правки

- `ipl$ownsCoincidentFace` — явный аргумент `predictedEntry` вместо неявного «повезло с состоянием».
- Чейн-переходы не ломают инварианты: `executeHostedTransit` сам гасит все сессии корабля
  (`sessionKeysFor`), `IplGrabChain.onBodyTransit` и `IplRopePortalSeam.onShipTransit` вызываются
  на каждый переход цепочки, включая жёсткие мейты.
- Оставлено как есть (осознанно): `IplPortalRimManager` — заглушка, пока нет нативной реализации;
  трогать её без natives смысла нет.

## Что НЕ сделано

- Не компилировалось.
- `IplParentDimSync` и `SubLevelClipUniformPatcher` не менялись — по итогам чтения их логика под
  Sodium совпадает с ванильной (клип пишется в `IplGlUseProgramProbeMixin` на bind программы,
  слот 0 `iportal_ClippingEquation`, слот 1 `ipl_subLevelClipEquation`), отдельная содиумная ветка
  им не нужна.
- Rapier-исходники из `ipl_patch_1` не пересматривались (в архив включены как есть).
