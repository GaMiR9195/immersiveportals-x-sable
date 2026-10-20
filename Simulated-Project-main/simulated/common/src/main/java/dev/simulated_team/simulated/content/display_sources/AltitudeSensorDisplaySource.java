package dev.simulated_team.simulated.content.display_sources;

import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.NumericSingleLineDisplaySource;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;
import dev.simulated_team.simulated.content.blocks.altitude_sensor.AltitudeSensorBlockEntity;
import dev.simulated_team.simulated.data.SimLang;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public class AltitudeSensorDisplaySource extends NumericSingleLineDisplaySource {

    @Override
    protected MutableComponent provideLine(final DisplayLinkContext context, final DisplayTargetStats stats) {
        if (!(context.getSourceBlockEntity() instanceof final AltitudeSensorBlockEntity be)) {
            return ZERO.copy();
        }

        switch (context.sourceConfig().getInt("AltitudeSensorSelection")) {
            case 0 -> {
                assert be.hasLevel();
                final float airPressure = (float) be.getAirPressure() * 100.0f;
                return Component.literal(String.format("%.2f%%", airPressure));
            }
            case 1 -> {
                return Component.literal(String.format("%.2f", be.getWorldHeight()));
            }
        }
        return EMPTY_LINE.copy();
    }

    @Override
    public void initConfigurationWidgets(final DisplayLinkContext context, final ModularGuiLineBuilder builder, final boolean isFirstLine) {
        super.initConfigurationWidgets(context, builder, isFirstLine);
        if (isFirstLine) {
            return;
        }

        builder.addSelectionScrollInput(0, 95, (selectionScrollInput, label) -> {
            selectionScrollInput.forOptions(SimLang.translatedOptions("display_source.altitude_sensor", "air_pressure", "height"));
        }, "AltitudeSensorSelection");
    }

    @Override
    protected String getTranslationKey() {
        return "altitude_sensor.data";
    }

    @Override
    protected boolean allowsLabeling(final DisplayLinkContext context) {
        return true;
    }
}
