package dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.screen.widgets;

import com.mojang.blaze3d.platform.InputConstants;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.screen.EntryModifierScreen;
import dev.simulated_team.simulated.data.SimLang;
import net.createmod.catnip.gui.widget.AbstractSimiWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public class PromptWidget extends AbstractSimiWidget {

    private final EntryModifierScreen entryModifierScreen;
    protected boolean bindingActive = false;

    public PromptWidget(final EntryModifierScreen entryModifierScreen, final int x, final int y, final int width, final int height) {
        super(x, y, width, height);
        this.entryModifierScreen = entryModifierScreen;
    }

    @Override
    protected void doRender(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTicks) {
        super.doRender(graphics, mouseX, mouseY, partialTicks);

        if (this.entryModifierScreen.modifying && this.entryModifierScreen.psuedoEntry != null) {
            Component displayName = InputConstants.getKey(this.entryModifierScreen.psuedoEntry.glfwKeyCode, -1).getDisplayName();

            if (this.bindingActive) {
                displayName = SimLang.translate("linked_typewriter.bind_screen_prompt").component();
            } else if (this.entryModifierScreen.psuedoEntry.glfwKeyCode == -1) {
                displayName = SimLang.translate("linked_typewriter.bind_new_key").component();
            }

            graphics.pose().translate(3, 4, 0);
            graphics.drawString(Minecraft.getInstance().font, displayName, this.getX(), this.getY(), 0xFFFFFF, true);
        }
    }

    @Override
    public void onClick(final double mouseX, final double mouseY) {
        super.onClick(mouseX, mouseY);
        this.bindingActive ^= true;
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (this.bindingActive && this.entryModifierScreen.psuedoEntry != null) {
            this.entryModifierScreen.psuedoEntry.keyCode(keyCode);
            this.bindingActive = false;
            return true;
        }

        this.bindingActive = false;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
