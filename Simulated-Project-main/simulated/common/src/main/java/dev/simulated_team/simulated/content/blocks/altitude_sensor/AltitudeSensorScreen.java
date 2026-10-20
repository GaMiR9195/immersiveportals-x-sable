package dev.simulated_team.simulated.content.blocks.altitude_sensor;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.simulated_team.simulated.data.SimLang;
import dev.simulated_team.simulated.index.SimGUITextures;
import dev.simulated_team.simulated.network.packets.ConfigureAltitudeSensorPacket;
import dev.simulated_team.simulated.util.SimColors;
import foundry.veil.api.network.VeilPacketManager;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.gui.AbstractSimiScreen;
import net.createmod.catnip.gui.ScreenOpener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

public class AltitudeSensorScreen extends AbstractSimiScreen {
    private static final SimGUITextures BACKGROUND = SimGUITextures.ALTITUDE_SENSOR;
    private static final SimGUITextures BAR = SimGUITextures.ALTITUDE_SENSOR_BAR_LIT;
    private static final SimGUITextures GRABBY = SimGUITextures.ALTITUDE_SENSOR_GRABBY_THING;

    private final AltitudeSensorBlockEntity blockEntity;
    private final LerpedFloat visualHighSignal;
    private final LerpedFloat visualLowSignal;
    private final float lerpSpeed = 0.85f;
    private final int barCenterWidth = 8;
    private final int barWidth = 13;
    private final int barHeight = 200;
    private int barLeft = this.guiLeft + 3;
    private int barTop = this.guiTop + 3;
    private int rightBarLeft = this.guiLeft + 28;
    private int soundStep;
    private int ticksOpen;
    private float highSignal;
    private float lowSignal;

    boolean dragging;
    boolean draggingLeft;
    boolean draggingRight;

    public AltitudeSensorScreen(final AltitudeSensorBlockEntity blockEntity) {
        super(SimLang.translate("gui.altitude_sensor.title").component());
        this.blockEntity = blockEntity;
        this.highSignal = blockEntity.highSignal;
        this.lowSignal = blockEntity.lowSignal;
        this.visualHighSignal = LerpedFloat.linear().startWithValue(this.highSignal);
        this.visualLowSignal = LerpedFloat.linear().startWithValue(this.lowSignal);
    }

    public static void open(final AltitudeSensorBlockEntity blockEntity) {
        ScreenOpener.open(new AltitudeSensorScreen(blockEntity));
    }

    @Override
    protected void init() {
        super.init();
        this.guiLeft -= BACKGROUND.width / 2;
        this.guiTop -= BACKGROUND.height / 2;
        this.barLeft = this.guiLeft + 3;
        this.barTop = this.guiTop + 3;
        this.rightBarLeft = this.guiLeft + 28;
    }

    @Override
    public void renderBackground(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        final int a = ((int) (0x50 * Math.min(1, (this.ticksOpen + AnimationTickHolder.getPartialTicks()) / 20f))) << 24;
        graphics.fillGradient(0, 0, this.width, this.height, 0x101010 | a, 0x101010 | a);

        BACKGROUND.render(graphics, this.guiLeft, this.guiTop);
    }

    @Override
    protected void renderWindow(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTicks) {

        final float visualHighPT = this.visualHighSignal.getValue(partialTicks);
        final float visualLowPT = this.visualLowSignal.getValue(partialTicks);
        final float invHighSignal = 1.0f - visualHighPT;
        final float invLowSignal = 1.0f - visualLowPT;

        final int middleBarWidth = 10;
        final int x = this.width / 2 - middleBarWidth / 2;
        final int y = this.height / 2 - this.barHeight / 2;
        final int highMax = (int) (visualHighPT * this.barHeight);
        final int lowMax = (int) (visualLowPT * this.barHeight);

        if (this.lowSignal > this.highSignal) {
            graphics.blit(BAR.location, x, y + BAR.height - highMax, BAR.startX, BAR.height - highMax - BAR.startY, BAR.width, BAR.height - (BAR.height - highMax));
        } else {
            graphics.blit(BAR.location, x, y, BAR.startX, BAR.startY, BAR.width, BAR.height - highMax);
        }

        final PoseStack ps = graphics.pose();

        BAR.bind();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        final Tesselator tesselator = Tesselator.getInstance();
        final BufferBuilder bufferbuilder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        final float imageSize = 256f;
        final float uvx1 = BAR.startX / imageSize;
        final float uvx2 = (BAR.startX + BAR.width) / imageSize;
        final float uvy1 = (BAR.startY + highMax) / imageSize;
        final float uvy2 = (BAR.startY + lowMax) / imageSize;

        final float px1 = (float) x;
        final float px2 = (float) x + BAR.width;
        final float py1 = (y - highMax) + BAR.height;
        final float py2 = (y - lowMax) + BAR.height;

        bufferbuilder.addVertex(ps.last().pose(), px2, py1, 0.0f).setUv(uvx2, uvy1).setColor(1.0f, 1.0f, 1.0f, 1.0f);
        bufferbuilder.addVertex(ps.last().pose(), px1, py1, 0.0f).setUv(uvx1, uvy1).setColor(1.0f, 1.0f, 1.0f, 1.0f);
        bufferbuilder.addVertex(ps.last().pose(), px1, py2, 0.0f).setUv(uvx1, uvy2).setColor(1.0f, 1.0f, 1.0f, 0.0f);
        bufferbuilder.addVertex(ps.last().pose(), px2, py2, 0.0f).setUv(uvx2, uvy2).setColor(1.0f, 1.0f, 1.0f, 0.0f);
        BufferUploader.drawWithShader(bufferbuilder.buildOrThrow());

        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        final int invHighMax = (int) (invHighSignal * this.barHeight);
        final int invLowMax = (int) (invLowSignal * this.barHeight);

        GRABBY.render(graphics, this.guiLeft - this.barWidth / 2, (this.barTop - GRABBY.height / 2) + invLowMax);
        GRABBY.render(graphics, this.rightBarLeft - this.barWidth / 2, (this.barTop - GRABBY.height / 2) + invHighMax);

        final int worldHigh = (int) this.blockEntity.toWorldHeight(this.highSignal);
        final int worldLow = (int) this.blockEntity.toWorldHeight(this.lowSignal);
        final String lowText = String.valueOf(worldLow);
        final String highText = String.valueOf(worldHigh);
        graphics.drawCenteredString(this.font, lowText, this.barLeft + this.barCenterWidth / 2, (this.barTop - this.font.lineHeight / 2) + invLowMax, this.draggingLeft || this.overGrabby(mouseX, mouseY, true) ? SimColors.OFF_WHITE : SimColors.WOODEN_BROWN);
        graphics.drawCenteredString(this.font, highText, this.rightBarLeft + this.barWidth / 2 + 1, (this.barTop - this.font.lineHeight / 2) + invHighMax, this.draggingRight || this.overGrabby(mouseX, mouseY, false) ? SimColors.OFF_WHITE : SimColors.WOODEN_BROWN);

        final int textWidth = this.font.width(this.title);
        final int textX = this.guiLeft - textWidth / 2 - 10;
        graphics.drawCenteredString(this.font, this.title, textX, this.height / 2 - this.font.lineHeight, SimColors.OFF_WHITE);
    }

    private boolean overBar(final double mouseX, final double mouseY, final boolean left) {
        final int x = left ? this.guiLeft : this.rightBarLeft + 1;
        final int y = this.barTop;

        return mouseX > x && mouseX < x + this.barWidth &&
                mouseY > y && mouseY < y + this.barHeight;
    }

    private boolean overGrabby(final double mouseX, final double mouseY, final boolean left) {
        final float visualHighPT = this.visualHighSignal.getValue(0);
        final float visualLowPT = this.visualLowSignal.getValue(0);
        final float invHighSignal = 1.0f - visualHighPT;
        final float invLowSignal = 1.0f - visualLowPT;

        final int invHighMax = (int) (invHighSignal * this.barHeight);
        final int invLowMax = (int) (invLowSignal * this.barHeight);

        final int x = (left ? this.barLeft : this.rightBarLeft) - this.barWidth / 2;
        final int y = (this.barTop - GRABBY.height / 2) + (left ? invLowMax : invHighMax);

        return mouseX > x && mouseX < x + GRABBY.width &&
                mouseY > y && mouseY < y + GRABBY.height;
    }

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        this.draggingLeft = this.overGrabby(mouseX, mouseY, true);
        this.draggingRight = this.overGrabby(mouseX, mouseY, false);

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void mouseMoved(final double mouseX, final double mouseY) {
        if (this.draggingLeft || this.draggingRight) {
            this.updateValues(mouseX, mouseY);
        }
        super.mouseMoved(mouseX, mouseY);
    }

    private void updateValues(final double mouseX, final double mouseY) {
        final int barTop = this.guiTop + 3;
        final int barHeight = 200;

        final float mouseProgress = (float) Mth.clamp(1.0 - (mouseY - barTop) / barHeight, 0.0f, 1.0f);

        final float change;
        if (hasControlDown()) {
            change = this.draggingLeft ? mouseProgress - this.lowSignal : mouseProgress - this.highSignal;
        } else {
            change = 0;
        }

        if (this.outOfBounds(this.lowSignal + change) || this.outOfBounds(this.highSignal + change)) {
            return;
        }

        if(this.draggingLeft) {
            this.lowSignal = mouseProgress;
            this.highSignal += change;
        } else if(this.draggingRight) {
            this.highSignal = mouseProgress;
            this.lowSignal += change;
        }

        this.visualHighSignal.chase(this.highSignal, this.lerpSpeed, LerpedFloat.Chaser.EXP);
        this.visualLowSignal.chase(this.lowSignal, this.lerpSpeed, LerpedFloat.Chaser.EXP);

        final int soundSteps = 15;
        final double newSoundStep = Math.floor(mouseProgress * soundSteps);
        if (newSoundStep != this.soundStep) {
            this.soundStep = (int) newSoundStep;
            Minecraft.getInstance().player.playSound(SoundEvents.LEVER_CLICK, 0.2f, 0.25f + (mouseProgress * 0.5f));
        }

    }

    public boolean outOfBounds(final float value) {
        return value < 0 || value > 1;
    }

    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        this.draggingLeft = false;
        this.draggingRight = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void tick() {
        this.ticksOpen++;

        this.visualHighSignal.tickChaser();
        this.visualLowSignal.tickChaser();
    }

    @Override
    public void onClose() {
        VeilPacketManager.server().sendPacket(
                new ConfigureAltitudeSensorPacket(this.blockEntity.getBlockPos(), this.highSignal, this.lowSignal)
        );
        super.onClose();
    }
}
