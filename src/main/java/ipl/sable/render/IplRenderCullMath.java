package ipl.sable.render;

/** Conservative CPU rejection tests before a Sable chunk draw touches GL. */
public final class IplRenderCullMath {
    private IplRenderCullMath() {}

    public static double[] bounds(double[] center, double[][] halfEdges) {
        double x=Math.abs(halfEdges[0][0])+Math.abs(halfEdges[1][0])+Math.abs(halfEdges[2][0]);
        double y=Math.abs(halfEdges[0][1])+Math.abs(halfEdges[1][1])+Math.abs(halfEdges[2][1]);
        double z=Math.abs(halfEdges[0][2])+Math.abs(halfEdges[1][2])+Math.abs(halfEdges[2][2]);
        return new double[]{center[0]-x,center[1]-y,center[2]-z,center[0]+x,center[1]+y,center[2]+z};
    }

    public static boolean outside(double[] bounds, double nx,double ny,double nz,double c) {
        double x=nx>=0?bounds[3]:bounds[0], y=ny>=0?bounds[4]:bounds[1], z=nz>=0?bounds[5]:bounds[2];
        return nx*x+ny*y+nz*z+c < -1.0e-7;
    }

    /** Opposite, coincident cuts leave only a zero-volume seam. Do not send that entire
     * source instance to the GPU in a portal pass where its mapped instance owns the view. */
    public static boolean emptyIntersection(double[] a, double[] b) {
        double la=Math.sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2]);
        double lb=Math.sqrt(b[0]*b[0]+b[1]*b[1]+b[2]*b[2]);
        if (la<1e-12 || lb<1e-12) return false;
        double x=a[0]/la+b[0]/lb,y=a[1]/la+b[1]/lb,z=a[2]/la+b[2]/lb;
        return x*x+y*y+z*z < 1e-20 && a[3]/la+b[3]/lb <= 1e-8;
    }
}
