package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IESodiumRenderSectionManager;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumRenderingContext;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.ArrayDeque;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinSodiumRenderSectionManager implements IESodiumRenderSectionManager {
    @Shadow
    @Final
    @Mutable
    private int renderDistance;
    
    @Shadow
    private @NotNull SortedRenderLists renderLists;
    
    /**
     * Стек контекстов IP, сейчас «вставленных» в этот менеджер (пусто — корневой проход).
     * {@link #ip_swapContext} вызывается парно одним и тем же объектом: при входе в проход
     * портала и при возврате. Только render thread.
     */
    @Unique
    private ArrayDeque<Object> ip_contextStack;
    
    /**
     * Контекст, для чьей камеры построен текущий граф видимости. {@code null} — корневой проход.
     */
    @Unique
    private Object ip_graphOwner;
    
    @Unique
    private Object ip_currentContext() {
        return ip_contextStack == null ? null : ip_contextStack.peekLast();
    }
    
    @Override
    public void ip_swapContext(SodiumRenderingContext context) {
        Validate.isTrue(context.renderDistance != 0, "Render distance cannot be 0");
        Validate.isTrue(context.renderLists != null);
        
        // Отслеживаем, чей сейчас проход: тот же объект второй раз подряд — это возврат.
        if (ip_contextStack == null) ip_contextStack = new ArrayDeque<>();
        if (ip_contextStack.peekLast() == context) {
            ip_contextStack.pollLast();
        }
        else {
            // Защита от разбалансировки (исключение посреди прохода портала).
            if (ip_contextStack.size() > 64) ip_contextStack.clear();
            ip_contextStack.addLast(context);
        }
        
        SortedRenderLists renderListsTmp = renderLists;
        renderLists = context.renderLists;
        context.renderLists = renderListsTmp;
        
        int renderDistanceTmp = renderDistance;
        renderDistance = context.renderDistance;
        context.renderDistance = renderDistanceTmp;
    }
    
    @Inject(
        method = "update(Lnet/minecraft/client/Camera;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Z)V",
        at = @At("HEAD")
    )
    private void ip_onGraphUpdate(Camera camera, Viewport viewport, boolean spectator, CallbackInfo ci) {
        ip_graphOwner = ip_currentContext();
    }
    
    /**
     * Section visibility is wrong after a portal was rendered, so this optimization (only used to
     * cull entities) is skipped then.
     *
     * <p>The visibility ({@code lastVisibleFrame == lastUpdatedFrame}) is shared by all passes,
     * while IP swaps the render lists per context. A same-dimension portal pass always rebuilds
     * the graph ({@code scheduleTerrainUpdate} on entry); the root pass of the next frame only
     * when the camera moved. A graph built for another context (a portal's camera) would make
     * entities near a portal blink, so entity culling is skipped unless the graph belongs to
     * the current context.
     */
    @Inject(method = "isSectionVisible", at = @At("HEAD"), cancellable = true)
    private void onIsSectionVisible(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (RenderStates.portalsRenderedThisFrame != 0 || ip_graphOwner != ip_currentContext()) {
            cir.setReturnValue(true);
        }
    }
    
}
