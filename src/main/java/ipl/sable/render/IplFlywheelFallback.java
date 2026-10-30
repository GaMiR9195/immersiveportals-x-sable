package ipl.sable.render;

import dev.engine_room.flywheel.impl.visualization.VisualManagerImpl;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.neoforge.mixinhelper.compatibility.flywheel.SubLevelEmbedding;
import dev.ryanhcode.sable.neoforge.mixinterface.compatibility.flywheel.BlockEntityStorageExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger("ipl-flywheel-fallback");

    private IplFlywheelFallback() {}

    /** Окружения Flywheel, которые в текущем проходе нужно схлопнуть. Только render thread. */
    private static final Set<Object> HIDDEN = Collections.newSetFromMap(new IdentityHashMap<>());
    /**
     * Последняя известная «встройка» каждого саб-уровня. Нужна проекции в ЧУЖОМ измерении:
     * там {@code VisualizationManagerImpl.get(уровень источника)} вернёт null (Flywheel
     * поддерживает только текущий {@code Minecraft.level}), а список BE — живой объект Sable.
     */
    private static final Map<ClientSubLevel, SubLevelEmbedding> EMBEDDINGS = new WeakHashMap<>();
    private static boolean loggedFailure;

    /** HEAD {@code EnvironmentStorage.flush()}: Flywheel текущего уровня начинает кадр. */
    public static void beginEnvironmentFlush() {
        HIDDEN.clear();
        try {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) return;
            VisualizationManagerImpl manager = VisualizationManagerImpl.get(level);
            if (manager == null) return;
            Object storage = ((VisualManagerImpl<?, ?>) manager.blockEntities()).getStorage();
            if (!(storage instanceof BlockEntityStorageExtension extension)) return;
            ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) return;
            for (ClientSubLevel sub : container.getAllSubLevels()) {
                SubLevelEmbedding info = extension.sable$getEmbeddingInfo(sub);
                if (info == null) {
                    EMBEDDINGS.remove(sub);
                    continue;
                }
                EMBEDDINGS.put(sub, info);
                if (ipl.sable.client.IplParentDimSync.isSuppressedSuccessor(sub)
                    || SourceClipPortalFinder.findStraddlingPortalPlane(sub) != null) {
                    HIDDEN.add(info.embedding());
                }
            }
        } catch (RuntimeException e) {
            // Внутренности Flywheel/Sable могли измениться — тогда просто остаёмся на инстансинге.
            HIDDEN.clear();
            if (!loggedFailure) {
                loggedFailure = true;
                LOG.warn("Flywheel fallback disabled for this session", e);
            }
        }
    }

    public static boolean isHiddenEnvironment(Object environment) {
        return environment != null && !HIDDEN.isEmpty() && HIDDEN.contains(environment);
    }

    /** BE саб-уровня, которые сейчас визуализирует Flywheel (копия; пусто, если нет). */
    public static List<BlockEntity> visualizedBlockEntities(ClientSubLevel sub) {
        SubLevelEmbedding info = EMBEDDINGS.get(sub);
        if (info == null) return List.of();
        try {
            List<BlockEntity> list = info.blockEntities();
            return list.isEmpty() ? List.of() : new ArrayList<>(list);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    public static boolean hasVisualized(ClientSubLevel sub) {
        SubLevelEmbedding info = EMBEDDINGS.get(sub);
        return info != null && !info.blockEntities().isEmpty();
    }
}
