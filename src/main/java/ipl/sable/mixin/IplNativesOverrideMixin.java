package ipl.sable.mixin;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Load the matching Atlas natives. Storage-chart bodies cannot safely fall back
 * to stock per-dimension physics: fail clearly instead of silently corrupting
 * joints, terrain sampling, and cross-chart ownership. ABI is checked before
 * the first scene is allocated. Both x86_64 Windows and Linux are supported.
 */
@Pseudo
@Mixin(value = Rapier3D.class, remap = false)
public abstract class IplNativesOverrideMixin {

    @Inject(method = "loadLibrary", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void ipl$loadCustomNatives(CallbackInfo ci) {
        if ("false".equalsIgnoreCase(System.getProperty("ipl.sable.customNatives", "true"))) {
            throw new IllegalStateException("IPSable hosted storage requires the custom Atlas natives");
        }

        String arch = System.getProperty("os.arch", "");
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!arch.equals("amd64") && !arch.equals("x86_64")) {
            throw new IllegalStateException("IPSable Atlas natives do not support architecture: " + arch);
        }
        String library = os.contains("win") ? "sable_rapier_x86_64_windows.dll"
            : os.contains("linux") ? "sable_rapier_x86_64_linux.so" : null;
        if (library == null) throw new IllegalStateException("Unsupported IPSable native platform: " + os);

        // Resource lookup notes: this handler executes as MERGED Rapier3D code (sable's
        // module), so the lookup must anchor on a real class in OUR module — and module
        // resource encapsulation can still null it, so fall back to the classloader view.
        String resource = "/natives_ipl/" + library;
        try {
            InputStream in = ipl.sable.natives.IplRapierNatives.class.getResourceAsStream(resource);
            if (in == null) {
                in = ipl.sable.natives.IplRapierNatives.class.getClassLoader()
                    .getResourceAsStream("natives_ipl/" + library);
            }
            if (in == null) {
                throw new java.io.FileNotFoundException("Required Atlas library is missing: " + resource
                    + "; build natives before processResources");
            }
            try (InputStream stream = in) {
                Path dir = Paths.get(".sable", "natives-ipl");
                Files.createDirectories(dir);
                Path dll = dir.resolve("ipl_abi2_" + library);
                Files.copy(stream, dll, StandardCopyOption.REPLACE_EXISTING);
                System.load(dll.toAbsolutePath().toString());
                ipl.sable.natives.IplRapierNatives.markAvailable();
                org.slf4j.LoggerFactory.getLogger("ipl-natives").info(
                    "[IPL-NATIVES] loaded IPSable-built sable_rapier natives from {}", dll.toAbsolutePath());
                ci.cancel();
            }
        } catch (Throwable t) {
            throw new IllegalStateException("IPSable cannot load the matching Atlas ABI 2 natives ("
                + library + "). Stock fallback is unsafe for hosted sublevels.", t);
        }
    }
}
