package harness;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;import java.nio.file.*;import java.util.*;import java.util.stream.*;
public class DeadHandlers{public static void main(String[] a)throws Exception{
 Map<String,Set<String>> merged=new TreeMap<>(); Set<String> invoked=new HashSet<>();
 Map<String,String> where=new HashMap<>();
 try(Stream<Path> s=Files.walk(Path.of(a[0]))){for(Path p:s.filter(x->x.toString().endsWith(".class")).toList()){
  ClassNode cn=new ClassNode();new ClassReader(Files.readAllBytes(p)).accept(cn,0);
  for(MethodNode m:cn.methods){
   int i=m.name.indexOf("ipl$"); if(i>0) i=i-1;
   if(i>=0 && (m.access & Opcodes.ACC_PUBLIC)==0){
     String key=m.name.substring(i==0?0:i+1); // ipl$name
     merged.computeIfAbsent(key,k->new TreeSet<>()).add(cn.name);
   }
   for(AbstractInsnNode in:m.instructions){
    if(in instanceof MethodInsnNode mi){int j=mi.name.indexOf("ipl$");if(j>=0)invoked.add(mi.name.substring(j));}
    if(in instanceof InvokeDynamicInsnNode id){for(Object b:id.bsmArgs){if(b instanceof Handle h){int j=h.getName().indexOf("ipl$");if(j>=0)invoked.add(h.getName().substring(j));}}}
   }
  }
 }}
 for(var e:merged.entrySet()) if(!invoked.contains(e.getKey())) System.out.println("DEAD "+e.getKey()+" in "+e.getValue());
 System.out.println("handlers="+merged.size()+" invoked="+merged.keySet().stream().filter(invoked::contains).count());
}}
