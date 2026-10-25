import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
/** Actual native step -> material callback, not merely registering a method ID. */
public class NativeContactSmoke {
 static int terrainHits,plotHits; static double maxImpact;
 public static void main(String[] args) {
  System.load(java.nio.file.Path.of(args[0]).toAbsolutePath().toString());
  long scene=Rapier3D.initialize(0,-9.81,0,0);
  long parent=args.length>1?Rapier3D.initialize(0,-9.81,0,0):scene;
  try {
   var callback=new dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback(){
    public double[] onCollision(int x,int y,int z,int a,int b,int c,double px,double py,double pz,double impact,boolean other){
     if(x>=32000)plotHits++;else terrainHits++;
     maxImpact=Math.max(maxImpact,impact);
     if(!Double.isFinite(impact))throw new AssertionError("non-finite impact");
     return new double[]{0,0,0,0};
    }
   };
   int material=Rapier3D.newVoxelCollider(.5,1,0,false,callback);
   Rapier3D.addVoxelColliderBox(material,new double[]{0,0,0,1,1,1});
   int encoded=((material+1)<<16)|3;
   int[] ground=new int[4096];for(int z=0;z<16;z++)for(int x=0;x<16;x++)ground[(z<<4)|x]=encoded;
   Rapier3D.addChunk(parent,0,0,0,ground,true,-1);
   Rapier3D.createSubLevel(scene,1,new double[]{4.5,5,4.5,0,0,0,1});
   int[] plot=new int[4096];plot[0]=encoded;

   Rapier3D.setCenterOfMass(scene,1,32000.5,.5,.5);
   Rapier3D.setLocalBounds(scene,1,32000,0,0,32001,1,1);
   Rapier3D.addChunk(scene,2000,0,0,plot,false,1);
   Rapier3D.setMassProperties(scene,1,100,new double[]{32000.5,.5,.5},new double[]{20,0,0,0,20,0,0,0,20});
   if(parent!=scene && ipl.sable.natives.IplRapierNatives.createImageCollider(parent,1,0,0,0,0,0,0,1)<0) throw new AssertionError("image creation");
   double[] pose=new double[13];
   for(int i=0;i<240;i++)Rapier3D.step(parent,1.0/120);
   Rapier3D.getPose(scene,1,pose);
   if(terrainHits==0||plotHits==0||maxImpact<4)throw new AssertionError("terrain="+terrainHits+" plot="+plotHits+" impact="+maxImpact+" pose="+java.util.Arrays.toString(pose));
   System.out.println("PASS actual "+(parent==scene?"native":"Atlas image")+" contact: terrain="+terrainHits+", plot="+plotHits+", max impact="+maxImpact);
  } finally{if(parent!=scene)Rapier3D.dispose(parent);Rapier3D.dispose(scene);}
 }
}
