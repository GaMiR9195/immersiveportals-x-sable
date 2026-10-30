package ipl.sable.mixin.client;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/**
 * Доступ к «глобальным» блок-сущностям секции ({@code shouldRenderOffScreen}: верёвки,
 * лебёдки, маяки…).
 *
 * <p>Зачем: такие BE НЕ попадают в {@code CompiledSection.getRenderableBlockEntities()},
 * их рисует только общий цикл {@code LevelRenderer.globalBlockEntities}. Для саб-уровней это
 * ломалось в двух местах: (1) под Sodium все секции саб-уровней компилирует ОДИН ванильный
 * диспетчер Sable, привязанный к первому {@code LevelRenderer}, поэтому верёвки всех измерений
 * попадали в набор одного рендерера — «призраки» в чужом измерении и пропажа в своём;
 * (2) проекции через портал вообще не видели глобальных BE. Теперь
 * {@link ipl.sable.render.IplSubLevelBlockEntityPass} рисует их сам — в проходе своего
 * саб-уровня, с его плоскостями отсечения и (для проекций) с отображённой матрицей.
 * Набор синхронизирован ванилью ({@code synchronized (globalBlockEntities)}) — читаем так же.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public interface IplRenderSectionGlobalsAccessor {

    @Accessor("globalBlockEntities")
    Set<BlockEntity> ipl$getGlobalBlockEntities();
}
