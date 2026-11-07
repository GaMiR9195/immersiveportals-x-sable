package ipl.sable.client;

import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import ipl.sable.mixin.client.InterpolationClockAccessor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * One Sable interpolation clock for every client level.
 *
 * <p>Sable gives each client level's container its own clock, fed by that level's clock packets.
 * With portals a client shows several levels at once, and a ship (or the player) moves between
 * them. Each clock locked to its own packets at its own delay, so a ship that changed level
 * changed time at the portal: an idle destination clock restarted on the ship's first packet
 * with Sable's start delay, a running one stood at another phase, and the ship was pulled ahead
 * or hung at the exit, then ran at another pace until that clock settled. A level the player
 * stopped receiving movement from kept a stale clock (the server drops a player who left the
 * level without a stop packet).
 *
 * <p>The server stamps every level on one timeline ({@code IplTimeline}), so all containers share
 * this single clock ({@code ClientSubLevelContainerMixin}): a body that changes level keeps its
 * time and pace, whatever else moves in either level. The clock advances once per client tick,
 * whichever container ticks first. Clock packets of all levels are merged
 * ({@code SnapshotInfoPacketMixin}): the clock runs while any level reports motion; a level's
 * "stopped" only stops it when no other level is running. A level counts as running only while
 * its packets keep coming ({@link #RUNNING_GRACE_TICKS}), so a level that went silent without a
 * stop does not hold the clock. When motion resumes after every level was quiet, the clock
 * restarts at the new tick exactly as Sable restarts it after a stop.
 *
 * <p>Motion that moves from one level to another (a ship through a portal) can stop one level a
 * moment before the other starts. Sable would restart the clock at the new packet's tick and
 * shift the phase of everything in motion; a clock stopped that recently resumes in phase
 * instead.
 */
public final class IplSharedClock {

    /** What Sable's {@code receiveInfo} does with a clock packet. */
    public enum Info {
        RUNNING,
        STOPPED,
        /** Restart at the packet's tick (every level went quiet without a stop). */
        RESTART,
        /** Run on in phase (the clock stopped a moment ago). */
        RESUME,
        /** Older than the newest tick: dropped, as Sable drops it. */
        LATE
    }

    /**
     * A stamp this far behind the newest one belongs to a new server timeline. Late packets stay
     * well within it: TCP (stop packets, a congested link) and UDP can overtake each other.
     */
    private static final int NEW_TIMELINE_TICKS = 400;
    /** A running level may miss this many packets (UDP loss) before it counts as gone quiet. */
    private static final int RUNNING_GRACE_TICKS = 3;

    private static ClientSableInterpolationState clock;
    private static long clientTick;
    private static long tickedAt = -1L;
    /** Tick of the packet that stopped the running clock. */
    private static int stoppedAt = Integer.MIN_VALUE;
    /** Newest clock packet of each level. */
    private static final Map<ResourceKey<Level>, LevelInfo> LEVELS = new HashMap<>();

    private record LevelInfo(int tick, boolean stopped) {}

    private IplSharedClock() {}

    /** The clock every client container uses. */
    public static ClientSableInterpolationState get() {
        if (clock == null) clock = new ClientSableInterpolationState();
        return clock;
    }

    /** {@code Minecraft.tick} HEAD. */
    public static void beginClientTick() {
        clientTick++;
    }

    /** A container tick: the shared clock advances on the first one of the client tick. */
    public static void tickOnce(ClientSableInterpolationState state) {
        if (state != clock) {
            state.tick();
            return;
        }
        if (tickedAt == clientTick) return;
        tickedAt = clientTick;
        state.tick();
        // Session and label payloads wait for the pose they belong to (IplStraddleSessionStore).
        // Timed render state is drained at the outer render pass, using its partial tick.
    }

    /** Merges a clock packet of {@code level} into the shared clock. */
    public static Info merge(ClientSableInterpolationState state, Level level, int gameTick, boolean stopped) {
        if (state != clock || level == null) return stopped ? Info.STOPPED : Info.RUNNING;
        var access = (InterpolationClockAccessor) state;
        if (access.ipl$getReceivedFirstUpdate() && gameTick < access.ipl$getMostRecentTick()) {
            if (gameTick >= access.ipl$getMostRecentTick() - NEW_TIMELINE_TICKS) return Info.LATE;
            reset();
        }
        boolean runningBefore = anyRunning(gameTick);
        LEVELS.put(level.dimension(), new LevelInfo(gameTick, stopped));
        if (!anyRunning(gameTick)) {
            if (!state.isStopped()) stoppedAt = gameTick;
            return Info.STOPPED;
        }
        if (state.isStopped()) {
            boolean justStopped = access.ipl$getReceivedFirstUpdate() && stoppedAt != Integer.MIN_VALUE
                && gameTick - stoppedAt <= RUNNING_GRACE_TICKS;
            return justStopped ? Info.RESUME : Info.RUNNING;
        }
        return runningBefore ? Info.RUNNING : Info.RESTART;
    }

    private static boolean anyRunning(int gameTick) {
        for (LevelInfo info : LEVELS.values()) {
            if (!info.stopped() && info.tick() >= gameTick - RUNNING_GRACE_TICKS) return true;
        }
        return false;
    }

    /** New connection, respawn or level change: the clock instance stays (containers hold it). */
    public static void reset() {
        LEVELS.clear();
        tickedAt = -1L;
        stoppedAt = Integer.MIN_VALUE;
        if (clock == null) return;
        var access = (InterpolationClockAccessor) clock;
        access.ipl$setReceivedFirstUpdate(false);
        access.ipl$setStopped(true);
        access.ipl$setMostRecentTick(-1.0);
        access.ipl$setInterpolationTick(0.0);
        access.ipl$setEstimatedServerTickSpeed(0.0);
        access.ipl$setServerMsFromLastUpdate(0.0f);
        clock.mostRecentInterpolationTick = 0.0;
        clock.lastInterpolationTick = 0.0;
    }
}
