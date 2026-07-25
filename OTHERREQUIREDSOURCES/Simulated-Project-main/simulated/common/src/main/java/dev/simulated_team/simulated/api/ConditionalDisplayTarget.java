package dev.simulated_team.simulated.api;

import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import net.minecraft.network.chat.Component;

public abstract class ConditionalDisplayTarget extends DisplayTarget {
    public abstract boolean allowsWriting(final DisplayLinkContext context);
    public abstract Component getErrorMessage(final DisplayLinkContext context);
}
