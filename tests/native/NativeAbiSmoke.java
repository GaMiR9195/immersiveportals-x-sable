import ipl.sable.natives.IplRapierNatives;
/** Loads the actual compiled binary through JNI, not a mocked provider. */
public class NativeAbiSmoke {
 public static void main(String[] args) {
  System.load(java.nio.file.Path.of(args[0]).toAbsolutePath().toString());
  IplRapierNatives.markAvailable();
  if(!IplRapierNatives.isAvailable() || IplRapierNatives.compatibilityAbi()!=2)
   throw new AssertionError("ABI mismatch");
  if(IplRapierNatives.setEnvironmentChart(0,1,0))
   throw new AssertionError("null scene accepted");
  long scene=dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.initialize(0,-9.81,0,0);
  try {
   var callback = new dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback() {
    public double[] onCollision(int x,int y,int z,int a,int b,int c,double px,double py,double pz,double impact,boolean other) {
     return new double[]{0,0,0,0};
    }
   };
   int material=dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.newVoxelCollider(.5,1,0,false,callback);
   if(material!=0) throw new AssertionError("first material registration failed");
   dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.addVoxelColliderBox(material,new double[]{0,0,0,1,1,1});
   for(int i=0;i<2;i++) {
    long rope=dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.createRope(scene,.1,.5,new double[]{0,0,0,1,0,0,2,0,0},3);
    if(dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.queryRope(scene,rope)==null) throw new AssertionError("rope query");
    if(dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.removeRope(scene,rope)!=rope) throw new AssertionError("defined rope return");
    if(dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.removeRope(scene,rope)!=0) throw new AssertionError("idempotent rope teardown");
   }
  } finally {
   dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.dispose(scene);
  }
  System.out.println("PASS: real JNI load/ABI, material registration/callback lookup, rope create/query/remove/repeated teardown, scene disposal");
 }
}
