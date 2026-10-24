package ipl.sable.dim;
import net.minecraft.world.level.Level;
/** Run after deleting client bridge classes: common verification must not resolve them. */
public class EnvironmentServerLinkTest {
 public static void main(String[] args) {
  Level storage=new Level(false,true),parent=new Level(false,false);
  IplWorldFrameContext.value=parent;
  if(IplEnvironmentFrame.contextualLevel(storage)!=parent) throw new AssertionError("server context");
  System.out.println("PASS: common environment boundary links with client bridge classes absent");
 }
}
