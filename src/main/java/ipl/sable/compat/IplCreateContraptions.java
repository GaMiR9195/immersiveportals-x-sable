package ipl.sable.compat;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * Create contraptions as entities carried by bodies. Create is optional: every method answers
 * for any entity and touches Create's classes only when it is loaded.
 */
public final class IplCreateContraptions {

	private IplCreateContraptions() {}

	private static final class Create {
		static final boolean LOADED = ModList.get().isLoaded("create");
	}

	/** Whether {@code entity} is a Create contraption. */
	public static boolean isContraption(Entity entity) {
		return Create.LOADED && Impl.isContraption(entity);
	}

	/**
	 * The centre of the block a contraption hangs on (a bearing, piston or pulley; otherwise its
	 * anchor block), in the coordinates of its level; null for anything else.
	 */
	@Nullable
	public static Vec3 holderPoint(Entity entity) {
		return Create.LOADED ? Impl.holderPoint(entity) : null;
	}

	/** After a plot-slot change, moves the absolute block references of a contraption by {@code (dx, dz)}. */
	public static void shiftReferences(Entity entity, int dx, int dz) {
		if (Create.LOADED) Impl.shiftReferences(entity, dx, dz);
	}

	/** The contraption seat {@code passenger} sits on, or null (not a contraption, no seat). */
	@Nullable
	public static Integer seatOf(Entity vehicle, Entity passenger) {
		return Create.LOADED ? Impl.seatOf(vehicle, passenger) : null;
	}

	/** Gives {@code passenger} its seat on contraption {@code vehicle} again, before it mounts. */
	public static void restoreSeat(Entity vehicle, Entity passenger, int seat) {
		if (Create.LOADED) Impl.restoreSeat(vehicle, passenger, seat);
	}

	private static final class Impl {
		static boolean isContraption(Entity entity) {
			return entity instanceof AbstractContraptionEntity;
		}

		@Nullable
		static Vec3 holderPoint(Entity entity) {
			if (entity instanceof ControlledContraptionEntity controlled) {
				BlockPos controller = ((ControlledContraptionAccess) controlled).ipl$controllerPos();
				if (controller != null) return Vec3.atCenterOf(controller);
			}
			if (entity instanceof AbstractContraptionEntity contraption) {
				Contraption c = contraption.getContraption();
				if (c != null && c.anchor != null) return Vec3.atCenterOf(c.anchor);
				return contraption.getAnchorVec().add(0.5, 0.5, 0.5);
			}
			return null;
		}

		static void shiftReferences(Entity entity, int dx, int dz) {
			if (!(entity instanceof AbstractContraptionEntity contraption)) return;
			Contraption c = contraption.getContraption();
			if (c != null && c.anchor != null) c.anchor = c.anchor.offset(dx, 0, dz);
		}

		@Nullable
		static Integer seatOf(Entity vehicle, Entity passenger) {
			if (!(vehicle instanceof AbstractContraptionEntity contraption) || contraption.getContraption() == null) return null;
			return contraption.getContraption().getSeatMapping().get(passenger.getUUID());
		}

		static void restoreSeat(Entity vehicle, Entity passenger, int seat) {
			if (vehicle instanceof AbstractContraptionEntity contraption && contraption.getContraption() != null) {
				contraption.getContraption().getSeatMapping().put(passenger.getUUID(), seat);
			}
		}
	}

	/** Implemented by {@code ControlledContraptionEntityMixin}. */
	public interface ControlledContraptionAccess {
		@Nullable
		BlockPos ipl$controllerPos();
	}
}
