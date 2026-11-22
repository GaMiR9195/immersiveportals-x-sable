package ipl.sable.transit;

import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import ipl.sable.compat.IplCreateContraptions;
import ipl.sable.natives.IplRapierNatives;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;

/**
 * Where kinematic contraptions hang on their bodies. The natives give a contraption mounted on a
 * straddling body an image with every image of the body and walk its pieces from this point
 * (natives ABI {@link IplRapierNatives#CONTRAPTION_IMAGE_ABI}): what passed a portal collides
 * beyond it, what did not stays, like the body's own blocks.
 */
public final class IplContraptionAnchors {

	private IplContraptionAnchors() {}

	public static void push(long sceneHandle, int id, KinematicContraption contraption) {
		if (sceneHandle == 0 || IplRapierNatives.abiLevel() < IplRapierNatives.CONTRAPTION_IMAGE_ABI) return;
		Vec3 anchor = anchorOf(contraption);
		IplRapierNatives.setContraptionAnchor(sceneHandle, id, anchor.x, anchor.y, anchor.z);
	}

	private static Vec3 anchorOf(KinematicContraption contraption) {
		Vec3 holder = contraption instanceof Entity entity ? IplCreateContraptions.holderPoint(entity) : null;
		if (holder != null) return holder;
		Vector3dc position = contraption.sable$getPosition();
		return new Vec3(position.x(), position.y(), position.z());
	}
}
