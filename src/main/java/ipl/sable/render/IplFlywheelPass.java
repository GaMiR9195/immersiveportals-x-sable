package ipl.sable.render;

/** Render-thread facts about the Flywheel frame being planned (its plan may run on workers). */
public final class IplFlywheelPass {
    /** The frame plan being executed belongs to a portal pass (or another IP world render). */
    public static volatile boolean planningPortalPass;

    private IplFlywheelPass() {}
}
