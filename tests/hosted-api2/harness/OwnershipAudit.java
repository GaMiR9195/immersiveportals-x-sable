package harness;

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import ipl.sable.IplSableRenderOwnershipPlugin;

/** Check routing scope in the actual transformed classes, plus provenance/idempotence. */
public final class OwnershipAudit {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static ClassNode read(String name)throws Exception {
        var n=new ClassNode();new ClassReader(Files.readAllBytes(Path.of("build/hosted-api2-transformed",name+".class"))).accept(n,0);return n;
    }
    static MethodNode sample(String origin){
        var m=new MethodNode(Opcodes.ACC_PUBLIC,"sample","()V",null,null);
        var a=new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
        a.values=new ArrayList<>(List.of("mixin",origin));m.visibleAnnotations=new ArrayList<>(List.of(a));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"dev/ryanhcode/sable/api/sublevel/ClientSubLevelContainer","getAllSubLevels","()Ljava/util/List;",false));return m;
    }
    public static void main(String[]args)throws Exception {
        for(String name:List.of("net/minecraft/client/renderer/LevelRenderer","net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer")){
            var n=read(name);int owned=0;
            for(var m:n.methods)for(var i:m.instructions)if(i instanceof MethodInsnNode c){
                if(c.owner.equals("ipl/sable/dim/IplOwnedSubLevels")&&c.name.equals("owned"))owned++;
                check(!(c.name.equals("getAllSubLevels")&&c.owner.startsWith("dev/ryanhcode/sable/api/sublevel/")),"stock renderer still sees public union: "+name+" "+m.name);
            }
            check(owned>0,"no owned stock render submissions "+name);
            check(IplSableRenderOwnershipPlugin.adaptRenderConsumers(n)==0,"not idempotent "+name);
            System.out.println("PASS stock render ownership: "+name+" owned="+owned);
        }
        var n=new ClassNode();n.methods.add(sample("dev.ryanhcode.sable.mixin.sublevel_render.impl.sodium.SodiumWorldRendererMixin"));
        n.methods.add(sample("some.addon.ScaleConsumer"));
        check(IplSableRenderOwnershipPlugin.adaptRenderConsumers(n)==1,"provenance scope");
        check(((MethodInsnNode)n.methods.get(1).instructions.getFirst()).name.equals("getAllSubLevels"),"addon enumeration changed");
        check(IplSableRenderOwnershipPlugin.adaptRenderConsumers(n)==0,"synthetic idempotence");
        var server=read("dev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer");boolean publicCall=false,closeOwned=false;
        for(var m:server.methods)for(var i:m.instructions)if(i instanceof MethodInsnNode c){
            if(m.name.equals("getAllSubLevels")&&c.name.equals("getAllSubLevels")&&c.owner.endsWith("/SubLevelContainer"))publicCall=true;
            if(m.name.contains("serverOwned")||m.name.contains("baseOwned")||m.name.contains("owned"))if(c.owner.equals("ipl/sable/dim/IplOwnedSubLevels"))closeOwned=true;
        }
        check(publicCall,"server public enumeration was replaced by ownership");
        // The close wrapper may have a synthetic name; require an owned-list call somewhere outside public enumeration.
        if(!closeOwned)for(var m:server.methods)if(!m.name.equals("getAllSubLevels"))for(var i:m.instructions)
            if(i instanceof MethodInsnNode c&&c.owner.equals("ipl/sable/dim/IplOwnedSubLevels"))closeOwned=true;
        check(closeOwned,"server shutdown not owner-local");
        System.out.println("PASS ownership/provenance bytecode assertions: "+checks);
    }
}
