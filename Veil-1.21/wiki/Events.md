Veil provides a basic event wrapper for Veil-specific events. The implementation subscribes to the Forge/Fabric events
based on the platform currently running.

The bridge methods are simply for convenience and have no other special internal features.

## Examples

Common

```java
import foundry.veil.event.FreeNativeResourcesEvent;
import foundry.veil.platform.services.VeilEventPlatform;

// This class is platform independent, so it uses the veil platform method
public class ModCommon {

    public static void initCommon() {
        VeilEventPlatform.INSTANCE.onFreeNativeResources(() -> {
            // listener here
        });
    }
}
```

Forge

```java
import foundry.veil.forge.event.ForgeFreeNativeResourcesEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod("modid")
public class ModForge {

    public ModForge() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onEvent(ForgeFreeNativeResourcesEvent event) {
        // listener here
    }
}
```

Fabric

```java
import foundry.veil.fabric.event.FabricFreeNativeResourcesEvent;
import net.fabricmc.api.ClientModInitializer;

public class ModFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        FabricFreeNativeResourcesEvent.EVENT.register(() -> {
            // listener here
        });
    }
}
```
