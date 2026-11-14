package ipl.sable.render;

import foundry.veil.api.client.render.shader.processor.ShaderPreProcessor;
import io.github.ocelot.glslprocessor.api.GlslParser;
import io.github.ocelot.glslprocessor.api.GlslSyntaxException;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import ipl.sable.render.clip.IplClipShaderInjector;
import ipl.sable.render.clip.IplClipShaderInjector.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.IPGlobal;

/**
 * Veil compiles its own programs and recompiles vanilla ones past Mojang's {@code Program}, so
 * the clip writes of {@link IplClipShaderInjector} are added here too: to every source file of
 * the last vertex-processing stage (vertex, tessellation evaluation, geometry). Veil's
 * post-processing passes draw in screen space and run with the cuts suspended
 * ({@code VeilPostProcessingMixin}).
 */
public class IplVeilShaderPreProcessor implements ShaderPreProcessor {

	private static final Logger LOG = LoggerFactory.getLogger("ipl-veil-preprocess");

	@Override
	public void modify(Context ctx, GlslTree tree) throws GlslSyntaxException {
		if (!IPGlobal.enableClippingMechanism || !ctx.isSourceFile()) return;
		Stage stage = stageOf(ctx);
		if (stage == null) return;
		String before = tree.toSourceString();
		String after = IplClipShaderInjector.inject(stage, before);
		if (before.equals(after)) return;
		GlslTree replacement;
		try {
			replacement = GlslParser.parse(after);
		} catch (GlslSyntaxException e) {
			LOG.error("[IPL-VEIL-PREPROCESS] {} {}: clip writes do not re-parse, shader left unclipped",
				ctx.name(), ctx.typeName(), e);
			return;
		}
		tree.getBody().clear();
		tree.getBody().addAll(replacement.getBody());
	}

	private static Stage stageOf(Context ctx) {
		if (ctx.isVertex()) return Stage.VERTEX;
		if (ctx.isTessellationEvaluation()) return Stage.TESSELLATION_EVALUATION;
		if (ctx.isGeometry()) return Stage.GEOMETRY;
		return null;
	}
}
