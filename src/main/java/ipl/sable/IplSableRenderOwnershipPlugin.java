package ipl.sable;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

/**
 * Sable's render handlers are renamed by Mixin when merged into vanilla/Sodium.
 * Wildcard injectors intentionally skip merged handlers. Adapt only methods whose
 * MixinMerged provenance identifies Sable's stock render submission, never addon
 * enumerations (e.g. scale handshakes or embedding transforms).
 */
public final class IplSableRenderOwnershipPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String pkg) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) { return true; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String name, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String name, ClassNode node, String mixin, IMixinInfo info) {
        if (name.equals("net.minecraft.client.renderer.LevelRenderer")
            || name.equals("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer")) {
            adaptRenderConsumers(node);
        }
    }
    public static int adaptRenderConsumers(ClassNode node) {
        int changed = 0;
        for (MethodNode method : node.methods) {
            if (!isSableSubmission(method.visibleAnnotations) && !isSableSubmission(method.invisibleAnnotations)) continue;
            for (AbstractInsnNode ins : method.instructions) {
                if (!(ins instanceof MethodInsnNode call) || !call.name.equals("getAllSubLevels")
                    || !call.desc.equals("()Ljava/util/List;")
                    || !(call.owner.equals("dev/ryanhcode/sable/api/sublevel/ClientSubLevelContainer")
                        || call.owner.equals("dev/ryanhcode/sable/api/sublevel/SubLevelContainer"))) continue;
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = "ipl/sable/dim/IplOwnedSubLevels";
                call.name = "owned";
                call.desc = "(Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;)Ljava/util/List;";
                call.itf = false;
                changed++;
            }
        }
        return changed;
    }
    private static boolean isSableSubmission(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (var annotation : annotations) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;") || annotation.values == null) continue;
            for (int i=0; i+1<annotation.values.size(); i+=2) {
                if (annotation.values.get(i).equals("mixin") && annotation.values.get(i+1) instanceof String origin
                    && origin.startsWith("dev.ryanhcode.sable.mixin.sublevel_render.")) return true;
            }
        }
        return false;
    }
}
