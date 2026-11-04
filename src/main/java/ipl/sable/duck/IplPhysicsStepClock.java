package ipl.sable.duck;

/**
 * Server tick in which a physics system's bodies were last stepped (implemented by
 * {@code PhysicsSystemMixin}, maintained by {@link ipl.sable.atlas.IplFusedStep}).
 */
public interface IplPhysicsStepClock {

    int ipl$steppedTick();

    void ipl$markStepped(int serverTick);
}
