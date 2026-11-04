package ipl.sable.duck;

import com.mojang.blaze3d.shaders.Uniform;
import org.jetbrains.annotations.Nullable;

/**
 * Duck interface implemented on every {@link net.minecraft.client.renderer.ShaderInstance}
 * via {@link ipl.sable.mixin.client.ShaderInstanceMixin}. Exposes the elements of our
 * independent per-sub-level clip uniform array.
 *
 * <p>Why a separate uniform from IP's {@code ip_clippingEquation}: IP's slot drives
 * {@code gl_ClipDistance[0]} and gets reused by IP's own pipeline (portal-through
 * view clip, entity render clip, weather clip, etc.). When we override that uniform
 * with our per-sub-level equation, we either fight IP for it or risk breaking IP's
 * intended visual effect in scenarios where both want to be active simultaneously
 * (e.g., a mirror inside a portal-through render -- IP wants to clip the dest-dim
 * view at the portal frame; we want to additionally clip the mirror's geometry at
 * its own plane). Using a parallel uniform array tied to
 * {@code gl_ClipDistance[1..SLOTS]} lets all planes apply independently -- a fragment is
 * culled if ANY plane says so, which is the geometrically-correct "intersection of kept
 * half-spaces."
 */
public interface IplSubLevelClipShader {
    /**
     * Element {@code slot} of {@code ipl_subLevelClipEquation[SLOTS]}, written to
     * {@code gl_ClipDistance[1 + slot]}; null when this shader does not declare the array.
     * The neutral value {@code (0,0,0,1)} disables the slot's cut.
     */
    @Nullable
    Uniform ipl$getSubLevelClipUniform(int slot);
}
