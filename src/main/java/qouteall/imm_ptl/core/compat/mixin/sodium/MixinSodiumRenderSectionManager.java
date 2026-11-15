package qouteall.imm_ptl.core.compat.mixin.sodium;

import ipl.sable.render.IplForeignPerspective;
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
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinSodiumRenderSectionManager implements IESodiumRenderSectionManager {
    @Shadow
    @Final
    @Mutable
    private int renderDistance;
    
    @Shadow
    private @NotNull SortedRenderLists renderLists;
    
    /** World pass whose camera the current section graph was built for. */
    @Unique
    private long ip_graphPassId = -1;
    
    @Override
    public void ip_swapContext(SodiumRenderingContext context) {
        Validate.isTrue(context.renderDistance != 0, "Render distance cannot be 0");
        Validate.isTrue(context.renderLists != null);
        
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
        // a graph for another mod's camera belongs to no pass
        ip_graphPassId = IplForeignPerspective.isRendering() ? -1 : WorldRenderInfo.getCurrentPassId();
    }
    
    /**
     * Sodium culls entities and block entities by the section graph. Section visibility is
     * stored in the sections, which every pass of this level shares, so it is only valid for the
     * pass that built the graph and only until another pass rebuilds it. Every other pass (an
     * enclosing pass after a same-level portal, a root pass that reused an older graph) draws
     * without this culling instead of blinking.
     */
    @Inject(method = "isSectionVisible", at = @At("HEAD"), cancellable = true)
    private void onIsSectionVisible(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (ip_graphPassId != WorldRenderInfo.getCurrentPassId()) {
            cir.setReturnValue(true);
        }
    }
}
