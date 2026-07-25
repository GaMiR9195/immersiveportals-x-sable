package dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.screen.widgets;

import com.simibubi.create.foundation.gui.widget.IconButton;
import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

public class ConfirmationWidgetBase extends IconButton {

    public boolean confirmation;
    public MutableComponent message;

    public ConfirmationWidgetBase(final int x, final int y, final ScreenElement icon) {
        super(x, y, icon);
    }

    public <T extends ConfirmationWidgetBase> T withMessage(final MutableComponent component) {
        this.message = component;
        return (T) this;
    }

    @Override
    public void doRender(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTicks) {
        super.doRender(graphics, mouseX, mouseY, partialTicks);

        if (this.isHovered && this.visible && this.active && this.confirmation) {
            this.renderHoveredText(graphics, mouseX, mouseY);
        }
    }

    public void renderHoveredText(final GuiGraphics graphics, final int mouseX, final int mouseY) {
        graphics.renderComponentTooltip(Minecraft.getInstance().font, List.of(this.message.withColor(0xff0000)), mouseX, mouseY);
    }

    @Override
    protected boolean clicked(final double mouseX, final double mouseY) {
        if (!this.isMouseOver(mouseX, mouseY)) {
            this.confirmation = false;
        }

        return super.clicked(mouseX, mouseY);
    }

    @Override
    public void onClick(final double mouseX, final double mouseY) {
        if (this.confirmation) {
            this.runCallback(mouseX, mouseY);
            this.confirmation = false;
        } else {
            this.confirmation = true;
        }
    }
}
