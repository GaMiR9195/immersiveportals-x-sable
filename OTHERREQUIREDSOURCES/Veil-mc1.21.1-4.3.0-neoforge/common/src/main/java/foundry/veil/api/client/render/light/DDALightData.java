package foundry.veil.api.client.render.light;

/**
 * Marks a light as Digital Differential Analyzer (DDA) enabled light. This allows the light to draw raymarched shadows.
 *
 * @since 3.3.0
 */
public interface DDALightData {

    /**
     * @return Whether occlusion is enabled
     */
    boolean isOcclusionEnabled();
}
