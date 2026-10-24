package harness;
import java.io.*;import java.util.*;import java.net.*;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;
import org.spongepowered.asm.service.*;import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.launch.platform.container.*;
public class MiniService extends MixinServiceAbstract implements IClassProvider,IClassBytecodeProvider,ITransformerProvider {
 public String getName(){return "Offline real-bytecode harness";} public boolean isValid(){return true;}
 public MixinEnvironment.Phase getInitialPhase(){return MixinEnvironment.Phase.DEFAULT;}
 public IClassProvider getClassProvider(){return this;}public IClassBytecodeProvider getBytecodeProvider(){return this;}public ITransformerProvider getTransformerProvider(){return this;}
 public IClassTracker getClassTracker(){return null;}public IMixinAuditTrail getAuditTrail(){return null;}
 public Collection<String> getPlatformAgents(){return List.of();}public IContainerHandle getPrimaryContainer(){return new ContainerHandleVirtual("offline-tests");}
 public InputStream getResourceAsStream(String path){return getClass().getClassLoader().getResourceAsStream(path);}
 public URL[] getClassPath(){return new URL[0];}
 public Class<?> findClass(String n)throws ClassNotFoundException{return findClass(n,false);}public Class<?> findClass(String n,boolean init)throws ClassNotFoundException{return Class.forName(n,init,getClass().getClassLoader());}public Class<?> findAgentClass(String n,boolean init)throws ClassNotFoundException{return findClass(n,init);}
 public ClassNode getClassNode(String n)throws ClassNotFoundException,IOException{return getClassNode(n,true,0);}public ClassNode getClassNode(String n,boolean transforms)throws ClassNotFoundException,IOException{return getClassNode(n,transforms,0);}
 public ClassNode getClassNode(String n,boolean transforms,int flags)throws ClassNotFoundException,IOException{try(var in=getResourceAsStream(n.replace('.','/')+".class")){if(in==null)throw new ClassNotFoundException(n);var node=new ClassNode();new ClassReader(in).accept(node,flags);return node;}}
 public Collection<ITransformer> getTransformers(){return List.of();}public Collection<ITransformer> getDelegatedTransformers(){return List.of();}public void addTransformerExclusion(String name){}
 public org.spongepowered.asm.mixin.transformer.IMixinTransformer transformer(){return getInternal(org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory.class).createTransformer();}
}
