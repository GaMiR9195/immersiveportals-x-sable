package ipl.sable.render;

/** Fixture: equation {@code (100 + 10 * space + slot, 0, 0, 1)}, world 1, eye 2, chunk input 3. */
public class IplClipUniformPatcher {
    public static final int SLOTS = 6;
    public static boolean active = true, chunk = true;
    public static int planes = SLOTS;
    public static int scopedProgram = -1;
    public static double[] scoped;

    private static float[] e(int space, int slot) {
        return active && slot < planes ? new float[]{100 + 10 * space + slot, 0, 0, 1} : null;
    }

    public static boolean hasSubLevelClip() { return active; }
    public static float[] getCurrentSubLevelEqWorld(int slot) { return e(1, slot); }
    public static float[] getCurrentSubLevelEqEye(int slot) { return e(2, slot); }
    public static float[] getCurrentSubLevelEqVanillaInput(int slot) { return e(3, slot); }
    public static boolean isChunkInputSpace() { return chunk; }
    public static double[] portalInputEquation(int p) { return p == scopedProgram ? scoped : null; }
}
