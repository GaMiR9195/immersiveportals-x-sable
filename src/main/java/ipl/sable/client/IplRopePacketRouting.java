package ipl.sable.client;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.ClientRopeStrand;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.UUID;

/**
 * Пакеты верёвок Simulated — в тот клиентский уровень, где живёт держатель верёвки.
 *
 * <p><b>Проблема.</b> Обработчики {@code ClientboundRopeDataPacket}/{@code ClientboundRopeStoppedPacket}
 * ищут BE держателя в {@code player.level()} — в измерении, где сейчас стоит игрок. Сервер же
 * шлёт их всем, кто отслеживает саб-уровень (Sable отдаёт этот список по всему серверу, а наш
 * межизмеренческий трекинг держит в нём зрителей через портал). Как только игрок перешёл в другое
 * измерение, данные верёвок корабля, оставшегося по ту сторону (или видимого проекцией), уходили
 * «в пустоту»: верёвка застывала в старом положении и срезалась плоскостью проекции — на другой
 * стороне портала её не было видно. Переключение мира IP тут не помогает: оно не трогает
 * {@code player.level()}.
 *
 * <p><b>Решение.</b> Если в текущем уровне игрока нет подходящего держателя, ищем его в остальных
 * загруженных IP клиентских уровнях. Участки (plot) Sable уникальны в пределах сервера, а для
 * данных дополнительно сверяется UUID верёвки — так что выбор однозначен. Обработчики идут в
 * главном потоке клиента (NeoForge по умолчанию), обход уровней безопасен.
 */
public final class IplRopePacketRouting {

    private IplRopePacketRouting() {}

    /**
     * Держатель в ДРУГОМ клиентском уровне, которому адресован пакет, или null — тогда
     * оригинальный обработчик (уровень игрока) прав.
     *
     * @param ropeId UUID верёвки из пакета данных; null для пакета остановки.
     */
    @Nullable
    public static RopeStrandHolderBehavior foreignHolder(@Nullable Player player, @Nullable BlockPos ownerPos, @Nullable UUID ropeId) {
        if (player == null || ownerPos == null) return null;
        Level current = player.level();
        RopeStrandHolderBehavior here = holderAt(current, ownerPos);
        if (here != null && matches(here, ropeId)) return null;
        if (!ClientWorldLoader.getIsInitialized()) return null;

        RopeStrandHolderBehavior fallback = null;
        for (ClientLevel level : ClientWorldLoader.getClientWorlds()) {
            if (level == current) continue;
            RopeStrandHolderBehavior other = holderAt(level, ownerPos);
            if (other == null) continue;
            ClientRopeStrand strand = other.getClientStrand();
            if (ropeId != null && strand != null && ropeId.equals(strand.getUuid())) return other;
            // Без точного совпадения подменяем уровень только если у игрока держателя нет вовсе.
            if (fallback == null && here == null && (ropeId == null || strand == null)) fallback = other;
        }
        return fallback;
    }

    @Nullable
    private static RopeStrandHolderBehavior holderAt(@Nullable Level level, BlockPos pos) {
        // Незагруженный чанк клиента — пустой: getBlockEntity вернёт null.
        if (level == null) return null;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof SmartBlockEntity smart)) return null;
        return smart.getBehaviour(RopeStrandHolderBehavior.TYPE);
    }

    private static boolean matches(RopeStrandHolderBehavior holder, @Nullable UUID ropeId) {
        if (ropeId == null) return true;
        ClientRopeStrand strand = holder.getClientStrand();
        return strand == null || ropeId.equals(strand.getUuid());
    }
}
