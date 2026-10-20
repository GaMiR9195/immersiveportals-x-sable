package foundry.veil.api.client.render.shader.processor;

import io.github.ocelot.glslprocessor.api.GlslSyntaxException;
import io.github.ocelot.glslprocessor.api.grammar.GlslVersionStatement;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import io.github.ocelot.glslprocessor.lib.anarres.cpp.LexerException;
import org.lwjgl.opengl.GLCapabilities;

import java.io.IOException;

/**
 * Adds the version and required extensions for all shaders that do not define a version.
 *
 * @author Ocelot
 */
public class ShaderVersionProcessor implements ShaderPreProcessor {

    @Override
    public void modify(Context ctx, GlslTree tree) throws IOException, GlslSyntaxException, LexerException {
        GlslVersionStatement version = tree.getVersionStatement();
        if (version.getVersion() == 110 && version.isCore()) {
            GLCapabilities caps = ctx.glCapabilities();
            if (caps.OpenGL41) {
                version.setVersion(410);
            } else {
                // Fallback to vanilla mc version
                version.setVersion(330);
            }
        }
    }
}
