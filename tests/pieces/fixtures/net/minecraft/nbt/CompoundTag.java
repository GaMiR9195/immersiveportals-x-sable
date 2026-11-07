package net.minecraft.nbt;

import java.util.HashMap;
import java.util.Map;

/** The subset of vanilla's compound tag the label codec uses. */
public class CompoundTag implements Tag {
    private final Map<String, Object> values = new HashMap<>();

    public void put(String key, Tag value) { values.put(key, value); }
    public void putBoolean(String key, boolean value) { values.put(key, value); }
    public void putInt(String key, int value) { values.put(key, value); }
    public void putDouble(String key, double value) { values.put(key, value); }
    public int getInt(String key) { return values.get(key) instanceof Integer n ? n : 0; }
    public double getDouble(String key) { return values.get(key) instanceof Double n ? n : 0.0; }
    public void putLong(String key, long value) { values.put(key, value); }
    public void putByteArray(String key, byte[] value) { values.put(key, value); }
    public boolean getBoolean(String key) { return values.get(key) instanceof Boolean b && b; }
    public long getLong(String key) { return values.get(key) instanceof Long l ? l : 0L; }
    public byte[] getByteArray(String key) { return values.get(key) instanceof byte[] b ? b : new byte[0]; }
    public ListTag getList(String key, int type) { return values.get(key) instanceof ListTag l ? l : new ListTag(); }

    public boolean contains(String key, int type) {
        Object v = values.get(key);
        return type == TAG_LIST ? v instanceof ListTag : type == TAG_BYTE_ARRAY ? v instanceof byte[] : type == TAG_COMPOUND && v instanceof CompoundTag;
    }
}
