package ipl.sable.mixin;

import ipl.sable.transit.IplEntityFrames;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes the position of a move packet to {@link IplEntityFrames#decodeIncomingPosition}. */
@Mixin(ServerboundMovePlayerPacket.class)
public abstract class IplMovePlayerPacketAccessor implements IplEntityFrames.MovePacketPosition {

    @Mutable @Shadow @Final protected double x;
    @Mutable @Shadow @Final protected double y;
    @Mutable @Shadow @Final protected double z;
    @Shadow @Final protected boolean hasPos;

    @Override
    public boolean ipl$hasPos() {
        return this.hasPos;
    }

    @Override
    public double ipl$x() {
        return this.x;
    }

    @Override
    public double ipl$y() {
        return this.y;
    }

    @Override
    public double ipl$z() {
        return this.z;
    }

    @Override
    public void ipl$setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
