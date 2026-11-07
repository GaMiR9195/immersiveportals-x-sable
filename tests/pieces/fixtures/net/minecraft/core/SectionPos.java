package net.minecraft.core;

/** Vanilla's section key packing. */
public final class SectionPos {
    public static long asLong(int x, int y, int z) {
        return ((long) x & 4194303L) << 42 | ((long) y & 1048575L) | ((long) z & 4194303L) << 20;
    }

    public static int x(long key) {
        return (int) (key >> 42);
    }

    public static int y(long key) {
        return (int) (key << 44 >> 44);
    }

    public static int z(long key) {
        return (int) (key << 22 >> 42);
    }
}
