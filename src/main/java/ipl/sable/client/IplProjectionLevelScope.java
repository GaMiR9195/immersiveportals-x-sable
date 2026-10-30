package ipl.sable.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * BE проекции рисуются «от имени» уровня, которому принадлежит корабль.
 *
 * <p><b>Проблема.</b> Проекция корабля из измерения A рисуется в проходе измерения B, и в этот
 * момент {@code Minecraft.level} = B. Многие BER (и сам Sable) ищут «свой» мир именно через
 * {@code Minecraft.level}, а не через {@code be.getLevel()}: пружина Simulated берёт
 * партнёрский саб-уровень из {@code SubLevelContainer.getContainer(Minecraft.level)}, Sable
 * масштабирует свет BE через {@code getContainingClient(pos)} — тоже по {@code Minecraft.level}.
 * В проходе B партнёр не находится, и пружина строится до точки в координатах участка
 * (миллионы блоков в стороне) — на другой стороне портала она «исчезала».
 *
 * <p><b>Решение.</b> IP в своих проходах порталов делает то же самое: на время отрисовки чужого
 * мира подменяет {@code Minecraft.level}. Здесь подмена ещё уже — только на время вызовов BER
 * одной межизмеренческой проекции; сброс буфера ({@code endBatch}) и всё остальное в проходе
 * идут уже с исходным уровнем. Для проекций внутри одного измерения ничего не меняется.
 */
public final class IplProjectionLevelScope {

    private IplProjectionLevelScope() {}

    public static void runInOwnerLevel(ClientSubLevel sub, Runnable draw) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel owner = sub == null ? null : sub.getLevel();
        ClientLevel current = minecraft.level;
        if (owner == null || current == null || owner == current) {
            draw.run();
            return;
        }
        minecraft.level = owner;
        try {
            draw.run();
        } finally {
            minecraft.level = current;
        }
    }
}
