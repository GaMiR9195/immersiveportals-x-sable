package foundry.veil.api.client.render.shader.program;

import foundry.veil.api.client.render.shader.uniform.ShaderUniformAccess;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL31C;

/**
 * Provides read and write access to all uniform variables in a shader program.
 *
 * @author Ocelot
 */
@ApiStatus.NonExtendable
public interface UniformAccess {

    /**
     * Retrieves the location of a uniform.
     *
     * @param name The name of the uniform to get
     * @return The location of that uniform or <code>-1</code> if not found
     */
    int getUniformLocation(CharSequence name);

    /**
     * Checks if the specified uniform exists in the shader.
     *
     * @param name The name of the uniform to check
     * @return Whether that uniform can be set
     */
    boolean hasUniform(CharSequence name);

    /**
     * Retrieves a uniform by name.
     *
     * @param name The name of the uniform to get
     * @return The uniform with that name or <code>null</code> if the uniform does not exist
     */
    @Nullable ShaderUniformAccess getUniform(CharSequence name);

    /**
     * Retrieves a uniform by name.
     *
     * @param name The name of the uniform to get
     * @return The uniform with that name
     */
    ShaderUniformAccess getUniformSafe(CharSequence name);

    /**
     * Retrieves the location of a uniform block.
     *
     * @param name The name of the uniform block to get
     * @return The location of that uniform block or {@value GL31C#GL_INVALID_INDEX} if not found
     */
    int getUniformBlock(CharSequence name);

    /**
     * Checks if the specified uniform block exists in the shader.
     *
     * @param name The name of the uniform block to check
     * @return Whether that uniform block can be set
     */
    boolean hasUniformBlock(CharSequence name);

    /**
     * Retrieves the location of a storage block.
     *
     * @param name The name of the storage block to get
     * @return The location of that storage block or {@value GL31C#GL_INVALID_INDEX} if not found
     */
    int getStorageBlock(CharSequence name);

    /**
     * Checks if the specified storage block exists in the shader.
     *
     * @param name The name of the storage block to check
     * @return Whether that storage block can be set
     */
    boolean hasStorageBlock(CharSequence name);

    /**
     * Sets the binding to use for the specified uniform block.
     *
     * @param name    The name of the block to set
     * @param binding The binding to use for that block
     */
    void setUniformBlock(CharSequence name, int binding);

    /**
     * Sets the binding to use for the specified storage block.
     *
     * @param name    The name of the block to set
     * @param binding The binding to use for that block
     */
    void setStorageBlock(CharSequence name, int binding);
}
