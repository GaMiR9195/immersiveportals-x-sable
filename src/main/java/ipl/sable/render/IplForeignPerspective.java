package ipl.sable.render;

import net.neoforged.fml.ModList;

/**
 * Level renders issued from another camera than the view's (Veil's level perspective renderer:
 * Sable's sky-light shadow map, mirrors and cameras of other mods). Such a render is not a view
 * of the player and not a portal pass: it takes no portal clip planes (they are planes of the
 * view's camera) and renders no portals into its target.
 */
public final class IplForeignPerspective {
    private static final boolean VEIL = ModList.get() != null && ModList.get().isLoaded("veil");

    private IplForeignPerspective() {}

    public static boolean isRendering() {
        return VEIL && Veil.isRendering();
    }

    private static final class Veil {
        static boolean isRendering() {
            return foundry.veil.api.client.render.VeilLevelPerspectiveRenderer.isRenderingPerspective();
        }
    }
}
