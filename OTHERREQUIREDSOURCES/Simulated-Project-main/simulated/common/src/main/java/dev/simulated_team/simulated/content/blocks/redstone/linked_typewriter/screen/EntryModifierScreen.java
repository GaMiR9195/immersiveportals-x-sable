package dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.widget.IconButton;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterEntries;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.screen.widgets.ConfirmationWidgetBase;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.screen.widgets.PromptWidget;
import dev.simulated_team.simulated.data.SimLang;
import dev.simulated_team.simulated.index.SimGUITextures;
import dev.simulated_team.simulated.network.packets.linked_typewriter.TypewriterMenuModifySlots;
import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public class EntryModifierScreen {

    public static final SimGUITextures MODIFICATION_MENU = SimGUITextures.LINKED_TYPEWRITER_KEY_MODIFICATION_MENU;
    public static final SimGUITextures MODIFICATION_ENTRY = SimGUITextures.LINKED_TYPEWRITER_BIND;

    public final LinkedTypewriterScreen parentScreen;

    private Consumer<LinkedTypewriterEntries.KeyboardEntry> finishedEntryCallback;

    public PsuedoKeyboardEntry psuedoEntry = null;
    public boolean modifying = false;

    public PromptWidget promptWidget;
    public IconButton confirmationWidget;

    public ConfirmationWidgetBase cancelEntryWidget;

    public EntryModifierScreen(final LinkedTypewriterScreen screen) {
        this.parentScreen = screen;
    }

    public void init() {
        this.promptWidget = new PromptWidget(this, 0, 0, 68, 16);
        this.cancelEntryWidget = new ConfirmationWidgetBase(0, 0, AllIcons.I_TRASH)
                .withMessage(SimLang.translate("linked_typewriter.delete.key").component())
                .withCallback(this::finishWithoutEntry);

        this.confirmationWidget = new IconButton(0, 0, AllIcons.I_CONFIRM).withCallback(() -> {
            if (this.psuedoEntry != null) {
                this.psuedoEntry.finishModifications();
            }
        });


        //central method for setting XY positions
        this.resetXYPositions();
    }

    public void resetXYPositions() {
        final int widgetHeight = this.getCenterHeight() + 32;

        this.promptWidget.setX(this.getCenterWidth() + 19);
        this.promptWidget.setY(widgetHeight);

        this.confirmationWidget.setX(this.getCenterWidth() + MODIFICATION_ENTRY.width - 56);
        this.confirmationWidget.setY(widgetHeight - 1);

        this.cancelEntryWidget.setX(this.getCenterWidth() + MODIFICATION_ENTRY.width - 33);
        this.cancelEntryWidget.setY(widgetHeight - 1);
    }

    public PsuedoKeyboardEntry startModifying(@Nullable final LinkedTypewriterEntries.KeyboardEntry toModify, final Consumer<LinkedTypewriterEntries.KeyboardEntry> onFinish) {
        final PsuedoKeyboardEntry psuedoEntry = new PsuedoKeyboardEntry();
        if (toModify != null) {
            psuedoEntry.keyCode(toModify.glfwKeyCode)
                    .first(toModify.getFirst())
                    .second(toModify.getSecond());

            this.parentScreen.getNewEntries().getKeyMap().remove(toModify.glfwKeyCode);
        } else {
            psuedoEntry.first = RedstoneLinkNetworkHandler.Frequency.EMPTY;
            psuedoEntry.second = RedstoneLinkNetworkHandler.Frequency.EMPTY;
        }

        this.parentScreen.addWidget(this.cancelEntryWidget);
        this.parentScreen.addWidget(this.confirmationWidget);
        this.parentScreen.addWidget(this.promptWidget);

        this.finishedEntryCallback = onFinish;
        this.modifying = true;
        this.psuedoEntry = psuedoEntry;

        final LinkedTypewriterMenuCommon menu = this.parentScreen.getMenu();
        menu.slotsActive = true;
        final ItemStack first = psuedoEntry.first.getStack();
        final ItemStack second = psuedoEntry.second.getStack();
        menu.ghostInventory.setStackInSlot(0, first);
        menu.ghostInventory.setStackInSlot(1, second);
        VeilPacketManager.server().sendPacket(new TypewriterMenuModifySlots(first, second));

        return psuedoEntry;
    }

    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY, final float pt, final PoseStack ps) {
        if (this.modifying) {
            ps.pushPose();

            this.confirmationWidget.render(guiGraphics, mouseX, mouseY, pt);
            this.promptWidget.render(guiGraphics, mouseX, mouseY, pt);
            this.cancelEntryWidget.render(guiGraphics, mouseX, mouseY, pt);

            ps.popPose();
        }
    }

    public void renderBG(final GuiGraphics guiGraphics) {
        final PoseStack ps = guiGraphics.pose();

        ps.pushPose();
        guiGraphics.fillGradient(0, 0, this.parentScreen.width, this.parentScreen.height, 1, -1072689136, -804253680);

        ps.translate(0, 0, 2);
        MODIFICATION_MENU.render(guiGraphics, this.getCenterWidth(), this.getCenterHeight());
        this.parentScreen.renderPlayerInventory(guiGraphics, this.getCenterWidth() + 19, this.getCenterHeight() + (18 * 4));
        ps.popPose();
    }

    public int getCenterWidth() {
        return this.parentScreen.getLeftPos() + 11;
    }

    public int getCenterHeight() {
        return this.parentScreen.getTopPos() - 31;
    }

    public void finishWithoutEntry() {
        this.finishedEntryCallback.accept(null);
        this.disable();
    }

    public void disable() {
        final LinkedTypewriterMenuCommon menu = this.parentScreen.getMenu();

        this.modifying = false;
        this.psuedoEntry = null;

        this.parentScreen.removeWidget(this.cancelEntryWidget);
        this.parentScreen.removeWidget(this.promptWidget);
        this.parentScreen.removeWidget(this.confirmationWidget);

        this.finishedEntryCallback = null;
        menu.slotsActive = false;
        menu.ghostInventory.setStackInSlot(0, ItemStack.EMPTY);
        menu.ghostInventory.setStackInSlot(1, ItemStack.EMPTY);
        VeilPacketManager.server().sendPacket(new TypewriterMenuModifySlots(ItemStack.EMPTY, ItemStack.EMPTY));
    }

    public class PsuedoKeyboardEntry {
        public int glfwKeyCode = -1;
        private RedstoneLinkNetworkHandler.Frequency first;
        private RedstoneLinkNetworkHandler.Frequency second;

        public PsuedoKeyboardEntry keyCode(final int newCode) {
            this.glfwKeyCode = newCode;
            return this;
        }

        public PsuedoKeyboardEntry first(final RedstoneLinkNetworkHandler.Frequency newFrequency) {
            this.first = newFrequency;
            return this;
        }

        public PsuedoKeyboardEntry second(final RedstoneLinkNetworkHandler.Frequency newFrequency) {
            this.second = newFrequency;
            return this;
        }

        public void finishModifications() {
            if (this.glfwKeyCode != -1) {
                this.first(RedstoneLinkNetworkHandler.Frequency.of(EntryModifierScreen.this.parentScreen.getMenu().ghostInventory.getStackInSlot(0)));
                this.second(RedstoneLinkNetworkHandler.Frequency.of(EntryModifierScreen.this.parentScreen.getMenu().ghostInventory.getStackInSlot(1)));

                final LinkedTypewriterEntries.KeyboardEntry entry = new LinkedTypewriterEntries.KeyboardEntry(EntryModifierScreen.this.psuedoEntry.first,
                        EntryModifierScreen.this.psuedoEntry.second,
                        EntryModifierScreen.this.psuedoEntry.glfwKeyCode,
                        EntryModifierScreen.this.parentScreen.clientBe.getBlockPos());

                EntryModifierScreen.this.finishedEntryCallback.accept(entry);
            } else {
                EntryModifierScreen.this.finishWithoutEntry();
            }

            EntryModifierScreen.this.disable();
        }
    }

}
