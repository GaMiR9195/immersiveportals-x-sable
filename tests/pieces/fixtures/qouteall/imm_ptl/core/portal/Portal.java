package qouteall.imm_ptl.core.portal;

import net.minecraft.world.phys.Vec3;

/** A portal rectangle: origin, unit axes (normal = axisW x axisH) and size. */
public class Portal {
    private final Vec3 origin, axisW, axisH;
    private final double width, height;

    public Portal(Vec3 origin, Vec3 axisW, Vec3 axisH, double width, double height) {
        this.origin = origin;
        this.axisW = axisW;
        this.axisH = axisH;
        this.width = width;
        this.height = height;
    }

    public Vec3 getOriginPos() { return origin; }
    public Vec3 getAxisW() { return axisW; }
    public Vec3 getAxisH() { return axisH; }
    public double getWidth() { return width; }
    public double getHeight() { return height; }

    public Vec3 getNormal() {
        return new Vec3(axisW.y * axisH.z - axisW.z * axisH.y, axisW.z * axisH.x - axisW.x * axisH.z,
            axisW.x * axisH.y - axisW.y * axisH.x);
    }
}
