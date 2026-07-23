package dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.screen;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;
import dev.engine_room.flywheel.lib.transform.PoseTransformStack;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterBlock;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterBlockEntity;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterEntries;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterInteractionHandler;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.screen.widgets.ConfirmationWidgetBase;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.screen.widgets.KeyWidget;
import dev.simulated_team.simulated.data.SimLang;
import dev.simulated_team.simulated.index.SimGUITextures;
import dev.simulated_team.simulated.index.SimIcons;
import dev.simulated_team.simulated.network.packets.linked_typewriter.TypewriterDisconnectUser;
import dev.simulated_team.simulated.network.packets.linked_typewriter.TypewriterKeySavePacket;
import dev.simulated_team.simulated.util.SimColors;
import foundry.veil.api.network.VeilPacketManager;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.joml.Vector2i;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class LinkedTypewriterScreen extends AbstractSimiContainerScreen<LinkedTypewriterMenuCommon> {
    public final LinkedTypewriterBlockEntity clientBe;
    private final LinkedTypewriterEntries newEntries;

    private final KeyEditorScreen keyEditorScreen;
    public final EntryModifierScreen modifier;

    private List<Rect2i> extraAreasMain;
    private final List<Rect2i> emptyExtraAreas = List.of();

    protected SimGUITextures backgroundMain;
    protected SimGUITextures backgroundBind;
    private IconButton mainScreenConfirm;
    public ConfirmationWidgetBase mainScreenResetAll;
    private IconButton mainScreenEditBinding;

    private boolean confirmingReset;

    private KeyRow firstKeyRow;
    private KeyRow secondKeyRow;
    private KeyRow thirdKeyRow;
    private KeyRow fourthKeyRow;
    private KeyRow fifthKeyRow;
    private final List<KeyRow> allKeys = new ArrayList<>();

    public LinkedTypewriterScreen(final LinkedTypewriterMenuCommon container, final Inventory inv, final Component title) {
        super(container, inv, title);

        this.clientBe = container.contentHolder;
        this.newEntries = new LinkedTypewriterEntries();
        this.newEntries.addAll(this.clientBe.getTypewriterEntries().getKeyMap());

//        this.editor2 = new KeyEditorScreen(this);

        this.keyEditorScreen = new KeyEditorScreen(this);
        this.modifier = new EntryModifierScreen(this);
    }

    @Override
    protected void init() {
        this.backgroundMain = SimGUITextures.LINKED_TYPEWRITER_MAIN;
        this.backgroundBind = SimGUITextures.LINKED_TYPEWRITER_BIND;
        this.setWindowSize(this.backgroundMain.width, this.backgroundMain.height);

        super.init();

        this.rebuildExtraAreas();

        final Vector2i pos = new Vector2i(145, 102);
        final int spacing = 8;

        this.firstKeyRow = new KeyRow(pos.x(), pos.y(), this.clientBe);
        this.secondKeyRow = new KeyRow(pos.x(), pos.y() + spacing, this.clientBe);
        this.thirdKeyRow = new KeyRow(pos.x(), pos.y() + spacing * 2, this.clientBe);
        this.fourthKeyRow = new KeyRow(pos.x(), pos.y() + spacing * 3, this.clientBe);
        this.fifthKeyRow = new KeyRow(pos.x(), pos.y() + spacing * 4, this.clientBe);
        this.setRows();

        this.modifier.init();
//        this.editor2.init(); //we no longer init like this...

        //Resets all keys and resets serverside TypeWriter
        this.mainScreenResetAll = new ConfirmationWidgetBase(this.getLeftPos() + 8, this.getTopPos() + this.backgroundMain.height - 24, AllIcons.I_TRASH)
                .withMessage(SimLang.translate("linked_typewriter.confirm_delete_all").component())
                .withCallback(() -> this.sendNewKeys(true));

        //Confirms the current modifications and sends the keys to the server
        this.mainScreenConfirm = new IconButton(this.getLeftPos() + this.backgroundMain.width - 33, this.getTopPos() + this.backgroundMain.height - 24, AllIcons.I_CONFIRM).withCallback(this::onClose);

        //Opens up the binding screen
        this.mainScreenEditBinding = new IconButton(this.getLeftPos() + this.backgroundMain.width - 62, this.getTopPos() + this.backgroundMain.height - 24, SimIcons.HAMBURGER).withCallback(() -> {
            this.switchScreen(true);
        });

        this.addWidget(this.mainScreenResetAll);
        this.addWidget(this.mainScreenConfirm);
        this.addWidget(this.mainScreenEditBinding);

        for (final KeyRow keyRow : this.allKeys) {
            for (final KeyWidget kwid : keyRow) {
                this.addWidget(kwid);
            }
        }
    }

    @Override
    protected void rebuildWidgets() {
        this.clearFocus();

        this.rescaleWindow();
        this.keyEditorScreen.resetPositions();
        this.modifier.resetXYPositions();

        this.setInitialFocus();
    }

    private void rebuildExtraAreas() {
        this.extraAreasMain = ImmutableList.of(this.getTypewriterBlockRect());
    }

    public Rect2i getTypewriterBlockRect() {
        return new Rect2i(this.leftPos + this.backgroundMain.width - 30, this.topPos + this.backgroundMain.height - 30, 94, 94);
    }

    public void rescaleWindow() {
        this.leftPos = (this.width - this.imageWidth) / 2;
        this.topPos = (this.height - this.imageHeight) / 2;

        this.backgroundMain = SimGUITextures.LINKED_TYPEWRITER_MAIN;
        this.backgroundBind = SimGUITextures.LINKED_TYPEWRITER_BIND;
        this.setWindowSize(this.backgroundMain.width, this.backgroundMain.height);

        this.rebuildExtraAreas();

        final int widgetHeight = this.getTopPos() + this.backgroundMain.height - 24;

        this.mainScreenConfirm.setX(this.getLeftPos() + this.backgroundMain.width - 33);
        this.mainScreenConfirm.setY(widgetHeight);


        this.mainScreenEditBinding.setX(this.getLeftPos() + this.backgroundMain.width - 62);
        this.mainScreenEditBinding.setY(widgetHeight);
    }

    /**
     * Switches between the binding screen and the main screen of this typewriter
     */
    public void switchScreen(final boolean subScreen) {
        this.clearWidgets();

        if (subScreen) {
            this.keyEditorScreen.startEditing();
        } else {
            this.keyEditorScreen.endEditing();

            for (final KeyRow keyRow : this.allKeys) {
                for (final KeyWidget kwid : keyRow) {
                    this.addWidget(kwid);
                }
            }

            this.addWidget(this.mainScreenResetAll);
            this.mainScreenResetAll.confirmation = false;
            this.mainScreenResetAll.setX(this.getLeftPos() + 8);
            this.mainScreenResetAll.setY(this.getTopPos() + this.backgroundMain.height - 24);

            this.addWidget(this.mainScreenConfirm);
            this.addWidget(this.mainScreenEditBinding);
        }
    }

    @Override
    public <T extends GuiEventListener & NarratableEntry> T addWidget(final T listener) {
        return super.addWidget(listener);
    }

    @Override
    public void removeWidget(final GuiEventListener listener) {
        super.removeWidget(listener);
    }

    @Override
    public void onClose() {
        if (this.modifier.psuedoEntry != null) {
            this.modifier.psuedoEntry.finishModifications();
        }

        this.sendNewKeys(false);

        LinkedTypewriterInteractionHandler.setMode(LinkedTypewriterInteractionHandler.Mode.IDLE);
        LinkedTypewriterInteractionHandler.associateTypewriter(null);

        VeilPacketManager.server().sendPacket(new TypewriterDisconnectUser(this.clientBe.getBlockPos()));
        super.onClose();
    }

    public void sendNewKeys(final boolean clearServer) {
        if (clearServer) {
            this.newEntries.clearAll();
        }

        // save keys!
        VeilPacketManager.server().sendPacket(new TypewriterKeySavePacket(this.newEntries, this.clientBe.getBlockPos(), clearServer));
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
        if (this.keyEditorScreen.active && !this.modifier.modifying) {
            this.keyEditorScreen.shiftEntries(scrollY > 0);
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void containerTick() {
        super.containerTick();

        if (this.keyEditorScreen.active) {
            this.keyEditorScreen.tick();
        }
    }

    @Override
    public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY, final float pt) {
        final PoseStack ps = guiGraphics.pose();
        ps.pushPose();
        ps.translate(0, 0, -1);

        super.render(guiGraphics, mouseX, mouseY, pt);

        if (this.hoveredSlot != null && this.hoveredSlot.isActive() && this.hoveredSlot.hasItem()) {
//            new ClientTextTooltip(Component.literal("awa").getVisualOrderText()).renderText(this.font, mouseX, mouseY, ps.last().pose(), guiGraphics.bufferSource());
            guiGraphics.renderTooltip(this.font, this.hoveredSlot.getItem(), mouseX, mouseY);
        }

        ps.pushPose();

        if (this.keyEditorScreen.active) {
            this.keyEditorScreen.render(guiGraphics, mouseX, mouseY, pt, ps);
        }

        ps.popPose();
        ps.popPose();
    }

    @Override
    protected void renderForeground(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTicks) {

        final PoseStack ps = graphics.pose();
        ps.pushPose();

        if (this.modifier.modifying) {
            ps.translate(0, 0, 200);
            this.modifier.render(graphics, mouseX, mouseY, partialTicks, ps);
        }
        ps.popPose();
    }

    @Override
    protected void renderBg(final GuiGraphics guiGraphics, final float pt, final int mx, final int my) {
        final int titleX = (this.backgroundMain.width / 2) - (Minecraft.getInstance().font.width(this.getTitle()) / 2);

        if (!this.keyEditorScreen.active) {
            this.backgroundMain.render(guiGraphics, this.getLeftPos(), this.getTopPos());
            guiGraphics.drawString(Minecraft.getInstance().font, this.getTitle(), this.getLeftPos() + titleX, this.getTopPos() + 4, SimColors.TITLE_DARK_RED, false);

            final int x = this.leftPos;
            final int y = this.topPos;

            final int rx = x + 8;
            final int ry = y + 21;


            if (!this.modifier.modifying) {
                this.renderTypeWriter(guiGraphics, x, y);
            }

            int i = 0;
            for (final KeyRow keyRow : this.allKeys) {
                keyRow.render(guiGraphics, rx, ry + i * 14, mx, my, pt, true);
                i++;
            }

            this.mainScreenResetAll.render(guiGraphics, mx, my, pt);
            this.mainScreenConfirm.render(guiGraphics, mx, my, pt);
            this.mainScreenEditBinding.render(guiGraphics, mx, my, pt);
        } else {
            this.keyEditorScreen.renderBG(guiGraphics, pt, mx, my);
        }

        if (this.modifier.modifying) {
            this.modifier.renderBG(guiGraphics);
        }
    }

    private void renderTypeWriter(final GuiGraphics graphics, final int x, final int y) {
        final PoseStack ps = graphics.pose();

        final TransformStack<PoseTransformStack> msr = TransformStack.of(ps);
        ps.pushPose();
        msr.pushPose()
                .translate(x + this.backgroundMain.width + 4, y + this.backgroundMain.height + 4, 100)
                .scale(40)
                .rotateXDegrees(-22)
                .rotateYDegrees(63);

        GuiGameElement.of(this.clientBe.getBlockState().setValue(LinkedTypewriterBlock.HORIZONTAL_FACING, Direction.WEST))
                .render(graphics);

        msr.scale(-1);
        msr.translate(-1, 0, -1);
        msr.rotateCentered((float) -(0.25f * Math.PI * 2.0f), Direction.UP);

        final float yRot = this.clientBe.getBlockState().getValue(LinkedTypewriterBlock.FACING).getOpposite().toYRot();
        msr.rotateCentered((float) Math.toRadians(yRot), Direction.UP);

        Minecraft.getInstance().getBlockEntityRenderDispatcher().renderItem(
                this.clientBe,
                ps,
                graphics.bufferSource(),
                255,
                OverlayTexture.NO_OVERLAY
        );

        msr.popPose();
        ps.popPose();
    }

    private void switchStates(final boolean newState) {
        for (final KeyRow allKey : LinkedTypewriterScreen.this.allKeys) {
            for (final KeyWidget key : allKey) {
                key.setActive(newState);
            }
        }

        LinkedTypewriterScreen.this.mainScreenEditBinding.setActive(newState);
        LinkedTypewriterScreen.this.mainScreenResetAll.setActive(newState);
        LinkedTypewriterScreen.this.mainScreenConfirm.setActive(newState);
    }

    @Override
    public boolean mouseClicked(final double pMouseX, final double pMouseY, final int pButton) {
        final int x = this.leftPos;
        final int y = this.topPos;
        if (this.confirmingReset && !(pMouseX > x + 8 && pMouseX < x + 26 && pMouseY > y + this.backgroundMain.height - 24 && pMouseY < y + this.backgroundMain.height - 6)) {
            this.confirmingReset = false;
        }

        return super.mouseClicked(pMouseX, pMouseY, pButton);
    }

    public LinkedTypewriterEntries getNewEntries() {
        return this.newEntries;
    }

    @Override
    public List<Rect2i> getExtraAreas() {
        if (this.keyEditorScreen.active || this.modifier.modifying) {
            return this.emptyExtraAreas;
        } else {
            return this.extraAreasMain;
        }
    }

    public int getTopPos() {
        return this.topPos;
    }

    public int getLeftPos() {
        return this.leftPos;
    }

    public void setRows() {
        final int standardLength = 14;

        this.firstKeyRow.add(standardLength, GLFW.GLFW_KEY_GRAVE_ACCENT, null);
        this.firstKeyRow.add(standardLength, GLFW.GLFW_KEY_1, null);
        this.firstKeyRow.add(standardLength, GLFW.GLFW_KEY_2, null);
        this.firstKeyRow.add(standardLength, GLFW.GLFW_KEY_3, null);
        this.firstKeyRow.add(standardLength, GLFW.GLFW_KEY_4, null);
        this.firstKeyRow.add(standardLength, GLFW.GLFW_KEY_5, null);
        this.firstKeyRow.add(standardLength, GLFW.GLFW_KEY_6, null);
        this.firstKeyRow.add(standardLength, GLFW.GLFW_KEY_7, null);
        this.firstKeyRow.add(standardLength, GLFW.GLFW_KEY_8, null);
        this.firstKeyRow.add(standardLength, GLFW.GLFW_KEY_9, null);
        this.firstKeyRow.add(standardLength, GLFW.GLFW_KEY_0, null);
        this.firstKeyRow.add(standardLength, GLFW.GLFW_KEY_MINUS, null);
        this.firstKeyRow.add(standardLength, GLFW.GLFW_KEY_EQUAL, null);
        this.firstKeyRow.add(standardLength + 12, GLFW.GLFW_KEY_BACKSPACE, null);
        this.firstKeyRow.add(standardLength, GLFW.GLFW_KEY_DELETE, null);

        this.secondKeyRow.add(standardLength + 6, GLFW.GLFW_KEY_TAB, null);
        this.secondKeyRow.add(standardLength, GLFW.GLFW_KEY_Q, null);
        this.secondKeyRow.add(standardLength, GLFW.GLFW_KEY_W, null);
        this.secondKeyRow.add(standardLength, GLFW.GLFW_KEY_E, null);
        this.secondKeyRow.add(standardLength, GLFW.GLFW_KEY_R, null);
        this.secondKeyRow.add(standardLength, GLFW.GLFW_KEY_T, null);
        this.secondKeyRow.add(standardLength, GLFW.GLFW_KEY_Y, null);
        this.secondKeyRow.add(standardLength, GLFW.GLFW_KEY_U, null);
        this.secondKeyRow.add(standardLength, GLFW.GLFW_KEY_I, null);
        this.secondKeyRow.add(standardLength, GLFW.GLFW_KEY_O, null);
        this.secondKeyRow.add(standardLength, GLFW.GLFW_KEY_P, null);
        this.secondKeyRow.add(standardLength, GLFW.GLFW_KEY_LEFT_BRACKET, null);
        this.secondKeyRow.add(standardLength, GLFW.GLFW_KEY_RIGHT_BRACKET, null);
        this.secondKeyRow.add(standardLength + 6, GLFW.GLFW_KEY_BACKSLASH, null);
        this.secondKeyRow.add(standardLength, GLFW.GLFW_KEY_PAGE_UP, null);

        this.thirdKeyRow.add(standardLength + 12, GLFW.GLFW_KEY_CAPS_LOCK, null);
        this.thirdKeyRow.add(standardLength, GLFW.GLFW_KEY_A, null);
        this.thirdKeyRow.add(standardLength, GLFW.GLFW_KEY_S, null);
        this.thirdKeyRow.add(standardLength, GLFW.GLFW_KEY_D, null);
        this.thirdKeyRow.add(standardLength, GLFW.GLFW_KEY_F, null);
        this.thirdKeyRow.add(standardLength, GLFW.GLFW_KEY_G, null);
        this.thirdKeyRow.add(standardLength, GLFW.GLFW_KEY_H, null);
        this.thirdKeyRow.add(standardLength, GLFW.GLFW_KEY_J, null);
        this.thirdKeyRow.add(standardLength, GLFW.GLFW_KEY_K, null);
        this.thirdKeyRow.add(standardLength, GLFW.GLFW_KEY_L, null);
        this.thirdKeyRow.add(standardLength, GLFW.GLFW_KEY_SEMICOLON, null);
        this.thirdKeyRow.add(standardLength, GLFW.GLFW_KEY_APOSTROPHE, null);
        this.thirdKeyRow.add(standardLength + 14, GLFW.GLFW_KEY_ENTER, null);
        this.thirdKeyRow.add(standardLength, GLFW.GLFW_KEY_PAGE_DOWN, null);

        this.fourthKeyRow.add(standardLength + 18, GLFW.GLFW_KEY_LEFT_SHIFT, null);
        this.fourthKeyRow.add(standardLength, GLFW.GLFW_KEY_Z, null);
        this.fourthKeyRow.add(standardLength, GLFW.GLFW_KEY_X, null);
        this.fourthKeyRow.add(standardLength, GLFW.GLFW_KEY_C, null);
        this.fourthKeyRow.add(standardLength, GLFW.GLFW_KEY_V, null);
        this.fourthKeyRow.add(standardLength, GLFW.GLFW_KEY_B, null);
        this.fourthKeyRow.add(standardLength, GLFW.GLFW_KEY_N, null);
        this.fourthKeyRow.add(standardLength, GLFW.GLFW_KEY_M, null);
        this.fourthKeyRow.add(standardLength, GLFW.GLFW_KEY_COMMA, null);
        this.fourthKeyRow.add(standardLength, GLFW.GLFW_KEY_PERIOD, null);
        this.fourthKeyRow.add(standardLength, GLFW.GLFW_KEY_SLASH, null);
        this.fourthKeyRow.add(standardLength + 8, GLFW.GLFW_KEY_RIGHT_SHIFT, null);
        this.fourthKeyRow.add(standardLength, GLFW.GLFW_KEY_UP, SimIcons.KEY_ARROW_UP);
        this.fourthKeyRow.add(standardLength, GLFW.GLFW_KEY_END, null);

        this.fifthKeyRow.add(standardLength + 4, GLFW.GLFW_KEY_LEFT_CONTROL, null);
        this.fifthKeyRow.add(standardLength, GLFW.GLFW_KEY_LEFT_SUPER, null);
        this.fifthKeyRow.add(standardLength, GLFW.GLFW_KEY_LEFT_ALT, null);
        this.fifthKeyRow.add(standardLength + 74, GLFW.GLFW_KEY_SPACE, null);
        this.fifthKeyRow.add(standardLength, GLFW.GLFW_KEY_RIGHT_ALT, null);
        this.fifthKeyRow.add(standardLength, GLFW.GLFW_KEY_MENU, null);
        this.fifthKeyRow.add(standardLength + 4, GLFW.GLFW_KEY_RIGHT_CONTROL, null);
        this.fifthKeyRow.add(standardLength, GLFW.GLFW_KEY_LEFT, SimIcons.KEY_ARROW_LEFT);
        this.fifthKeyRow.add(standardLength, GLFW.GLFW_KEY_DOWN, SimIcons.KEY_ARROW_DOWN);
        this.fifthKeyRow.add(standardLength, GLFW.GLFW_KEY_RIGHT, SimIcons.KEY_ARROW_RIGHT);

        this.allKeys.clear();
        this.allKeys.add(this.firstKeyRow);
        this.allKeys.add(this.secondKeyRow);
        this.allKeys.add(this.thirdKeyRow);
        this.allKeys.add(this.fourthKeyRow);
        this.allKeys.add(this.fifthKeyRow);
    }

    private class KeyRow extends ArrayList<KeyWidget> {
        Vector2i pos;
        LinkedTypewriterBlockEntity be;

        public KeyRow(final int x, final int y, final LinkedTypewriterBlockEntity be) {
            super();
            this.pos = new Vector2i(x, y);
            this.be = be;
        }

        public void add(final int length, final int glfwKey, final ScreenElement icon) {
            final KeyWidget kWid = new KeyWidget(2, 2, length, glfwKey, icon, LinkedTypewriterScreen.this);

            //whenever the key is pressed, we want to open up the subscreen with the correct index
            kWid.withCallback(() -> {
                LinkedTypewriterScreen.this.switchStates(false);

                LinkedTypewriterScreen.this.modifier.startModifying(LinkedTypewriterScreen.this.newEntries.getEntry(glfwKey), (newEntry) -> {
                    LinkedTypewriterScreen.this.switchStates(true);

                    if (newEntry != null) {
                        LinkedTypewriterScreen.this.getNewEntries().setKey(newEntry.glfwKeyCode, newEntry);
                    }
                }).keyCode(glfwKey);
            });

            this.add(kWid);
        }

        public void render(final GuiGraphics guiGraphics, final int x, final int y, final int mouseX, final int mouseY, final float pt, final boolean keyboardActive) {
            int length = 0;
            for (final KeyWidget key : this) {
                key.render(guiGraphics, x + length, y, mouseX, mouseY, pt, keyboardActive);

                length += key.getWidth();
            }
        }
    }
}