package net.minecraft.nbt;

import java.util.ArrayList;
import java.util.List;

public class ListTag implements Tag {
    private final List<Tag> items = new ArrayList<>();

    public boolean add(Tag tag) { return items.add(tag); }
    public int size() { return items.size(); }
    public CompoundTag getCompound(int i) { return (CompoundTag) items.get(i); }
}
