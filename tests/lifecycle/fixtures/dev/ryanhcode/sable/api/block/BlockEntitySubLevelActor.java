package dev.ryanhcode.sable.api.block;
import dev.ryanhcode.sable.sublevel.SubLevel;
public interface BlockEntitySubLevelActor {
 default Iterable<SubLevel> sable$getConnectionDependencies(){return null;}
 default Iterable<SubLevel> sable$getLoadingDependencies(){return sable$getConnectionDependencies();}
}
