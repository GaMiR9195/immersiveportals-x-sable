package ipl.sable.transit;

import ipl.sable.compat.IplCreateContraptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.ducks.IEServerPlayerEntity;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Moves an entity together with everything riding it into another level. IP's dimension change
 * recreates an entity without its passengers, so a player on a seat, a mob in a boat or anyone
 * seated on a Create contraption would stay behind. Every member of the vehicle tree is placed
 * where {@link Placement} puts it (read before anything moves), and the tree is rebuilt in the
 * destination, contraption seats included.
 */
final class IplRideTransfer {
	private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-flip");

	private IplRideTransfer() {}

	/** Where a member of the tree goes and how it moves there. */
	interface Placement {
		Vec3 position(Entity entity);

		Vec3 motion(Entity entity);
	}

	private record Member(Entity entity, @Nullable Member vehicle, @Nullable Integer seat, Vec3 position, Vec3 motion) {}

	/**
	 * Moves {@code root} and its passengers into {@code to}; {@code onMoved} receives each old
	 * entity and its successor. Returns the moved root, or null when it could not be moved.
	 */
	@Nullable
	static Entity moveTree(Entity root, ServerLevel to, Placement placement, BiConsumer<Entity, Entity> onMoved) {
		List<Member> members = new ArrayList<>();
		collect(root, null, placement, members);
		for (int i = members.size() - 1; i > 0; i--) dismount(members.get(i).entity());
		Map<Member, Entity> moved = new IdentityHashMap<>();
		for (Member member : members) {
			Entity successor = move(member, to);
			if (successor == null) continue;
			moved.put(member, successor);
			onMoved.accept(member.entity(), successor);
			Entity vehicle = member.vehicle() == null ? null : moved.get(member.vehicle());
			if (vehicle != null) mount(successor, vehicle, member.seat());
		}
		return moved.get(members.get(0));
	}

	private static void collect(Entity entity, @Nullable Member vehicle, Placement placement, List<Member> out) {
		Integer seat = vehicle == null ? null : IplCreateContraptions.seatOf(vehicle.entity(), entity);
		Member member = new Member(entity, vehicle, seat, placement.position(entity), placement.motion(entity));
		out.add(member);
		for (Entity passenger : List.copyOf(entity.getPassengers())) collect(passenger, member, placement, out);
	}

	@Nullable
	private static Entity move(Member member, ServerLevel to) {
		Entity entity = member.entity();
		if (entity.isRemoved()) return null;
		try {
			Entity successor = ServerTeleportationManager.teleportEntityGeneral(entity, member.position(), to);
			if (successor == null || successor.isRemoved()) return null;
			successor.setDeltaMovement(member.motion());
			return successor;
		} catch (RuntimeException e) {
			LOG.warn("[IPL-MOVE] failed to move {} into {}", entity, to.dimension().location(), e);
			return null;
		}
	}

	private static void dismount(Entity passenger) {
		if (passenger instanceof ServerPlayer player) ((IEServerPlayerEntity) player).ip_stopRidingWithoutTeleportRequest();
		else passenger.stopRiding();
	}

	private static void mount(Entity passenger, Entity vehicle, @Nullable Integer seat) {
		if (seat != null) IplCreateContraptions.restoreSeat(vehicle, passenger, seat);
		if (passenger instanceof ServerPlayer player) ((IEServerPlayerEntity) player).ip_startRidingWithoutTeleportRequest(vehicle);
		else passenger.startRiding(vehicle, true);
	}
}
