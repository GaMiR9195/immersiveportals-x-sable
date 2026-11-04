package org.lwjgl.opengl;

/** Locations: IP's slot 0 at 10, sub-level element {@code i} at {@code 20 + i}. */
public class GL20 {
    public static int queries = 0;
    public static boolean missing;
    /** Number of sub-level array elements the next linked program declares. */
    public static int declared = 6;

    public static int glGetUniformLocation(int p, String n) {
        queries++;
        if (missing) return -1;
        if (n.equals("iportal_ClippingEquation")) return 10;
        if (n.equals("ipl_subLevelClipEquation")) return declared > 0 ? 20 : -1;
        String prefix = "ipl_subLevelClipEquation[";
        if (n.startsWith(prefix) && n.endsWith("]")) {
            int i = Integer.parseInt(n.substring(prefix.length(), n.length() - 1));
            return i < declared ? 20 + i : -1;
        }
        return -1;
    }
}
