package foundry.veil.impl.resource.loader;

import foundry.veil.api.resource.VeilResource;
import foundry.veil.api.resource.VeilResourceInfo;
import foundry.veil.api.resource.VeilResourceLoader;
import foundry.veil.api.resource.VeilResourceManager;
import foundry.veil.api.resource.type.TextureResource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

public class TextureResourceLoader implements VeilResourceLoader {

    private static final Set<String> EXTENSIONS = Set.of(
            ".png",
            ".jpg",
            ".jpeg"
    );

    @Override
    public boolean canLoad(PackType packType, ResourceLocation location, Path filePath, @Nullable Path modResourcePath) {
        if (packType != PackType.CLIENT_RESOURCES) {
            return false;
        }

        for (String extension : EXTENSIONS) {
            if (location.getPath().endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public VeilResource<?> load(VeilResourceManager resourceManager, ResourceProvider provider, PackType packType, ResourceLocation location, @Nullable Path filePath, @Nullable Path modResourcePath) throws IOException {
//        ResourceMetadata metadata = resourceManager.getResourceMetadata(path);
//        AnimationMetadataSection animation = null;
//        if (metadata != null) {
//            animation = metadata.getSection(AnimationMetadataSection.SERIALIZER).orElse(null);
//        }
        return new TextureResource(new VeilResourceInfo(packType, location, filePath, modResourcePath, false));
    }
}
