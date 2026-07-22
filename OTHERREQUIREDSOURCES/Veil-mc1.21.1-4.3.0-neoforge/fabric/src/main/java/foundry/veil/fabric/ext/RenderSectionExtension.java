package foundry.veil.fabric.ext;

public interface RenderSectionExtension {

    boolean veil$hasNotRendered();

    void veil$markRendered();

    void veil$addIncomingDirections(int directions);
}
