package dev.ryanhcode.offroad.index;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.ryanhcode.offroad.Offroad;

public class OffroadPartialModels {

	public static final PartialModel
			DIODE_LEFT = block("wheel_mount/diode_left"),
			DIODE_RIGHT = block("wheel_mount/diode_right"),
			TELE_OUTER = block("wheel_mount/tele_outer"),
			TELE_INNER = block("wheel_mount/tele_inner"),
			TELE_MOUNT = block("wheel_mount/mount"),
			SPRING_UPPER = block("wheel_mount/spring_upper"),
			SPRING_MIDDLE = block("wheel_mount/spring_middle"),
			SPRING_LOWER = block("wheel_mount/spring_lower"),
			ROCK_CUTTING_WHEEL_WHEEL = block("rockcutting_wheel/wheel");

	private static PartialModel block(final String path) {
		return PartialModel.of(Offroad.path("block/" + path));
	}
	private static PartialModel entity(final String path) {
		return PartialModel.of(Offroad.path("entity/" + path));
	}
	private static PartialModel item(final String path) {
		return PartialModel.of(Offroad.path("item/" + path));
	}

	public static void init() {
	}
}
