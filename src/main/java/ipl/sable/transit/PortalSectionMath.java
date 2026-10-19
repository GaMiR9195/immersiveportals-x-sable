package ipl.sable.transit;

/** Section of an affine box by a portal-normal slab. Pure geometry, no world state. */
public final class PortalSectionMath {
    public static final int NO_SECTION = -1, INSIDE = 0, OUTSIDE = 1;
    private static final double EPS = 1.0e-8;
    private PortalSectionMath() {}

    /** OUTSIDE means ANY section vertex exceeds the finite opening + release tolerance.
     * This is containment of the section, NOT overlap of the whole box with a larger door.
     * basis contains the three world-space half-edge vectors, not normalized axes. */
    public static int classify(double[] center, double[][] basis, double[] origin,
        double[] w, double[] h, double[] n, double halfW, double halfH, double depth) {
        double dx = center[0]-origin[0], dy = center[1]-origin[1], dz = center[2]-origin[2];
        double cn = dx*n[0]+dy*n[1]+dz*n[2];
        if (Math.abs(cn) > support(basis,n)+depth+EPS) return NO_SECTION;
        double cw = dx*w[0]+dy*w[1]+dz*w[2], ch = dx*h[0]+dy*h[1]+dz*h[2];
        if (Math.abs(cw)+support(basis,w) <= halfW+EPS
            && Math.abs(ch)+support(basis,h) <= halfH+EPS) return INSIDE;
        double[] vertices = new double[24];
        boolean found = false;
        for (int i=0;i<8;i++) {
            double x=dx, y=dy, z=dz;
            for (int axis=0;axis<3;axis++) {
                double sign=(i & (1<<axis)) == 0 ? -1 : 1;
                x+=sign*basis[axis][0]; y+=sign*basis[axis][1]; z+=sign*basis[axis][2];
            }
            int j=i*3;
            vertices[j]=x*w[0]+y*w[1]+z*w[2];
            vertices[j+1]=x*h[0]+y*h[1]+z*h[2];
            vertices[j+2]=x*n[0]+y*n[1]+z*n[2];
            if (Math.abs(vertices[j+2]) <= depth+EPS) {
                found=true;
                if (outside(vertices[j],vertices[j+1],halfW,halfH)) return OUTSIDE;
            }
        }
        // Vertices of box intersect slab = surviving box corners + edge/slab intersections.
        // Testing whole projected bounds would falsely release a tilted long object whose
        // remote end is wide of the opening but nowhere near the crossing plane.
        for (int i=0;i<8;i++) for (int axis=0;axis<3;axis++) {
            if ((i & (1<<axis)) != 0) continue;
            int a=i*3,b=(i | (1<<axis))*3;
            double za=vertices[a+2],zb=vertices[b+2];
            if (Math.abs(zb-za)<EPS) continue;
            for (int side=-1;side<=1;side+=2) {
                double t=(side*depth-za)/(zb-za);
                if (t < -EPS || t > 1+EPS) continue;
                found=true;
                double x=vertices[a]+(vertices[b]-vertices[a])*t;
                double y=vertices[a+1]+(vertices[b+1]-vertices[a+1])*t;
                if (outside(x,y,halfW,halfH)) return OUTSIDE;
            }
        }
        return found ? INSIDE : NO_SECTION;
    }

    private static boolean outside(double x,double y,double w,double h) {
        return Math.abs(x)>w+EPS || Math.abs(y)>h+EPS;
    }
    private static double support(double[][] b,double[] n) {
        return Math.abs(b[0][0]*n[0]+b[0][1]*n[1]+b[0][2]*n[2])
            +Math.abs(b[1][0]*n[0]+b[1][1]*n[1]+b[1][2]*n[2])
            +Math.abs(b[2][0]*n[0]+b[2][1]*n[1]+b[2][2]*n[2]);
    }
}
