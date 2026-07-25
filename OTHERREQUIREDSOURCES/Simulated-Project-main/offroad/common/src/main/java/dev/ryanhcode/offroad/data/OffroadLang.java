package dev.ryanhcode.offroad.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.simibubi.create.foundation.utility.FilesHelper;
import com.tterrag.registrate.providers.RegistrateLangProvider;
import dev.ryanhcode.offroad.Offroad;
import dev.ryanhcode.offroad.content.ponder.OffroadPonderPlugin;
import dev.ryanhcode.offroad.index.OffroadAdvancements;
import dev.ryanhcode.offroad.index.OffroadSoundEvents;
import net.createmod.catnip.lang.Lang;
import net.createmod.catnip.lang.LangBuilder;
import net.createmod.catnip.lang.LangNumberFormat;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public class OffroadLang {

    public static LangBuilder builder() {
        return Lang.builder(Offroad.MOD_ID);
    }

    public static LangBuilder text(final String text) {
        return builder().text(text);
    }

    public static LangBuilder translate(final String key, final Object... args) {
        return builder().translate(key, args);
    }

    public static LangBuilder number(final double number) {
        return builder().text(LangNumberFormat.format(number));
    }

    public static LangBuilder space() {
        return builder().space();
    }

    public static void emptyLine(final List<Component> tooltip) {
        builder().text("").forGoggles(tooltip);
    }

    public static LangBuilder blockName(final BlockState blockState) {
        return builder().add(blockState.getBlock().getName());
    }

    public static List<Component> translatedOptions(final String prefix, final String... keys) {
        final List<Component> result = new ArrayList<>(keys.length);
        for (final String key : keys)
            result.add(translate((prefix != null ? prefix + "." : "") + key).component());
        return result;
    }

    public static LangBuilder kilopixelGram(final double value) {
        return kilopixelGram(value, "%.2f");
    }

    public static LangBuilder kilopixelGram(final double value, final String format) {
        return getPrefixedUnit("pg", value, format);
    }

    public static LangBuilder kilopixelNewton(final double value) {
        return kilopixelNewton(value, "%.2f");
    }

    public static LangBuilder kilopixelNewton(final double value, final String format) {
        return getPrefixedUnit("pn", value, format);
    }

    private static LangBuilder getPrefixedUnit(final String unit, double value, final String format) {
        final String[] prefixes = {"k", "m", "g"};
        int index = 0;
        while (value >= 1000 && index < prefixes.length - 1) {
            value /= 1000;
            index++;
        }

        return translate("unit." + prefixes[index] + unit, format.formatted(value));
    }


    public static void registrateLang(final RegistrateLangProvider provider) {
        final BiConsumer<String, String> consumer = provider::add;

        final Map<String, String> lang = getLangMap("en_us");
        lang.forEach(consumer);

        OffroadAdvancements.provideLang(consumer);
        OffroadSoundEvents.REGISTRY.provideLang(consumer);

        PonderIndex.addPlugin(new OffroadPonderPlugin());
        PonderIndex.getLangAccess().provideLang(Offroad.MOD_ID, consumer);
    }

    private static Map<String, String> getLangMap(final String lang) {
        final String filepath = "datagen/lang/%s.json".formatted(lang);
        final JsonObject langObject = FilesHelper.loadJsonResource(filepath).getAsJsonObject();

        final Map<String, String> langMap = new HashMap<>();
        flattenJson(langMap, langObject, null);
        return langMap;
    }

    private static void flattenJson(final Map<String, String> outputMap, final JsonElement element, final String currentPath) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            final String string = element.getAsJsonPrimitive().getAsString();
            outputMap.put(currentPath, string);
            return;
        }

        if (element.isJsonObject()) {
            final JsonObject object = element.getAsJsonObject();
            for (final String key : object.keySet()) {
                final JsonElement value = object.get(key);
                final String path = currentPath != null ? currentPath + "." + key : key;
                flattenJson(outputMap, value, path);
            }
        }
    }
}
