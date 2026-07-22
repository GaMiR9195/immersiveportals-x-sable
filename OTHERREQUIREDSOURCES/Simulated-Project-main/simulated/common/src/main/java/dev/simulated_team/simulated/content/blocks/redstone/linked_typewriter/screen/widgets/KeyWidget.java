package dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.screen.widgets;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterEntries;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.screen.LinkedTypewriterScreen;
import dev.simulated_team.simulated.index.SimGUITextures;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.gui.element.ScreenElement;
import net.createmod.catnip.gui.widget.AbstractSimiWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

public class KeyWidget extends AbstractSimiWidget {

    private final LinkedTypewriterEntries.KeyboardEntry EMPTY = new LinkedTypewriterEntries.KeyboardEntry(RedstoneLinkNetworkHandler.Frequency.EMPTY, RedstoneLinkNetworkHandler.Frequency.EMPTY, this.keyNum, BlockPos.ZERO);

    public int keyNum;

    public boolean bound = false;
    public boolean keyboardActive;

    private final ScreenElement icon;

    private final LinkedTypewriterScreen screen;

    public KeyWidget(final int pX, final int pY, final int pWidth, final int key, final ScreenElement keyIcon, final LinkedTypewriterScreen screen) {
        super(pX, pY, pWidth, 14, Component.empty());
        this.keyNum = key;
        this.icon = keyIcon;

        this.screen = screen;
    }

    public void render(final GuiGraphics pGuiGraphics, final int x, final int y, final int pMouseX, final int pMouseY, final float pPartialTick, final boolean keyboardActive) {
        this.bound = this.screen.getNewEntries().getKeyMap().containsKey(this.keyNum);

        this.setX(x);
        this.setY(y);
        this.keyboardActive = keyboardActive;
        this.render(pGuiGraphics, pMouseX, pMouseY, pPartialTick);
    }

    @Override
    public void renderWidget(@NotNull final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTicks) {
        final SimGUITextures start = this.bound ? SimGUITextures.KEY_START : SimGUITextures.INACTIVE_KEY_START;
        final SimGUITextures middle = this.bound ? SimGUITextures.KEY_MIDDLE : SimGUITextures.INACTIVE_KEY_MIDDLE;
        final SimGUITextures end = this.bound ? SimGUITextures.KEY_END : SimGUITextures.INACTIVE_KEY_END;

        final int midWidth = this.width - start.width - end.width;
        final int endX = start.width + midWidth;
        final boolean mouseHover = this.isMouseOver(mouseX, mouseY);
        final int y = this.getY() + (mouseHover && this.keyboardActive ? 2 : 0);

        start.render(graphics, this.getX(), y);

        for (int i = 0; i < midWidth / 2; i++) {
            middle.render(graphics, this.getX() + start.width + i * 2, y);
        }

        end.render(graphics, this.getX() + endX, y);

        if (this.icon != null) {
            if (this.bound) RenderSystem.setShaderColor(0.447f, 0.278f, 0.192f, 1.0f);
                else RenderSystem.setShaderColor(0.318f, 0.125f, 0.094f, 1.0f);
            this.icon.render(graphics, this.getX() + 3, y + 4);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }

        if (this.isHovered) {
            this.renderHover(graphics, mouseX, mouseY, partialTicks);
        }
    }

    protected void renderHover(@NotNull final GuiGraphics pGuiGraphics, final int pMouseX, final int pMouseY, final float pPartialTick) {
        LinkedTypewriterEntries.KeyboardEntry keyboardEntry = this.screen.getNewEntries().getEntry(this.keyNum);
        if (keyboardEntry == null) {
            keyboardEntry = this.EMPTY;
        }

        if (this.keyboardActive) {
            final SimGUITextures arrow = SimGUITextures.LINKED_TYPEWRITER_TOOLTIP_ARROW;
            final SimGUITextures freq = SimGUITextures.LINKED_TYPEWRITER_FREQUENCY;

            final Component keyName = this.keyName();
            final int textLength = Minecraft.getInstance().font.width(keyName.getString());
            final int textHeight = Minecraft.getInstance().font.lineHeight;

            final int freqWidth = 20;
            final int freqHeight = 20;

            final int minWidth = arrow.width + 4 + freqWidth;
            final int bgWidth = Math.max(minWidth, textLength + 8);
            final int bgHeight = textHeight + 12 + freqHeight;
            final int yOffset = 8;

            final int textX = (this.width / 2) - (textLength / 2) + 1;
            final int bgX = (this.width / 2) - (bgWidth / 2);
            final int arrowX = (this.width / 2) - (arrow.width / 2);
            final int freqX = (this.width / 2) - (freq.width / 2);

            this.renderBackground(pGuiGraphics, this.getX() + bgX, this.getY() - bgHeight - yOffset, bgWidth, bgHeight);
            arrow.render(pGuiGraphics, this.getX() + arrowX, this.getY() - yOffset - 2);

            freq.render(pGuiGraphics, this.getX() + freqX, this.getY() - yOffset - bgHeight + 4);

            final Couple<RedstoneLinkNetworkHandler.Frequency> coupled = keyboardEntry.getAsCouple();
            pGuiGraphics.renderItem(coupled.getFirst().getStack(), this.getX() + freqX + 1, this.getY() - yOffset - bgHeight + 5);
            pGuiGraphics.renderItem(coupled.getSecond().getStack(), this.getX() + freqX + 19, this.getY() - yOffset - bgHeight + 5);

            pGuiGraphics.drawString(Minecraft.getInstance().font, keyName.getString(), this.getX() + textX - 1, this.getY() - yOffset - textHeight - 2, DyeColor.BLACK.getTextColor(), false);
        }
    }

    private Component keyName() {
        return InputConstants.getKey(this.keyNum, GLFW.glfwGetKeyScancode(this.keyNum))
                .getDisplayName();
    }

    private void renderBackground(@NotNull final GuiGraphics pGuiGraphics, final int x, final int y, final int w, final int h) {
        final SimGUITextures bg = SimGUITextures.LINKED_TYPEWRITER_TOOLTIP_BACKGROUND;
        pGuiGraphics.blitSprite(bg.location, x, y, 0, w, h);
    }
}