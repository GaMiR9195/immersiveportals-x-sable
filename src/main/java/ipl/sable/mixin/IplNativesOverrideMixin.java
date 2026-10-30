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
 * Load IPSable's locally-built Sable natives instead of the ones bundled in the sable jar
 * (aperture contact clipping, image colliders and contact-impulse readout live in the Rust
 * layer).
 *
 * <p>Sable re-extracts its bundled {@code .l4z} natives with {@code REPLACE_EXISTING} on
 * every launch, so swapping the extracted file can't stick; and repacking the sable jar
 * couples our deploy loop to theirs. Instead: this mod carries the patched library as a
 * resource ({@code /natives_ipl/sable_rapier_<arch>_<os>.<ext>}, same naming as Sable:
 * arch {@code x86_64|aarch64}, os/ext {@code windows.dll|linux.so|macos.dylib}), and this
 * mixin — whose handler executes as merged {@code Rapier3D} code, i.e. the exact
 * classloader whose natives the JVM resolves — extracts and {@code System.load}s it at the
 * head of {@code loadLibrary}, cancelling the stock path.
 *
 * <p>Fail-open: a platform without a bundled library, an IO error or a link error logs once
 * and falls through to Sable's stock natives (the portal features that need the patched
 * engine then stay off). Kill switch: {@code -Dipl.sable.customNatives=false}.
 */
@Pseudo
@Mixin(value = Rapier3D.class, remap = false)
public abstract class IplNativesOverrideMixin {

    @Inject(method = "loadLibrary", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void ipl$loadCustomNatives(CallbackInfo ci) {
        if ("false".equalsIgnoreCase(System.getProperty("ipl.sable.customNatives", "true"))) {
            return;
        }
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("ipl-natives");

        String archProperty = System.getProperty("os.arch", "");
        String arch = archProperty.equals("arm") || archProperty.startsWith("aarch64") ? "aarch64" : "x86_64";
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String suffix = os.contains("win") ? "windows.dll"
            : os.contains("mac") || os.contains("darwin") ? "macos.dylib"
            : "linux.so";
        String fileName = "sable_rapier_" + arch + "_" + suffix;

        // Resource lookup notes: this handler executes as MERGED Rapier3D code (sable's
        // module), so the lookup must anchor on a real class in OUR module — and module
        // resource encapsulation can still null it, so fall back to the classloader view.
        String resource = "/natives_ipl/" + fileName;
        try {
            InputStream in = ipl.sable.natives.IplRapierNatives.class.getResourceAsStream(resource);
            if (in == null) {
                in = ipl.sable.natives.IplRapierNatives.class.getClassLoader()
                    .getResourceAsStream("natives_ipl/" + fileName);
            }
            if (in == null) {
                log.info("[IPL-NATIVES] no IPSable natives bundled for this platform ({}); "
                    + "using sable's stock natives — portal collision features are disabled", fileName);
                return;
            }
            try (InputStream stream = in) {
                Path dir = Paths.get(".sable", "natives-ipl");
                Files.createDirectories(dir);
                Path lib = dir.resolve(fileName.replace("sable_rapier_", "sable_rapier_ipl_"));
                Files.copy(stream, lib, StandardCopyOption.REPLACE_EXISTING);
                System.load(lib.toAbsolutePath().toString());
                ipl.sable.natives.IplRapierNatives.markAvailable();
                log.info("[IPL-NATIVES] loaded IPSable-built sable_rapier natives from {}", lib.toAbsolutePath());
                ci.cancel();
            }
        } catch (Throwable t) {
            log.error("[IPL-NATIVES] failed to load custom natives — falling back to sable's bundled build", t);
            // fall through: stock loadLibrary proceeds
        }
    }
}
