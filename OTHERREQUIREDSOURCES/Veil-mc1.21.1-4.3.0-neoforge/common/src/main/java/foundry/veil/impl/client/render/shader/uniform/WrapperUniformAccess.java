package foundry.veil.impl.client.render.shader.uniform;

import foundry.veil.api.client.render.shader.uniform.ShaderUniformAccess;
import org.jetbrains.annotations.ApiStatus;
import org.joml.*;

import java.util.function.Supplier;

@ApiStatus.Internal
public record WrapperUniformAccess(Supplier<ShaderUniformAccess> getter) implements ShaderUniformAccess {

    private ShaderUniformAccess getAccess() {
        ShaderUniformAccess access = this.getter.get();
        return access != null ? access : ShaderUniformAccess.EMPTY;
    }

    @Override
    public boolean isValid() {
        return this.getAccess().isValid();
    }

    @Override
    public void setFloat(float value) {
        this.getAccess().setFloat(value);
    }

    @Override
    public void setVector(float x, float y) {
        this.getAccess().setVector(x, y);
    }

    @Override
    public void setVector(float x, float y, float z) {
        this.getAccess().setVector(x, y, z);
    }

    @Override
    public void setVector(float x, float y, float z, float w) {
        this.getAccess().setVector(x, y, z, w);
    }

    @Override
    public void setVector(Vector2fc value) {
        this.getAccess().setVector(value);
    }

    @Override
    public void setVector(Vector3fc value) {
        this.getAccess().setVector(value);
    }

    @Override
    public void setVector(Vector4fc value) {
        this.getAccess().setVector(value);
    }

    @Override
    public void setVector(float[] values) {
        this.getAccess().setVector(values);
    }

    @Override
    public void setInt(int value) {
        this.getAccess().setInt(value);
    }

    @Override
    public void setVectorI(int x, int y) {
        this.getAccess().setVectorI(x, y);
    }

    @Override
    public void setVectorI(int x, int y, int z) {
        this.getAccess().setVectorI(x, y, z);
    }

    @Override
    public void setVectorI(int x, int y, int z, int w) {
        this.getAccess().setVectorI(x, y, z, w);
    }

    @Override
    public void setVectorI(Vector2ic value) {
        this.getAccess().setVectorI(value);
    }

    @Override
    public void setVectorI(Vector3ic value) {
        this.getAccess().setVectorI(value);
    }

    @Override
    public void setVectorI(Vector4ic value) {
        this.getAccess().setVectorI(value);
    }

    @Override
    public void setVectorI(int[] values) {
        this.getAccess().setVectorI(values);
    }

    @Override
    public void setDouble(double value) {
        this.getAccess().setDouble(value);
    }

    @Override
    public void setVector64(double x, double y) {
        this.getAccess().setVector64(x, y);
    }

    @Override
    public void setVector64(double x, double y, double z) {
        this.getAccess().setVector64(x, y, z);
    }

    @Override
    public void setVector64(double x, double y, double z, double w) {
        this.getAccess().setVector64(x, y, z, w);
    }

    @Override
    public void setVector64(Vector2dc value) {
        ShaderUniformAccess.super.setVector64(value);
    }

    @Override
    public void setVector64(Vector3dc value) {
        this.getAccess().setVector64(value);
    }

    @Override
    public void setVector64(Vector4dc value) {
        this.getAccess().setVector64(value);
    }

    @Override
    public void setVector64(double[] values) {
        this.getAccess().setVector64(values);
    }

    @Override
    public void setLong(long value) {
        this.getAccess().setLong(value);
    }

    @Override
    public void setVectorI64(long x, long y) {
        this.getAccess().setVectorI64(x, y);
    }

    @Override
    public void setVectorI64(long x, long y, long z) {
        this.getAccess().setVectorI64(x, y, z);
    }

    @Override
    public void setVectorI64(long x, long y, long z, long w) {
        this.getAccess().setVectorI64(x, y, z, w);
    }

    @Override
    public void setVectorI64(long[] values) {
        this.getAccess().setVectorI64(values);
    }

    @Override
    public void setFloats(float... values) {
        this.getAccess().setFloats(values);
    }

    @Override
    public void setVectors(Vector2fc... values) {
        this.getAccess().setVectors(values);
    }

    @Override
    public void setVectors(Vector3fc... values) {
        this.getAccess().setVectors(values);
    }

    @Override
    public void setVectors(Vector4fc... values) {
        this.getAccess().setVectors(values);
    }

    @Override
    public void setInts(int... values) {
        this.getAccess().setInts(values);
    }

    @Override
    public void setIVectors(Vector2ic... values) {
        this.getAccess().setIVectors(values);
    }

    @Override
    public void setIVectors(Vector3ic... values) {
        this.getAccess().setIVectors(values);
    }

    @Override
    public void setIVectors(Vector4ic... values) {
        this.getAccess().setIVectors(values);
    }

    @Override
    public void setDoubles(double... values) {
        this.getAccess().setDoubles(values);
    }

    @Override
    public void set64Vectors(Vector2dc... values) {
        this.getAccess().set64Vectors(values);
    }

    @Override
    public void set64Vectors(Vector3dc... values) {
        this.getAccess().set64Vectors(values);
    }

    @Override
    public void set64Vectors(Vector4dc... values) {
        this.getAccess().set64Vectors(values);
    }

    @Override
    public void setLongs(long... values) {
        this.getAccess().setLongs(values);
    }

    @Override
    public void setHandle(long value) {
        this.getAccess().setHandle(value);
    }

    @Override
    public void setHandles(long... values) {
        this.getAccess().setHandles(values);
    }

    @Override
    public void setMatrix(Matrix2fc value) {
        this.getAccess().setMatrix(value);
    }

    @Override
    public void setMatrix(Matrix3fc value) {
        this.getAccess().setMatrix(value);
    }

    @Override
    public void setMatrix(Matrix4fc value) {
        this.getAccess().setMatrix(value);
    }

    @Override
    public void setMatrix2x3(Matrix3x2fc value) {
        this.getAccess().setMatrix2x3(value);
    }

    @Override
    public void setMatrix3x2(Matrix3x2fc value) {
        this.getAccess().setMatrix3x2(value);
    }

    @Override
    public void setMatrix3x4(Matrix4x3fc value) {
        this.getAccess().setMatrix3x4(value);
    }

    @Override
    public void setMatrix4x3(Matrix4x3fc value) {
        this.getAccess().setMatrix4x3(value);
    }

    @Override
    public void setMatrix(Matrix2fc value, boolean transpose) {
        this.getAccess().setMatrix(value, transpose);
    }

    @Override
    public void setMatrix(Matrix3fc value, boolean transpose) {
        this.getAccess().setMatrix(value, transpose);
    }

    @Override
    public void setMatrix(Matrix4fc value, boolean transpose) {
        this.getAccess().setMatrix(value, transpose);
    }

    @Override
    public void setMatrix2x3(Matrix3x2fc value, boolean transpose) {
        this.getAccess().setMatrix2x3(value, transpose);
    }

    @Override
    public void setMatrix3x2(Matrix3x2fc value, boolean transpose) {
        this.getAccess().setMatrix3x2(value, transpose);
    }

    @Override
    public void setMatrix3x4(Matrix4x3fc value, boolean transpose) {
        this.getAccess().setMatrix3x4(value, transpose);
    }

    @Override
    public void setMatrix4x3(Matrix4x3fc value, boolean transpose) {
        this.getAccess().setMatrix4x3(value, transpose);
    }

    @Override
    public void setMatrix(Matrix2dc value) {
        this.getAccess().setMatrix(value);
    }

    @Override
    public void setMatrix(Matrix3dc value) {
        this.getAccess().setMatrix(value);
    }

    @Override
    public void setMatrix(Matrix4dc value) {
        this.getAccess().setMatrix(value);
    }

    @Override
    public void setMatrix2x3(Matrix3x2dc value) {
        this.getAccess().setMatrix2x3(value);
    }

    @Override
    public void setMatrix3x2(Matrix3x2dc value) {
        this.getAccess().setMatrix3x2(value);
    }

    @Override
    public void setMatrix3x4(Matrix4x3dc value) {
        this.getAccess().setMatrix3x4(value);
    }

    @Override
    public void setMatrix4x3(Matrix4x3dc value) {
        this.getAccess().setMatrix4x3(value);
    }

    @Override
    public void setMatrix(Matrix2dc value, boolean transpose) {
        this.getAccess().setMatrix(value, transpose);
    }

    @Override
    public void setMatrix(Matrix3dc value, boolean transpose) {
        this.getAccess().setMatrix(value, transpose);
    }

    @Override
    public void setMatrix(Matrix4dc value, boolean transpose) {
        this.getAccess().setMatrix(value, transpose);
    }

    @Override
    public void setMatrix2x3(Matrix3x2dc value, boolean transpose) {
        this.getAccess().setMatrix2x3(value, transpose);
    }

    @Override
    public void setMatrix3x2(Matrix3x2dc value, boolean transpose) {
        this.getAccess().setMatrix3x2(value, transpose);
    }

    @Override
    public void setMatrix3x4(Matrix4x3dc value, boolean transpose) {
        this.getAccess().setMatrix3x4(value, transpose);
    }

    @Override
    public void setMatrix4x3(Matrix4x3dc value, boolean transpose) {
        this.getAccess().setMatrix4x3(value, transpose);
    }
}
