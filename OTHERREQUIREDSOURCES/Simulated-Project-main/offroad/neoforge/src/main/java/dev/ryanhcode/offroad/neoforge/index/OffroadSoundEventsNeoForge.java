package dev.ryanhcode.offroad.neoforge.index;

import dev.ryanhcode.offroad.index.OffroadSoundEvents;
import com.simibubi.create.AllSoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

public class OffroadSoundEventsNeoForge {

    public static void register(final RegisterEvent.RegisterHelper<SoundEvent> helper) {
        for (final AllSoundEvents.SoundEntry entry : OffroadSoundEvents.ALL.values())
            entry.register(helper);
    }

}
