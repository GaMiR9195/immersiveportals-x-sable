package ipl.sable.natives;
import java.util.*;
/** Native resource seam: real Atlas open/clear/refresh methods, no Rapier/JNI loading. */
public final class IplRapierNatives {
 public static final int PIECE_ABI=3;
 public static boolean available=true;
 public static boolean isAvailable(){return available;}public static int abiLevel(){return 7;}
 public static int failBody=-1,throwCreateBody=-1,throwSetBody=-1,opened,failed,updated,cleared; private static long next;
 public static final Set<Long> images=new HashSet<>();
 public static final Map<Integer,double[]> edges=new HashMap<>();
 public static void reset(){failBody=throwCreateBody=throwSetBody=-1;opened=failed=updated=cleared=0;images.clear();edges.clear();available=true;}
 public static boolean piecesSupported(){return true;}
 public static long createImageCollider(long scene,int body,double x,double y,double z,double qx,double qy,double qz,double qw){
  if(body==throwCreateBody)throw new UnsatisfiedLinkError("fixture create failure");
  if(body==failBody){failed++;return -1;} long handle=++next;opened++;images.add(handle);return handle;
 }
 public static void setImageNode(long scene,int body,long image,int node){if(body==throwSetBody)throw new IllegalStateException("fixture node failure");}
 public static void setImagePrefix(long scene,long image,double x,double y,double z,double qx,double qy,double qz,double qw){updated++;}
 public static void removeImageCollider(long scene,int body,long image){images.remove(image);}
 public static void clearPieces(long scene,int body){cleared++;edges.remove(body);}
 public static void setPieceSection(long scene,int body,int x,int y,int z,byte[] labels){}
 public static int nativeEdgeStride(){return 32;}
 public static void setPieceEdges(long scene,int body,double[] data){edges.put(body,data);}
}