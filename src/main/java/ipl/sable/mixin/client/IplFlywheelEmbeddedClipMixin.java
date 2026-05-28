package ipl.sable.mixin.client;

import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import ipl.sable.render.IplProgramBindHook;
import ipl.sable.render.IplSubLevelUniformRegistry;
import ipl.sable.render.SubLevelClipUniformPatcher;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL41;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Catch Flywheel's per-embedded-environment program binds that miss BOTH
 * our {@code GlStateManager._glUseProgram} AND
 * {@code ProgramManager.glUseProgram} hooks.
 *
 * <p><b>The path we're catching:</b> Sable wires Create
 * {@code BracketedKineticBlockEntity} (cogs) to Flywheel
 * {@code BlockEntityVisualizer} via
 * {@code BlockEntityStorageMixin.sable$createVisual}, embedding the cog
 * into a per-sub-level Flywheel {@code VisualEmbedding}. The cog is
 * rendered by {@link EmbeddedEnvironment#setupDraw(GlProgram)} which
 * calls Flywheel's own {@code GlProgram.bind} ->
 * {@code GL20C.glUseProgram} directly, bypassing all Mojang/Iris
 * wrappers.
 *
 * <p>Critical insight (user-reported): Flywheel's MAIN backend may have
 * fallen back to "off" globally under Iris+shaderpack, but per-sub-level
 * Flywheel embeddings stay active -- that's the whole point of Sable's
 * BlockEntityStorageMixin wiring. Empirical: sub-level cogs cast no
 * shadows (Flywheel path), main-world cogs DO cast shadows (vanilla BER
 * path). So `EmbeddedEnvironment.setupDraw` IS firing for sub-level
 * cogs in this config.
 *
 * <p>An earlier @Pseudo+targets-string attempt silently no-op'd --
 * mixin without compile-time class info couldn't bind the inject. This
 * version uses a direct class reference (compileOnly Flywheel dep added
 * in allProjects.gradle).
 *
 * <p>What this mixin does at {@code setupDraw} HEAD:
 * <ol>
 *   <li>Reads the bound program's GL handle via {@link GlProgram#handle()}
 *       and registers it with {@link IplSubLevelUniformRegistry} via
 *       {@link IplProgramBindHook} so it flows through our normal
 *       cache + spray paths going forward.</li>
 *   <li>If there's a "latest" sub-level equation available (set the
 *       last time any Sable bracket fired {@code patchForSubLevel}),
 *       write it to the program's {@code ipl_subLevelClipEquation}
 *       location and enable {@code GL_CLIP_DISTANCE1}.</li>
 * </ol>
 *
 * <p>Uses {@code latestSubLevelEqWorld} (never-cleared) rather than
 * {@code currentSubLevelEqWorld} because Flywheel's draw stage runs
 * AFTER all Sable brackets have exited.
 */
@Mixin(value = EmbeddedEnvironment.class, remap = false)
public class IplFlywheelEmbeddedClipMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-flywheel-embedded-clip");

    @Unique
    private static final AtomicBoolean IPL$LOGGED_FIRST_FIRE = new AtomicBoolean(false);

    @Unique
    private static final AtomicLong IPL$LAST_LOG_NS = new AtomicLong(0L);

    @Inject(
        method = "setupDraw",
        at = @At("HEAD"),
        remap = false
    )
    private void ipl$onSetupDraw(GlProgram program, CallbackInfo ci) {
        if (IPL$LOGGED_FIRST_FIRE.compareAndSet(false, true)) {
            IPL$LOG.info("[IPL-FLYWHEEL-EMBED-FIRED] FIRST-FIRE program={} class={}",
                program, program != null ? program.getClass().getName() : "null");
        }

        if (program == null) return;
        int progId = program.handle();
        if (progId <= 0) return;

        // Register so our normal spray path covers this program going forward.
        IplProgramBindHook.onBind(progId);

        // Write our slot-1 equation directly to this program (Sable brackets
        // have exited by now, so use the persistent "latest" cache).
        float[] eqWorld = SubLevelClipUniformPatcher.getLatestSubLevelEqWorld();
        if (eqWorld == null) {
            return;
        }

        int subLevelLoc = GL20.glGetUniformLocation(progId, "ipl_subLevelClipEquation");
        if (subLevelLoc < 0) {
            return;
        }

        GL41.glProgramUniform4f(progId, subLevelLoc,
            eqWorld[0], eqWorld[1], eqWorld[2], eqWorld[3]);
        GL11.glEnable(GL30.GL_CLIP_DISTANCE1);

        long now = System.nanoTime();
        long last = IPL$LAST_LOG_NS.get();
        if (now - last > 5_000_000_000L) {
            IPL$LAST_LOG_NS.set(now);
            IPL$LOG.info("[IPL-FLYWHEEL-EMBED-WRITE] progId={} subLevelLoc={} eq=({},{},{},{})",
                progId, subLevelLoc, eqWorld[0], eqWorld[1], eqWorld[2], eqWorld[3]);
        }
    }
}
