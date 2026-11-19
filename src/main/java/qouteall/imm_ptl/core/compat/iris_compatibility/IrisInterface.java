package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import qouteall.q_misc_util.Helper;

import java.lang.reflect.Field;

public class IrisInterface {

    public static class Invoker {
        public boolean isIrisPresent() {
            return false;
        }

        public boolean isShaders() {
            return false;
        }

        public boolean isRenderingShadowMap() {
            return false;
        }

        public Object getPipeline(LevelRenderer worldRenderer) {
            return null;
        }

        public void setPipeline(LevelRenderer worldRenderer, Object pipeline) {

        }

        public void reloadPipelines() {}

        /** Z scale of the projection while the shader pipeline draws the first-person hand, else 1. */
        public float getHandDepthScale() {
            return 1;
        }

        @Nullable
        public String getShaderpackName() {
            return null;
        }
    }

    public static class OnIrisPresent extends Invoker {

        private final Field worldRendererPipelineField = Helper.noError(() -> {
            Field field = LevelRenderer.class.getDeclaredField("pipeline");
            field.setAccessible(true);
            return field;
        });

        @Override
        public boolean isIrisPresent() {
            return true;
        }

        @Override
        public boolean isShaders() {
            return Iris.getCurrentPack().isPresent();
        }

        @Override
        public boolean isRenderingShadowMap() {
            return ShadowRenderer.ACTIVE;
        }

        @Override
        public Object getPipeline(LevelRenderer worldRenderer) {
            return Helper.noError(() ->
                ((WorldRenderingPipeline) worldRendererPipelineField.get(worldRenderer))
            );
        }

        // the pipeline switching is unnecessary when using shaders
        // but still necessary with shaders disabled
        @Override
        public void setPipeline(LevelRenderer worldRenderer, Object pipeline) {
            Helper.noError(() -> {
                worldRendererPipelineField.set(worldRenderer, pipeline);
                return null;
            });
        }

        @Override
        public void reloadPipelines() {
            Iris.getPipelineManager().destroyPipeline();
        }

        @Override
        public float getHandDepthScale() {
            return HandRenderer.INSTANCE.isActive() ? HandRenderer.DEPTH : 1;
        }

        @Nullable
        @Override
        public String getShaderpackName() {
            return Iris.getCurrentPackName();
        }
    }

    public static Invoker invoker = new Invoker();
}
