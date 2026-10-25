package harness;
import java.nio.file.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.MixinService;
public class FullAudit {
 public static void main(String[]args)throws Exception{
  System.setProperty("mixin.service","harness.MiniService");
  MixinBootstrap.init();MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
  var transformer=((MiniService)MixinService.getService()).transformer();
  com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
  for (String c : System.getProperty("audit.configs").split(",")) Mixins.addConfiguration(c);
  int ok=0,missing=0,unchanged=0,failed=0;
  for(String name:Files.readAllLines(Path.of(args[0]))){ if(name.isBlank())continue;
   byte[] before;
   try(var in=FullAudit.class.getClassLoader().getResourceAsStream(name.replace('.','/')+".class")){ if(in==null){System.out.println("MISSING "+name);missing++;continue;} before=in.readAllBytes();}
   try{
    byte[] after=transformer.transformClassBytes(name,name,before);
    if(java.util.Arrays.equals(before,after)){System.out.println("UNCHANGED "+name);unchanged++;}
    else {System.out.println("OK "+name);ok++; Path o=Path.of(System.getProperty("audit.out"),name.replace('.','/')+".class");Files.createDirectories(o.getParent());Files.write(o,after);}
   }catch(Throwable t){System.out.println("FAIL "+name+" : "+t);Throwable c=t.getCause();while(c!=null){System.out.println("   cause: "+c);c=c.getCause();}failed++;}
  }
  System.out.println("SUMMARY ok="+ok+" missing="+missing+" unchanged="+unchanged+" failed="+failed);
 }
}
