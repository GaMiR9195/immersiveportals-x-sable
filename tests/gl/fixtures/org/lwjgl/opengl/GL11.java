package org.lwjgl.opengl;

import java.util.HashSet;
import java.util.Set;

public class GL11 {
    /** Same enum as GL_CLIP_DISTANCE0, as in real GL. */
    public static final int GL_CLIP_PLANE0 = 0x3000;
    public static final Set<Integer> enabled = new HashSet<>();

    public static void glEnable(int x) { enabled.add(x); }
    public static void glDisable(int x) { enabled.remove(x); }
}
