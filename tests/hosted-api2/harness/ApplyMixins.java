package harness;
import java.nio.file.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.MixinService;
public class ApplyMixins {
 public static void main(String[]args)throws Exception{
  System.setProperty("mixin.service","harness.MiniService");
  MixinBootstrap.init();MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
  if (ApplyMixins.class.getClassLoader().getResource("com/misterblusky9/pym/internal/mixin/interaction/SubLevelRaycastDistanceMixin.class") != null)
    Mixins.addConfiguration("harness-pym.mixins.json");
  var transformer=((MiniService)MixinService.getService()).transformer();
  com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
  Mixins.addConfiguration("harness-flywheel.mixins.json");Mixins.addConfiguration("harness-sable.mixins.json");Mixins.addConfiguration("harness-sable-common.mixins.json");Mixins.addConfiguration("harness-ipl.mixins.json");
  var targets = new java.util.ArrayList<>(java.util.List.of(args));
  if (ApplyMixins.class.getClassLoader().getResource("com/misterblusky9/pym/internal/interaction/ScaleAwareBlockClip.class") != null)
    targets.add("com.misterblusky9.pym.internal.interaction.ScaleAwareBlockClip");
  for(String name:targets){byte[] before;try(var in=ApplyMixins.class.getClassLoader().getResourceAsStream(name.replace('.','/')+".class")){before=in.readAllBytes();}
   byte[] after=transformer.transformClassBytes(name,name,before);
   if(java.util.Arrays.equals(before,after))throw new AssertionError("not transformed "+name);
   Path out=Path.of(System.getProperty("harness.output","build/hosted-api2-transformed"),name.replace('.','/')+".class");Files.createDirectories(out.getParent());Files.write(out,after);
   System.out.println("PASS applied: "+name+" "+before.length+" -> "+after.length);
  }
 }
}
