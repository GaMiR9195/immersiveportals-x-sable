import java.nio.file.*;
import java.util.*;
import java.io.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
/** Offline target/descriptor audit. This is NOT a substitute for a Minecraft mixin launch. */
public class TargetAudit {
 static int checks;
 static ClassNode node(String name) throws Exception {
  try(InputStream in=TargetAudit.class.getClassLoader().getResourceAsStream(name+".class")) {
   if(in==null)return null;
   ClassNode n=new ClassNode();new ClassReader(in).accept(n,0);return n;
  }
 }
 static Object val(AnnotationNode a,String key){if(a==null||a.values==null)return null;for(int i=0;i<a.values.size();i+=2)if(a.values.get(i).equals(key))return a.values.get(i+1);return null;}
 static List<AnnotationNode> annotations(List<AnnotationNode>a,List<AnnotationNode>b){var out=new ArrayList<AnnotationNode>();if(a!=null)out.addAll(a);if(b!=null)out.addAll(b);return out;}
 static boolean matches(MethodNode m,String selector){int p=selector.indexOf('(');return p<0?m.name.equals(selector):(m.name+m.desc).equals(selector);}
 static void check(boolean ok,String msg){checks++;if(!ok)throw new AssertionError(msg);}
 static List<MethodNode> methods(String target,ClassNode c) throws Exception {
  var out=new ArrayList<>(c.methods);
  if(target.endsWith("/BlockEntityStorage"))out.addAll(node("dev/ryanhcode/sable/neoforge/mixin/compatibility/flywheel/BlockEntityStorageMixin").methods);
  return out;
 }
 public static void main(String[]args)throws Exception {
  var config=com.google.gson.JsonParser.parseString(Files.readString(Path.of("src/main/resources/ipl_sable.mixins.json"))).getAsJsonObject();
  String pkg=config.get("package").getAsString().replace('.','/');
  for(String group:List.of("mixins","client"))for(var item:config.getAsJsonArray(group)) {
   String name=pkg+"/"+item.getAsString().replace('.','/');
   check(node(name)!=null,"production config references absent class: "+group+" "+name);
  }
  System.out.println("PASS: all production mixin config entries resolve to compiled classes");
  String base="ipl/sable/mixin/";
  String[] names={"client/IplFlywheelChunkLifecycleMixin","client/IplFlywheelBeLifecycleMixin","client/IplFlywheelDirtyLifecycleMixin","client/IplFlywheelScopedManagerMixin","IplContactColliderCallbackMixin","IplContactSteppingSystemMixin","IplContactEffectsViewMixin","IplCreateLinkNetworkViewMixin","IplCreateLinkConnectionsAccessor","IplContainerSpatialReadMixin","IplPhysicsSpatialReadMixin","IplHostedBroadcastViewMixin","IplHostedRetainedEntityInsertionMixin","IplReadViewClipMixin","client/SableAltContainerTickMixin","client/IplHostedClientEffectsMixin","client/IplClientSoundInvoker","client/IplHostedFlywheelApiMixin","client/IplHostedFlywheelEntityEventsMixin","client/IplHostedFlywheelPoseCacheMixin","client/IplHostedFlywheelStorageMixin","client/IplFlywheelStorageAccessor"};
  for(String name:names){
   ClassNode mixin=node(base+name);check(mixin!=null,"mixin missing "+name);
   AnnotationNode meta=annotations(mixin.visibleAnnotations,mixin.invisibleAnnotations).stream().filter(a->a.desc.endsWith("/Mixin;")).findFirst().orElseThrow();
   @SuppressWarnings("unchecked") var values=(List<Type>)val(meta,"value");
   check(values!=null&&values.size()==1,"one class target "+name);
   String target=values.getFirst().getInternalName();ClassNode c=node(target);check(c!=null,"target missing "+target);
   var methods=methods(target,c);
   for(MethodNode handler:mixin.methods)for(AnnotationNode annotation:annotations(handler.visibleAnnotations,handler.invisibleAnnotations)){
    @SuppressWarnings("unchecked") var selectors=(List<String>)val(annotation,"method");
    if(selectors==null)continue;
    var selected=new ArrayList<MethodNode>();
    for(String selector:selectors){
     var hits=methods.stream().filter(m->matches(m,selector)).toList();
     check(!hits.isEmpty(),name+" missing method "+selector);selected.addAll(hits);
    }
    Object atValue=val(annotation,"at");var ats=new ArrayList<AnnotationNode>();
    if(atValue instanceof AnnotationNode a)ats.add(a);
    if(atValue instanceof List<?>list)for(Object o:list)if(o instanceof AnnotationNode a)ats.add(a);
    for(AnnotationNode at:ats){
     if(!"INVOKE".equals(val(at,"value")))continue;
     String invoke=(String)val(at,"target");if(invoke==null)continue;
     int semi=invoke.indexOf(';'),paren=invoke.indexOf('(');
     String owner=invoke.substring(1,semi),method=invoke.substring(semi+1,paren),desc=invoke.substring(paren);
     int count=0;
     // Sable replaces remove/recreateAll; only inspect its replacement, not both bodies.
     if(target.endsWith("/BlockEntityStorage") && selectors.contains("recreateAll")) selected.removeIf(c.methods::contains);
     for(MethodNode m:selected)for(AbstractInsnNode ins:m.instructions)if(ins instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals(method)&&call.desc.equals(desc))count++;
     int require=val(annotation,"require") instanceof Integer i?i:1;
     check(count>=require,name+" "+handler.name+" invoke "+invoke+" hits="+count+" require="+require);
    }
    if(annotation.desc.endsWith("/WrapMethod;"))for(MethodNode m:selected)check((m.access&Opcodes.ACC_NATIVE)==0,name+" must not wrap a native body");
   }
   System.out.println("PASS target/descriptor: "+name);
  }
  ClassNode mc=node("net/minecraft/client/Minecraft");
  MethodNode tick=mc.methods.stream().filter(m->m.name.equals("tick")&&m.desc.equals("()V")).findFirst().orElseThrow();
  int tutorial=-1,entities=-1,index=0;
  for(AbstractInsnNode ins:tick.instructions){if(ins instanceof MethodInsnNode call){if(call.owner.endsWith("/Tutorial")&&call.name.equals("onLookAt"))tutorial=index;if(call.name.equals("tickEntities"))entities=index;}index++;}
  check(tutorial>=0&&entities>tutorial,"stock Sable pose seam precedes entity collisions");
  System.out.println("PASS: "+checks+" bytecode-target assertions; PYM optional target audited separately from supplied source");
 }
}
