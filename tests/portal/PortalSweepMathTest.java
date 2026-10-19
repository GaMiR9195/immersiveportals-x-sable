package ipl.sable.transit;

import java.util.Random;

/** Standalone tests of production geometry, without Minecraft/Gradle/JUnit. */
public final class PortalSweepMathTest {
    private static final double[] O = {0,0,0}, W = {1,0,0}, H = {0,1,0}, N = {0,0,1};
    private static final double[][] CUBE = {{.5,0,0},{0,.5,0},{0,0,.5}};
    private static int assertions;

    public static void main(String[] args) {
        PortalSweepMath.Hit straight = hit(new double[]{0,0,-10}, new double[]{0,0,10}, CUBE);
        check(straight != null, "high speed crossing");
        near(straight.enter(), .475, "entry lies on actual plane, not a 0.1-deep band");
        near(straight.leave(), .525, "trailing boundary");
        check(hit(new double[]{3,0,-10}, new double[]{3,0,10}, CUBE) == null, "finite aperture miss");
        check(hit(new double[]{0,0,-1}, new double[]{0,0,-1}, CUBE) == null, "stationary source");
        check(hit(new double[]{0,0,0}, new double[]{0,0,0}, CUBE) != null, "stationary straddle");
        check(hit(new double[]{0,0,1}, new double[]{0,0,1}, CUBE) == null, "stationary destination");
        // Plane interval begins at .25, but width is reached only at .5.
        PortalSweepMath.Hit oblique = hit(new double[]{-3,0,-1}, new double[]{0,0,1}, CUBE);
        check(oblique != null, "oblique hit");
        near(oblique.enter(), .5, "entry must include lateral clipping time");
        PortalSweepMath.Hit reverse = hit(new double[]{0,0,1}, new double[]{-3,0,-1}, CUBE);
        near(reverse.enter(), 1-oblique.leave(), "time reversal entry");
        near(reverse.leave(), 1-oblique.enter(), "time reversal exit");
        // Slender OBB can miss a tiny corner even though its world AABB overlaps it.
        double r = Math.sqrt(.5);
        double[][] slender = {{2*r,2*r,0},{-.05*r,.05*r,0},{0,0,.1}};
        PortalSweepMath.Hit corner = PortalSweepMath.sweep(
            new double[]{0,1.5,-1}, new double[]{0,1.5,1}, slender, slender,
            O,W,H,N,.1,.1,0,0);
        check(corner == null, "rotated OBB rejects world-AABB false positive");
        check(PortalSweepMath.sweep(new double[]{0,0,.505}, new double[]{0,0,.505},
            CUBE,CUBE,O,W,H,N,1,1,.01,0) != null, "exit skin retains contact");
        check(PortalSweepMath.sweep(new double[]{0,0,.52}, new double[]{0,0,.52},
            CUBE,CUBE,O,W,H,N,1,1,.01,0) == null, "exit skin releases geometrically");
        randomTranslation();
        rotationalChordBound();
        System.out.println("PASS: " + assertions + " geometry assertions (seed=0x51AB1E)");
    }

    private static PortalSweepMath.Hit hit(double[] a, double[] b, double[][] basis) {
        return PortalSweepMath.sweep(a,b,basis,basis,O,W,H,N,1,1,0,0);
    }

    private static void randomTranslation() {
        Random random = new Random(0x51AB1E);
        for (int i=0;i<1000;i++) {
            double angle = random.nextDouble()*Math.PI*2;
            double c=Math.cos(angle), s=Math.sin(angle);
            double[][] basis={{.5*c,.5*s,0},{-.3*s,.3*c,0},{0,0,.2}};
            double[] a={random.nextDouble()*8-4, random.nextDouble()*8-4,-3};
            double[] b={random.nextDouble()*8-4, random.nextDouble()*8-4,3};
            PortalSweepMath.Hit forward=hit(a,b,basis), backward=hit(b,a,basis);
            check((forward==null)==(backward==null), "reversal existence");
            if (forward != null) {
                near(forward.enter(),1-backward.leave(),"random time reversal");
                near(forward.leave(),1-backward.enter(),"random time reversal");
                double t=(forward.enter()+forward.leave())*.5;
                double[] p=lerp(a,b,t);
                check(hit(p,p,basis)!=null,"reported interval has a real static SAT overlap");
            }
            for(int j=0;j<=40;j++) {
                double t=j/40.0;
                double[] p=lerp(a,b,t);
                if(hit(p,p,basis)!=null) {
                    check(forward!=null,"sweep cannot miss sampled static overlap");
                    check(t+1e-8>=forward.enter() && t-1e-8<=forward.leave(),"sample inside reported interval");
                }
            }
            // Rigidly rotate the entire test about Y; crossing times must not change.
            double angleY=random.nextDouble()*Math.PI*2;
            double[][] rotated={rotateY(basis[0],angleY),rotateY(basis[1],angleY),rotateY(basis[2],angleY)};
            PortalSweepMath.Hit invariant=PortalSweepMath.sweep(rotateY(a,angleY),rotateY(b,angleY),
                rotated,rotated,O,rotateY(W,angleY),rotateY(H,angleY),rotateY(N,angleY),1,1,0,0);
            check((forward==null)==(invariant==null),"world-frame rotation invariance");
            if(forward!=null) near(forward.enter(),invariant.enter(),"world-frame rotation time");
        }
    }

    private static void rotationalChordBound() {
        // Long arm rotates through the plane while endpoint centers both miss it.
        double theta=Math.toRadians(5), radius=20;
        double[] a={radius*Math.cos(-theta/2),0,radius*Math.sin(-theta/2)};
        double[] b={radius*Math.cos(theta/2),0,radius*Math.sin(theta/2)};
        double[][] ba={{.01,0,0},{0,.01,0},{0,0,.01}};
        double[] origin={radius,0,0};
        double pad=(radius+.02)*(1-Math.cos(theta/2));
        check(PortalSweepMath.sweep(a,b,ba,ba,origin,W,H,N,.0001,.1,0,pad)!=null,
            "rotational sagitta bound preserves a narrow aperture hit");
    }

    private static double[] rotateY(double[] p,double a) {
        return new double[]{Math.cos(a)*p[0]+Math.sin(a)*p[2],p[1],-Math.sin(a)*p[0]+Math.cos(a)*p[2]};
    }
    private static double[] lerp(double[] a,double[] b,double t) {
        return new double[]{a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t,a[2]+(b[2]-a[2])*t};
    }
    private static void near(double a,double b,String name) {check(Math.abs(a-b)<1e-7,name+": "+a+" vs "+b);}
    private static void check(boolean ok,String name) {assertions++; if(!ok) throw new AssertionError(name);}
}
