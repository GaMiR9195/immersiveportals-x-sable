package foundry.veil.api.resource.type;

import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public interface VeilShaderResource<T extends VeilShaderResource<?>> extends VeilTextResource<T> {

    @Override
    default int getIconCode() {
        return 0xECD1; // Code file icon
    }

//    @Override
//    default @Nullable TextEditorLanguageDefinition languageDefinition() {
//        return VeilLanguageDefinitions.glsl();
//    }
}
