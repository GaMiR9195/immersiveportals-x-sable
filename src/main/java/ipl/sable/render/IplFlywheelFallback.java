package ipl.sable.render;

import dev.engine_room.flywheel.impl.visualization.VisualManagerImpl;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.neoforge.mixinhelper.compatibility.flywheel.SubLevelEmbedding;
import dev.ryanhcode.sable.neoforge.mixinterface.compatibility.flywheel.BlockEntityStorageExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplParentDimSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Flywheel-визуалы (шестерни, валы Create и т.п.) саб-уровней, пересекающих портал.
 *
 * <p><b>Проблема.</b> Flywheel рисует визуалы саб-уровня одним «встроенным окружением»
 * ({@code EmbeddedEnvironment}) с НАСТОЯЩЕЙ позой и только в проходе своего уровня. Отсюда:
 * (1) у пересекающего портал корабля шестерни не режутся плоскостью портала (рисуются
 * целиком, в том числе «за» порталом); (2) в проекции (кусок, вышедший из другого портала)
 * их нет вовсе — Flywheel о проекциях не знает; (3) у скрытого на время перехода «преемника»
 * визуалы продолжают висеть на месте.
 *
 * <p><b>Решение.</b> Пока саб-уровень пересекает портал, его окружение Flywheel схлопывается
 * (нулевая матрица в {@code EmbeddedEnvironment.flush} — работает и для instancing, и для
 * indirect), а сами BE рисуются обычным BER-путём внутри области отсечения саб-уровня — с
 * плоскостями куска и, для проекции, с отображённой матрицей. На время этих BER
 * {@code VisualizationManagerImpl.supportsVisualization} возвращает false (см.
 * {@link IplSubLevelBlockEntityPass#isForcingVanillaRender()}), иначе Create пропустил бы
 * отрисовку, считая, что её сделает Flywheel. Когда корабль уходит от портала, всё
 * возвращается к обычному инстансингу.
 *
 * <p>Класс загружается только при наличии Flywheel ({@link IplSubLevelBlockEntityPass}).
 */
public final class IplFlywheelFallback {

    private IplFlywheelFallback() {}

    /** Окружения Flywheel, которые в текущем проходе нужно схлопнуть. Только render thread. */
    private static final Set<Object> HIDDEN = Collections.newSetFromMap(new IdentityHashMap<>());
    /**
     * Последняя известная «встройка» каждого саб-уровня. Нужна проекции в ЧУЖОМ измерении:
     * там {@code VisualizationManagerImpl.get(уровень источника)} вернёт null (Flywheel
     * поддерживает только текущий {@code Minecraft.level}), а список BE — живой объект Sable.
     */
    private static final Map<ClientSubLevel, SubLevelEmbedding> EMBEDDINGS = new WeakHashMap<>();

    /** HEAD {@code EnvironmentStorage.flush()}: Flywheel текущего уровня начинает кадр. */
    public static void beginEnvironmentFlush() {
        HIDDEN.clear();
        ClientLevel level = Minecraft.getInstance().level;
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(level);
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (manager == null || container == null) return;
        // Хранилище BE Flywheel реализует расширение Sable (его compat-миксин), как и в Simulated.
        BlockEntityStorageExtension storage =
            (BlockEntityStorageExtension) ((VisualManagerImpl<?, ?>) manager.blockEntities()).getStorage();
        for (ClientSubLevel sub : container.getAllSubLevels()) {
            SubLevelEmbedding info = storage.sable$getEmbeddingInfo(sub);
            if (info == null) {
                EMBEDDINGS.remove(sub);
                continue;
            }
            EMBEDDINGS.put(sub, info);
            if (IplParentDimSync.isSuppressedSuccessor(sub)
                || IplClipPortalFinder.findStraddlingPortalPlane(sub) != null) {
                HIDDEN.add(info.embedding());
            }
        }
    }

    public static boolean isHiddenEnvironment(Object environment) {
        return HIDDEN.contains(environment);
    }

    /** BE саб-уровня, которые сейчас визуализирует Flywheel (копия; пусто, если нет). */
    public static List<BlockEntity> visualizedBlockEntities(ClientSubLevel sub) {
        SubLevelEmbedding info = EMBEDDINGS.get(sub);
        return info == null || info.blockEntities().isEmpty() ? List.of() : new ArrayList<>(info.blockEntities());
    }

    public static boolean hasVisualized(ClientSubLevel sub) {
        SubLevelEmbedding info = EMBEDDINGS.get(sub);
        return info != null && !info.blockEntities().isEmpty();
    }
}
