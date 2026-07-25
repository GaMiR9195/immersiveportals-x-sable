package foundry.veil.fabric.mixin.compat.sodium;

import com.google.common.base.Stopwatch;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.util.Pair;
import foundry.veil.Veil;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.ext.sodium.ChunkShaderOptionsExtension;
import foundry.veil.fabric.ext.ShaderChunkRendererExtension;
import foundry.veil.impl.ThreadTaskScheduler;
import foundry.veil.impl.client.render.shader.processor.SodiumShaderProcessor;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.caffeinemc.mods.sodium.client.gl.shader.*;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;

@SuppressWarnings("ConstantValue")
@Mixin(ShaderChunkRenderer.class)
public abstract class ShaderChunkRendererMixin implements ShaderChunkRendererExtension {

    @Shadow(remap = false)
    @Final
    private Map<ChunkShaderOptions, GlProgram<ChunkShaderInterface>> programs;

    @Shadow(remap = false)
    protected abstract GlProgram<ChunkShaderInterface> createShader(String path, ChunkShaderOptions options);

    @Shadow
    private static ShaderConstants createShaderConstants(ChunkShaderOptions options) {
        throw new UnsupportedOperationException("Implemented via mixin");
    }

    @Unique
    private ThreadTaskScheduler veil$scheduler;
    @Unique
    private Map<ShaderType, ShaderParser.ParsedShader> veil$shaderSource;
    @Unique
    private int veil$activeBuffers;

    @Unique
    private static final Map<ShaderType, ResourceLocation> SHADERS = Map.of(
            ShaderType.VERTEX,
            ResourceLocation.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque.vsh"),
            ShaderType.FRAGMENT,
            ResourceLocation.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque.fsh")
    );

    @Inject(method = "delete", at = @At("HEAD"), remap = false)
    public void delete(CallbackInfo ci) {
        if (this.veil$scheduler != null) {
            this.veil$scheduler.cancel();
        }
        this.veil$activeBuffers = 0;
    }

    @Inject(method = "compileProgram", at = @At("HEAD"), remap = false)
    public void updateActiveProgram(ChunkShaderOptions options, CallbackInfoReturnable<GlProgram<ChunkShaderInterface>> cir) {
        ((ChunkShaderOptionsExtension) (Object) options).veil$setActiveBuffers(this.veil$activeBuffers);
    }

    @WrapOperation(method = "createShader", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderLoader;loadShader(Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderType;Lnet/minecraft/resources/ResourceLocation;Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderConstants;)Lnet/caffeinemc/mods/sodium/client/gl/shader/GlShader;", remap = true), require = 2, remap = false)
    private GlShader createShader(ShaderType type, ResourceLocation name, ShaderConstants constants, Operation<GlShader> original) {
        if (this.veil$shaderSource != null) {
            ShaderParser.ParsedShader source = this.veil$shaderSource.get(type);
            if (source != null) {
                return new GlShader(type, name, source);
            }
        }
        return original.call(type, name, constants);
    }

    @Unique
    private void recompile(Queue<Pair<ChunkShaderOptions, ShaderConstants>> keys, int activeBuffers) {
        if (this.veil$scheduler != null) {
            this.veil$scheduler.cancel();
        }

        int shaderCount = keys.size();
        Stopwatch stopwatch = Stopwatch.createStarted();
        GLCapabilities glCapabilities = GL.getCapabilities();

        this.veil$scheduler = new ThreadTaskScheduler("VeilSodiumShaderCompile", 1, () -> {
            Pair<ChunkShaderOptions, ShaderConstants> pair = keys.poll();
            if (pair == null) {
                return null;
            }
            return () -> {
                Map<ShaderType, ShaderParser.ParsedShader> map = new Object2ObjectArrayMap<>();
                for (Map.Entry<ShaderType, ResourceLocation> entry : SHADERS.entrySet()) {
                    ShaderType type = entry.getKey();
                    SodiumShaderProcessor.setShaderType(type.id, entry.getValue(), glCapabilities);
                    ShaderParser.ParsedShader src = ShaderParser.parseShader(ShaderLoader.getShaderSource(entry.getValue()), pair.getSecond());
                    map.put(type, src);
                }

                Minecraft.getInstance().execute(() -> {
                    this.veil$shaderSource = map;
                    GlProgram<ChunkShaderInterface> old = this.programs.put(pair.getFirst(), this.createShader("blocks/block_layer_opaque", pair.getFirst()));
                    if (old != null) {
                        old.delete();
                    }
                    this.veil$shaderSource = null;
                });
            };
        });
        this.veil$scheduler.getCompletedFuture().thenRunAsync(() -> {
            this.veil$shaderSource = null;
            this.veil$activeBuffers = activeBuffers;
            if (!this.veil$scheduler.isCancelled()) {
                Veil.LOGGER.info("Compiled {} Sodium Shaders in {}", shaderCount, stopwatch.stop());
            }
        }, VeilRenderSystem.renderThreadExecutor());
    }

    @SuppressWarnings("RedundantOperationOnEmptyContainer")
    @Unique
    private Queue<Pair<ChunkShaderOptions, ShaderConstants>> getActiveKeys(int activeBuffers) {
        Queue<Pair<ChunkShaderOptions, ShaderConstants>> keys = new ConcurrentLinkedDeque<>();
        for (ChunkShaderOptions key : this.programs.keySet()) {
            boolean unique = true;
            for (Pair<ChunkShaderOptions, ShaderConstants> pair : keys) {
                ChunkShaderOptions options = pair.getFirst();
                if (options.fog() == key.fog() && options.pass() == key.pass() && options.vertexType() == key.vertexType()) {
                    unique = false;
                    break;
                }
            }
            if (unique) {
                ChunkShaderOptions options = new ChunkShaderOptions(key.fog(), key.pass(), key.vertexType());
                ((ChunkShaderOptionsExtension) (Object) options).veil$setActiveBuffers(activeBuffers);
                keys.add(Pair.of(options, createShaderConstants(options)));
            }
        }
        return keys;
    }

    @Override
    public void veil$recompile() {
        this.recompile(this.getActiveKeys(this.veil$activeBuffers), this.veil$activeBuffers);
    }

    @Override
    public void veil$setActiveBuffers(int activeBuffers) {
        if (this.veil$activeBuffers == activeBuffers) {
            return;
        }

        if (this.veil$scheduler != null) {
            this.veil$scheduler.cancel();
        }

        Queue<Pair<ChunkShaderOptions, ShaderConstants>> keys = this.getActiveKeys(activeBuffers);
        keys.removeIf(pair -> this.programs.containsKey(pair.getFirst()));
        if (keys.isEmpty()) {
            return;
        }

        this.recompile(keys, activeBuffers);
    }

    @Override
    public Map<ChunkShaderOptions, GlProgram<ChunkShaderInterface>> veil$getPrograms() {
        return this.programs;
    }
}
