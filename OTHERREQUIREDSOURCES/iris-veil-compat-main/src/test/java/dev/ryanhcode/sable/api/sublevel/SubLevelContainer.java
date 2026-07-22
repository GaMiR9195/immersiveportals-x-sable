package dev.ryanhcode.sable.api.sublevel;

import net.minecraft.client.multiplayer.ClientLevel;

public final class SubLevelContainer {
    private SubLevelContainer() {
    }

    public static ClientSubLevelContainer getContainer(ClientLevel level) {
        return new ClientSubLevelContainer();
    }

    public static ClientSubLevelContainer getContainer(Object level) {
        return new ClientSubLevelContainer();
    }
}
