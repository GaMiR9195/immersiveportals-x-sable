package dev.ryanhcode.sable.api.sublevel;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;

import java.util.List;

public class ClientSubLevelContainer {
    public List<ClientSubLevel> getAllSubLevels() {
        return List.of(new ClientSubLevel());
    }
}
