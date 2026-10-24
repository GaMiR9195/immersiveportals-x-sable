package ipl.sable.dim;
/** Explicit read-only enumeration scope; never enabled for ownership/tick/save loops. */
public final class IplReadViewScope {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private IplReadViewScope() {}
    public static boolean active() { return DEPTH.get() > 0; }
    public static int enter() { int previous = DEPTH.get(); DEPTH.set(previous + 1); return previous; }
    public static void exit(int previous) { if (previous == 0) DEPTH.remove(); else DEPTH.set(previous); }
}
