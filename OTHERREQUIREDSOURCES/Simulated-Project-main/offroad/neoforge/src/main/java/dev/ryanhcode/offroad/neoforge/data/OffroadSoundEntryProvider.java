package dev.ryanhcode.offroad.neoforge.data;

import dev.ryanhcode.offroad.Offroad;
import dev.ryanhcode.offroad.index.OffroadSoundEvents;
import com.google.gson.JsonObject;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class OffroadSoundEntryProvider implements DataProvider {

    private final PackOutput output;

    public OffroadSoundEntryProvider(final DataGenerator generator) {
        this.output = generator.getPackOutput();
    }

    @Override
    public CompletableFuture<?> run(final CachedOutput cache) {
        return this.generate(this.output.getOutputFolder(), cache);
    }

    @Override
    public String getName() {
        return "Offroad's Custom Sounds";
    }

    public CompletableFuture<?> generate(Path path, final CachedOutput cache) {
        path = path.resolve("assets/" + Offroad.MOD_ID);
        final JsonObject json = new JsonObject();
        OffroadSoundEvents.ALL.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    entry.getValue()
                            .write(json);
                });
        return DataProvider.saveStable(cache, json, path.resolve("sounds.json"));
    }
}
