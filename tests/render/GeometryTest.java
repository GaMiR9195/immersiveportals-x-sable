package ipl.sable.render;
import ipl.sable.transit.PortalSectionMath;
import java.util.Random;
public final class GeometryTest {
 static int assertions;
 static void check(boolean b,String m){assertions++;if(!b)throw new AssertionError(m);}
 static int section(double[] c,double[][] e,double depth){return PortalSectionMath.classify(c,e,new double[3],new double[]{1,0,0},new double[]{0,1,0},new double[]{0,0,1},1.25,1.25,depth);}
 public static void main(String[] args){
  double[][] cube={{.5,0,0},{0,.5,0},{0,0,.5}};
  check(section(new double[]{.74,0,0},cube,0)==0,"24cm protrusion retained");
  check(section(new double[]{.76,0,0},cube,0)==1,"26cm protrusion released despite majority overlap");
  check(section(new double[]{0,0,2},cube,.1)==-1,"no normal section");
  double[][] longTilt={{2,0,2},{0,.2,0},{.1,0,-.1}};
  check(section(new double[3],longTilt,0)==0,"remote tilted end outside is not a side exit");
  check(section(new double[]{1.15,0,0},longTilt,0)==1,"actual tilted section leaves");
  check(section(new double[]{-1.15,0,0},longTilt,0)==1,"symmetric tilted section leaves");
  check(section(new double[]{0,0,0},longTilt,1.3)==1,"finite slab includes real protrusion");
  check(IplRenderCullMath.emptyIntersection(new double[]{1,0,0,0},new double[]{-2,0,0,0}),"coincident opposed cuts");
  check(IplRenderCullMath.emptyIntersection(new double[]{1,0,0,-2},new double[]{-1,0,0,1}),"separated cuts empty");
  check(!IplRenderCullMath.emptyIntersection(new double[]{1,0,0,1},new double[]{-1,0,0,1}),"nonempty slab kept");
  check(!IplRenderCullMath.emptyIntersection(new double[]{1,0,0,0},new double[]{0,1,0,0}),"nonparallel cuts kept");
  Random random=new Random(529);
  for(int i=0;i<1000;i++){
   double[] c={random.nextDouble()*100,random.nextDouble()*100,random.nextDouble()*100};
   double[][] e=new double[3][3];for(int a=0;a<3;a++)for(int k=0;k<3;k++)e[a][k]=random.nextDouble()*20-10;
   double[] b=IplRenderCullMath.bounds(c,e),lo={1e99,1e99,1e99},hi={-1e99,-1e99,-1e99};
   for(int v=0;v<8;v++)for(int k=0;k<3;k++){double x=c[k];for(int a=0;a<3;a++)x+=((v&(1<<a))==0?-1:1)*e[a][k];lo[k]=Math.min(lo[k],x);hi[k]=Math.max(hi[k],x);}
   for(int k=0;k<3;k++){check(Math.abs(lo[k]-b[k])<1e-10,"affine min");check(Math.abs(hi[k]-b[k+3])<1e-10,"affine max");}
   double nx=random.nextDouble()-.5,ny=random.nextDouble()-.5,nz=random.nextDouble()-.5,d=random.nextDouble()*20-10;
   boolean allOutside=true;for(int v=0;v<8;v++){double x=(v&1)==0?b[0]:b[3],y=(v&2)==0?b[1]:b[4],z=(v&4)==0?b[2]:b[5];if(nx*x+ny*y+nz*z+d>=-1e-7)allOutside=false;}
   check(IplRenderCullMath.outside(b,nx,ny,nz,d)==allOutside,"halfspace conservative support");
  }
  System.out.println("PASS: "+assertions+" section/culling geometry assertions");
 }
}
