package ipl.sable.render;
import org.lwjgl.opengl.*;
import net.minecraft.client.renderer.ShaderInstance;
import qouteall.imm_ptl.core.render.FrontClipping;
public final class ProgramBindTest {
 static int assertions;
 static void check(boolean v,String m){assertions++;if(!v)throw new AssertionError(m);}
 static void pair(int p,float a,float b){GL41.writes.clear();IplProgramBindHook.uploadSubLevel(p);check(GL41.writes.size()==2,"exactly two cut writes");check(GL41.writes.get(0).p()==p&&GL41.writes.get(1).p()==p,"bound program only");check(GL41.writes.get(0).a()==a&&GL41.writes.get(1).a()==b,"correct space and both cuts");check(GL41.writes.stream().noneMatch(w->w.loc()==10),"subcut cannot stomp portal slot");}
 public static void main(String[] args){
  for(int p=1;p<=128;p++)IplProgramRegistry.register(p,"other");
  IplProgramRegistry.register(1,"rendertype_solid");IplProgramRegistry.register(2,"entity");
  pair(1,5,6);int q=GL20.queries;pair(1,5,6);check(q==GL20.queries,"location cache hit");
  pair(2,3,4);pair(3,1,2);
  SubLevelClipUniformPatcher.second=false;pair(1,5,0);
  check(GL41.writes.get(1).d()==1,"missing second is neutral");
  ShaderInstance shader=new ShaderInstance();shader.id=2;IplProgramBindHook.prepareShader(shader);
  check(shader.a.value[0]==3&&shader.b.value[3]==1,"Java uniforms synchronized before apply");
  SubLevelClipUniformPatcher.active=false;pair(2,0,0);
  IplProgramBindHook.prepareShader(shader);check(shader.a.value[3]==1&&shader.a.value[0]==0,"Java uniform reset outside bracket");
  GL41.writes.clear();IplProgramBindHook.onBind(1);check(GL41.writes.isEmpty(),"no portal or sub bracket no work");
  SubLevelClipUniformPatcher.active=true;SubLevelClipUniformPatcher.second=true;
  GL41.writes.clear();IplProgramBindHook.onBind(1);check(GL41.writes.size()==2,"128 registered programs do not cause broadcast");
  GL41.writes.clear();FrontClipping.isClippingEnabled=true;IplProgramBindHook.onBind(2);
  check(GL41.writes.size()==3,"portal plus two independent sub cuts");check(GL41.writes.get(0).a()==21,"portal uses eye space");FrontClipping.isClippingEnabled=false;
  IplProgramRegistry.unregister(1);check(!IplProgramRegistry.usesVanillaSubLevelInputSpace(1),"delete removes space identity");
  GL20.missing=true;IplProgramBindHook.forgetProgram(1);GL41.writes.clear();IplProgramBindHook.uploadSubLevel(1);check(GL41.writes.isEmpty(),"relink invalidates old locations");q=GL20.queries;IplProgramBindHook.uploadSubLevel(1);check(q==GL20.queries,"negative locations cached");
  GL20.missing=false;IplProgramBindHook.forgetProgram(1);pair(1,1,2);
  GL41.writes.clear();IplProgramBindHook.uploadSubLevel(0);check(GL41.writes.isEmpty(),"program zero ignored");
  System.out.println("PASS: "+assertions+" program-bind fixture assertions");
 }
}
