package foundry.veil.fabric.mixin.client.perspective.sodium;

import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer;
import foundry.veil.fabric.ext.RenderSectionExtension;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.RenderSectionVisitor;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.VisibilityEncoding;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.collections.WriteQueue;
import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller.isWithinNearbySectionFrustum;

@Mixin(OcclusionCuller.class)
public abstract class OcclusionCullerMixin {

    @Shadow(remap = false)
    protected abstract RenderSection getRenderSection(int x, int y, int z);

    @Shadow(remap = false)
    private static void visitNeighbors(WriteQueue<RenderSection> queue, RenderSection section, int outgoing, int frame) {
    }

    @Inject(method = "visitNode", at = @At("HEAD"), cancellable = true, remap = false)
    private static void visitNode(WriteQueue<RenderSection> queue, @NotNull RenderSection render, int incoming, int frame, CallbackInfo ci) {
        if (!VeilLevelPerspectiveRenderer.isRenderingPerspective()) {
            return;
        }

        ci.cancel();
        RenderSectionExtension ext = (RenderSectionExtension) render;
        if (ext.veil$hasNotRendered()) {
            ext.veil$markRendered();
            render.setIncomingDirections(0);
            queue.enqueue(render);
        }

        ext.veil$addIncomingDirections(incoming);
    }

    @Inject(method = "addNearbySections", at = @At("HEAD"), cancellable = true, remap = false)
    public void addNearbySections(RenderSectionVisitor visitor, Viewport viewport, int frame, CallbackInfo ci) {
        if (!VeilLevelPerspectiveRenderer.isRenderingPerspective()) {
            return;
        }

        ci.cancel();
        SectionPos origin = viewport.getChunkCoord();
        int originX = origin.getX();
        int originY = origin.getY();
        int originZ = origin.getZ();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        RenderSection section = this.getRenderSection(originX + dx, originY + dy, originZ + dz);
                        RenderSectionExtension ext = (RenderSectionExtension) section;
                        if (section != null && ext.veil$hasNotRendered() && isWithinNearbySectionFrustum(viewport, section)) {
                            ext.veil$markRendered();
                            visitor.visit(section);
                        }
                    }
                }
            }
        }
    }

    @Inject(method = "initWithinWorld", at = @At("HEAD"), cancellable = true, remap = false)
    public void initWithinWorld(RenderSectionVisitor visitor, WriteQueue<RenderSection> queue, Viewport viewport, boolean useOcclusionCulling, int frame, CallbackInfo ci) {
        if (!VeilLevelPerspectiveRenderer.isRenderingPerspective()) {
            return;
        }

        ci.cancel();
        SectionPos origin = viewport.getChunkCoord();
        RenderSection section = this.getRenderSection(origin.getX(), origin.getY(), origin.getZ());
        RenderSectionExtension ext = (RenderSectionExtension) section;
        if (section != null) {
            ext.veil$markRendered();
            visitor.visit(section);
            int outgoing;
            if (useOcclusionCulling) {
                outgoing = VisibilityEncoding.getConnections(section.getVisibilityData());
            } else {
                outgoing = 63;
            }

            visitNeighbors(queue, section, outgoing, frame);
        }
    }
}
