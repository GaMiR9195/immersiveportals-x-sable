package foundry.veil.api.client.render.shader.program;

import foundry.veil.api.client.render.ext.VeilMultiBind;
import foundry.veil.api.client.render.framebuffer.AdvancedFbo;
import foundry.veil.api.client.render.framebuffer.AdvancedFboTextureAttachment;
import foundry.veil.api.client.render.shader.texture.ShaderTextureSource;
import foundry.veil.ext.AbstractTextureExtension;
import foundry.veil.impl.client.render.shader.program.ShaderProgramImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;

/**
 * Provides write access to all textures in a shader program.
 *
 * @author Ocelot
 */
@ApiStatus.NonExtendable
public interface TextureUniformAccess {

    /**
     * Sets <code>DiffuseSampler0</code>-<code>DiffuseSamplerMax</code> to the color buffers in the specified framebuffer.
     * <br>
     * Also sets <code>DiffuseDepthSampler</code> if the framebuffer has a depth attachment.
     *
     * @param framebuffer The framebuffer to bind samplers from
     */
    default void setFramebufferSamplers(AdvancedFbo framebuffer) {
        boolean setDiffuseSampler = false;
        for (int i = 0; i < framebuffer.getColorAttachments(); i++) {
            if (!framebuffer.isColorTextureAttachment(i)) {
                continue;
            }

            AdvancedFboTextureAttachment attachment = framebuffer.getColorTextureAttachment(i);
            this.setTexture("DiffuseSampler" + i, GL_TEXTURE_2D, attachment.getId());
            if (attachment.getName() != null) {
                this.setTexture(attachment.getName(), GL_TEXTURE_2D, attachment.getId());
            }
            if (!setDiffuseSampler) {
                this.setTexture("DiffuseSampler", GL_TEXTURE_2D, attachment.getId());
                setDiffuseSampler = true;
            }
        }

        if (framebuffer.isDepthTextureAttachment()) {
            AdvancedFboTextureAttachment attachment = framebuffer.getDepthTextureAttachment();
            this.setTexture("DiffuseDepthSampler", GL_TEXTURE_2D, attachment.getId());
            if (attachment.getName() != null) {
                this.setTexture(attachment.getName(), GL_TEXTURE_2D, attachment.getId());
            }
        }
    }

    /**
     * Adds a texture that is dynamically bound and sets texture units.
     *
     * @param name     The name of the texture to set
     * @param location The name of the texture in the texture manager to bind and assign a texture unit
     * @since 3.6.0
     */
    default void setTexture(CharSequence name, ResourceLocation location) {
        this.setTexture(name, Minecraft.getInstance().getTextureManager().getTexture(location));
    }

    /**
     * Adds a texture that is dynamically bound and sets texture units.
     *
     * @param name      The name of the texture to set
     * @param location  The name of the texture in the texture manager to bind and assign a texture unit
     * @param samplerId The id of the sampler assign a texture unit
     * @since 3.6.0
     */
    default void setTexture(CharSequence name, ResourceLocation location, int samplerId) {
        this.setTexture(name, Minecraft.getInstance().getTextureManager().getTexture(location), samplerId);
    }

    /**
     * Adds a texture that is dynamically bound and sets texture units.
     *
     * @param name    The name of the texture to set
     * @param texture The texture to bind and assign a texture unit
     * @since 3.6.0
     */
    default void setTexture(CharSequence name, AbstractTexture texture) {
        this.setTexture(name, ((AbstractTextureExtension) texture).getTextureTarget(), texture.getId(), 0);
    }

    /**
     * Adds a texture that is dynamically bound and sets texture units.
     *
     * @param name      The name of the texture to set
     * @param texture   The texture to bind and assign a texture unit
     * @param samplerId The id of the sampler assign a texture unit
     * @since 3.6.0
     */
    default void setTexture(CharSequence name, AbstractTexture texture, int samplerId) {
        this.setTexture(name, ((AbstractTextureExtension) texture).getTextureTarget(), texture.getId(), samplerId);
    }

    /**
     * Adds a texture that is dynamically bound and sets texture units.
     *
     * @param name      The name of the texture to set
     * @param target    The target of the texture
     * @param textureId The id of the texture to bind and assign a texture unit
     * @since 3.6.0
     */
    default void setTexture(CharSequence name, int target, int textureId) {
        this.setTexture(name, target, textureId, 0);
    }

    /**
     * Adds a texture that is dynamically bound and sets texture units.
     *
     * @param name      The name of the texture to set
     * @param target    The target of the texture
     * @param textureId The id of the texture to bind and assign a texture unit
     * @param samplerId The id of the sampler assign a texture unit
     * @since 3.6.0
     */
    void setTexture(CharSequence name, int target, int textureId, int samplerId);

    /**
     * Removes the specified sampler binding. This will effectively make it a missing texture.
     *
     * @param name The name of the sampler to remove
     * @since 3.6.0
     */
    void removeTexture(CharSequence name);

    /**
     * Loads the samplers set by {@link #setTexture(CharSequence, int, int)} into the shader.
     *
     * @param samplerStart The sampler to start binding to
     */
    default void bindSamplers(int samplerStart) {
        this.bindSamplers(ShaderTextureSource.GLOBAL_CONTEXT, samplerStart);
    }

    /**
     * Loads the samplers set by {@link #setTexture(CharSequence, int, int)} into the shader.
     *
     * @param context      The context for setting built-in shader samplers or <code>null</code> to ignore normal samplers
     * @param samplerStart The sampler to start binding to
     */
    void bindSamplers(@Nullable ShaderTextureSource.Context context, int samplerStart);

    /**
     * Clears all samplers.
     */
    void clearSamplers();

    /**
     * Sets <code>DiffuseSampler0</code>-<code>DiffuseSamplerMax</code> to the color buffers in the specified framebuffer.
     * <br>
     * Also sets <code>DiffuseDepthSampler</code> if the framebuffer has a depth attachment.
     *
     * @param framebuffer The framebuffer to bind samplers from
     */
    static void setFramebufferSamplers(ShaderInstance instance, AdvancedFbo framebuffer) {
        if (instance instanceof ShaderProgramImpl.Wrapper wrapper) {
            wrapper.program().setFramebufferSamplers(framebuffer);
            return;
        }

        boolean setDiffuseSampler = false;
        for (int i = 0; i < framebuffer.getColorAttachments(); i++) {
            if (!framebuffer.isColorTextureAttachment(i)) {
                continue;
            }

            AdvancedFboTextureAttachment attachment = framebuffer.getColorTextureAttachment(i);
            instance.setSampler("DiffuseSampler" + i, attachment);
            if (attachment.getName() != null) {
                instance.setSampler(attachment.getName(), attachment);
            }
            if (!setDiffuseSampler) {
                instance.setSampler("DiffuseSampler", attachment);
                setDiffuseSampler = true;
            }
        }

        if (framebuffer.isDepthTextureAttachment()) {
            AdvancedFboTextureAttachment attachment = framebuffer.getDepthTextureAttachment();
            instance.setSampler("DiffuseDepthSampler", attachment);
            if (attachment.getName() != null) {
                instance.setSampler(attachment.getName(), attachment);
            }
        }
    }
}
