package ipl.sable.dim;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.mixin.IplOwnedSubLevelsAccessor;
import java.util.List;
/** Internal ownership view. Do not expose a parent world's hosted list to save/tick loops. */
public final class IplOwnedSubLevels {
    private IplOwnedSubLevels() {}
    public static List<SubLevel> owned(SubLevelContainer container) {
        return ((IplOwnedSubLevelsAccessor)container).ipl$ownedSubLevels();
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List<ServerSubLevel> owned(ServerSubLevelContainer container) {
        return (List)owned((SubLevelContainer)container);
    }
}
