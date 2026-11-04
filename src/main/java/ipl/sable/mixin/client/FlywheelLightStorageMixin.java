package ipl.sable.mixin.client;

import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.neoforge.compatibility.flywheel.SableFlywheelLightStorage;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Свет саб-уровней во Flywheel собирается по СВОЕМУ уровню хранилища, а не по уровню игрока.
 *
 * <p>Движок Flywheel — и {@link SableFlywheelLightStorage} — у каждого
 * клиентского уровня свой, но Sable при сборе секций берёт {@code Minecraft.getInstance().level}
 * и {@code getContainingClient(SectionPos)}, то есть уровень, где сейчас стоит игрок. Кадровый план
 * выполняется в рабочих потоках Flywheel, а IP переключает текущий уровень и при отрисовке
 * порталов, и при входе в измерение. С чужим контейнером секции корабля уходят в статическую
 * сцену (id 0) с глобальным адресом, шейдер не находит для них света, и части, которые рисует
 * Flywheel, на кадр чернеют у всех кораблей сразу.
 */
@Mixin(value = SableFlywheelLightStorage.class, remap = false)
public abstract class FlywheelLightStorageMixin {

    @Redirect(method = {"updateLightSections", "removeUnusedSections"}, at = @At(value = "FIELD",
        target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;",
        opcode = Opcodes.GETFIELD), require = 2)
    private ClientLevel ipl$ownLevel(Minecraft minecraft) {
        return ((LightStorage) (Object) this).level() instanceof ClientLevel own ? own : minecraft.level;
    }

    @Redirect(method = "indexForSection", at = @At(value = "INVOKE",
        target = "Ldev/ryanhcode/sable/ActiveSableCompanion;getContainingClient(Lnet/minecraft/core/SectionPos;)Ldev/ryanhcode/sable/sublevel/ClientSubLevel;"), require = 1)
    private ClientSubLevel ipl$containingInOwnLevel(ActiveSableCompanion helper, SectionPos pos) {
        if (((LightStorage) (Object) this).level() instanceof Level own) {
            return helper.getContaining(own, pos) instanceof ClientSubLevel subLevel ? subLevel : null;
        }
        return helper.getContainingClient(pos);
    }
}
