package foundry.veil.ext;

import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;

/**
 * @since 3.6.0
 */
public interface AbstractTextureExtension {

    /**
     * @return The target this texture uses
     */
    default int getTextureTarget() {
        return GL_TEXTURE_2D;
    }
}
