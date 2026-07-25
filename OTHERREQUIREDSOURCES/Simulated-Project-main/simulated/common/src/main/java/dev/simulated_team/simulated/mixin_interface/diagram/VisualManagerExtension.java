package dev.simulated_team.simulated.mixin_interface.diagram;

import dev.ryanhcode.sable.neoforge.mixinhelper.compatibility.flywheel.SubLevelEmbedding;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;

public interface VisualManagerExtension {

    SubLevelEmbedding sable$getBEEmbeddingInfo(ClientSubLevel subLevel);
}
