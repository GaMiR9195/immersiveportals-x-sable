package ipl.sable.render.route;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One physical cut: openings that share their rectangle (both faces of a two-sided portal, the
 * exit face of a pair seen through its entry, either face of it) are one plane in route space.
 * A primitive is cut once per such plane, and crossing it is one crossing: the first of its
 * openings that moves the frame decides ({@link #crossedFrom}).
 *
 * @param rect    the rectangle, facing like the first of its openings
 * @param members the openings (1-based indices into the route's opening list), positive when the
 *                opening faces like {@code rect}, negative when it faces the other way
 */
record IplRouteCut(IplRouteRect rect, int[] members) {

	/** Groups {@code openings} by their rectangle, in order of first appearance. */
	static List<IplRouteCut> of(List<IplRouteOpening> openings) {
		List<IplRouteCut> cuts = new ArrayList<>();
		for (int k = 0; k < openings.size(); k++) {
			IplRouteRect rect = openings.get(k).rect();
			int index = -1;
			for (int c = 0; c < cuts.size() && index < 0; c++) if (cuts.get(c).rect.coincides(rect)) index = c;
			if (index < 0) {
				cuts.add(new IplRouteCut(rect, new int[]{k + 1}));
				continue;
			}
			IplRouteCut existing = cuts.get(index);
			int[] members = Arrays.copyOf(existing.members, existing.members.length + 1);
			members[members.length - 1] = existing.rect.normal.dot(rect.normal) > 0.0 ? k + 1 : -(k + 1);
			cuts.set(index, new IplRouteCut(existing.rect, members));
		}
		return cuts;
	}

	/**
	 * The frame after crossing this plane inside its opening, from {@code frame}: front to back of
	 * {@link #rect} when {@code frontToBack}. Each opening is crossed in its own direction; the
	 * first that changes the frame is the crossing.
	 */
	IplRouteFrame crossedFrom(IplRouteFrame frame, boolean frontToBack, List<IplRouteOpening> openings) {
		for (int member : members) {
			IplRouteFrame next = frame.cross(openings.get(Math.abs(member) - 1), frontToBack == member > 0);
			if (next != frame) return next;
		}
		return frame;
	}
}
