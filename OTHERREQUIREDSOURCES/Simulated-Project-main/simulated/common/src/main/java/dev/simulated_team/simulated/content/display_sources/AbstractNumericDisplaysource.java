package dev.simulated_team.simulated.content.display_sources;

import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.NumericSingleLineDisplaySource;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;
import net.minecraft.network.chat.Component;

import java.util.List;

public abstract class AbstractNumericDisplaysource extends NumericSingleLineDisplaySource {

    @Override
    public void initConfigurationWidgets(final DisplayLinkContext context, final ModularGuiLineBuilder builder, final boolean isFirstLine) {
        super.initConfigurationWidgets(context, builder, isFirstLine);
        if (isFirstLine) {
            return;
        }

        builder.addSelectionScrollInput(0, this.getWidth(), ((selectionScrollInput, label) -> selectionScrollInput.forOptions(this.getOptions())), this.getSelectionKey());
    }

    public int getWidth() {
        return 100;
    }

    abstract List<Component> getOptions();
    abstract String getKey();
    abstract String getSelectionKey();

    @Override
    protected String getTranslationKey() {
        return this.getKey();
    }

    @Override
    protected boolean allowsLabeling(final DisplayLinkContext displayLinkContext) {
        return true;
    }
}
