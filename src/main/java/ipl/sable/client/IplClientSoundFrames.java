package ipl.sable.client;

import dev.ryanhcode.sable.sound.MovingSoundInstanceDelegate;
import ipl.sable.dim.IplClientWorldFrameContext;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * World frame of client tickable sounds started by hosted ship code.
 *
 * <p>Hosted plot block entities tick on the hosting client level inside IP's remote-world
 * tick ({@code ClientWorldLoader.withSwitchedWorld}), so while they run,
 * {@code Minecraft.level} is the level they live in. A tickable sound they start (engine
 * loops, thruster hums, machine whirs) is then ticked by the {@code SoundEngine} OUTSIDE
 * that scope, where {@code Minecraft.level} is the player's world: the common "is my block
 * entity's level still the client world?" liveness check fails on the first sound tick and
 * the sound stops immediately (hosted ships were silent). In stock Sable the plot and the
 * player share one level, so the check holds.
 *
 * <p>Rule: a tickable sound started while a hosting level was the current client world is
 * ticked in that same world frame (same IP world switch, and the same armed parent
 * context). Sounds started anywhere else are untouched.
 */
public final class IplClientSoundFrames {

    private record Frame(ClientLevel storage, @Nullable ClientLevel parentContext) {}

    private static final Map<SoundInstance, Frame> FRAMES =
        Collections.synchronizedMap(new WeakHashMap<>());

    private IplClientSoundFrames() {}

    public static void capture(SoundInstance sound) {
        if (!(sound instanceof TickableSoundInstance)) return;
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return;
        ClientLevel current = mc.level;
        if (current == null || !IplDimAgnostic.isHostingLevel(current)
            || !ClientWorldLoader.getIsWorldSwitched()) {
            return;
        }
        FRAMES.put(sound, new Frame(current, IplClientWorldFrameContext.current()));
    }

    @Nullable
    private static Frame frameOf(SoundInstance sound) {
        if (FRAMES.isEmpty()) return null;
        Frame frame = FRAMES.get(sound);
        if (frame == null && sound instanceof MovingSoundInstanceDelegate delegate && delegate.instance != null) {
            frame = FRAMES.get(delegate.instance);
        }
        if (frame == null) return null;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel live = ClientWorldLoader.getOptionalWorld(frame.storage().dimension());
        if (live != frame.storage() || mc.player == null || mc.level == frame.storage()) return null;
        return frame;
    }

    public static void runInFrame(SoundInstance sound, Runnable action) {
        Frame frame = frameOf(sound);
        if (frame == null) {
            action.run();
            return;
        }
        ClientWorldLoader.withSwitchedWorld(frame.storage(), () -> {
            ClientLevel prev = frame.parentContext() != null
                ? IplClientWorldFrameContext.push(frame.parentContext()) : null;
            try {
                action.run();
            } finally {
                if (frame.parentContext() != null) IplClientWorldFrameContext.pop(prev);
            }
        });
    }

    public static boolean callInFrame(SoundInstance sound, Supplier<Boolean> action) {
        Frame frame = frameOf(sound);
        if (frame == null) return action.get();
        return ClientWorldLoader.withSwitchedWorld(frame.storage(), action);
    }

    public static void clear() {
        FRAMES.clear();
    }
}
