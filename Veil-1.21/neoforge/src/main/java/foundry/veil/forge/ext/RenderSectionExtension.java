package foundry.veil.forge.ext;

public interface RenderSectionExtension {

    boolean veil$hasNotRendered();

    void veil$markRendered();

    void veil$addIncomingDirections(int directions);
}
